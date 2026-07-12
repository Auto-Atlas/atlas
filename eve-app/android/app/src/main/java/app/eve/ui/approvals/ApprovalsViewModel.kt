package app.eve.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.ApproveOutcome
import app.eve.data.ApprovalRepository
import app.eve.data.CancelOutcome
import app.eve.data.DenyOutcome
import app.eve.data.RedirectOutcome
import app.eve.data.models.Approval
import app.eve.data.models.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the Approvals inbox state. Folds: (1) the fetched pending list, (2) a 1s countdown
 * ticker that expires cards locally, and (3) live WS approval_resolved/expired events. Calls
 * the repo for approve/deny and reflects the honest outcome (ok:false => SendFailed, 409 =>
 * resolved-elsewhere) — never a false success.
 *
 * Testable: scope, clock, and the stream event Flow are injected so coroutines-test can drive
 * virtual time and push synthetic events.
 */
class ApprovalsViewModel(
    private val repo: ApprovalRepository,
    private val streamEvents: Flow<StreamEvent>,
    injectedScope: CoroutineScope? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val tickMs: Long = 1_000,
) : ViewModel() {

    // Production: lifecycle-bound viewModelScope (cancelled in onCleared). Tests: a TestScope.
    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow<ApprovalsUiState>(ApprovalsUiState.Loading)
    val state: StateFlow<ApprovalsUiState> = _state.asStateFlow()

    /** Live per-task Agent Activity cards (live-delegation-approvals), newest first. */
    private val _agentActivity = MutableStateFlow<List<AgentTaskCard>>(emptyList())
    val agentActivity: StateFlow<List<AgentTaskCard>> = _agentActivity.asStateFlow()

    /** False while the live stream is down/reconnecting — the UI must SHOW it (a frozen
     *  feed that looks live is the guardrail violation this flag exists to prevent). */
    private val _streamHealthy = MutableStateFlow(true)
    val streamHealthy: StateFlow<Boolean> = _streamHealthy.asStateFlow()

    init {
        scope.launch { observeStream() }
        scope.launch { runTicker() }
    }

    fun refresh() {
        scope.launch {
            if (_state.value is ApprovalsUiState.Items || _state.value is ApprovalsUiState.Empty) {
                // keep current cards while refreshing; no flash to Loading
            } else {
                _state.value = ApprovalsUiState.Loading
            }
            when (val r = repo.pending()) {
                is ApiResult.Ok -> applyFetched(r.value)
                is ApiResult.Err -> applyError(r.error)
            }
        }
        scope.launch { loadAgentTasks() }
    }

    private suspend fun loadAgentTasks() {
        when (val r = repo.agentTasks()) {
            is ApiResult.Ok -> {
                val fetched = (r.value.active + r.value.recent).map { AgentTaskCard.fromDto(it) }
                // Keep richer local feeds for cards we already track; the fetch supplies
                // status/capabilities truth, the stream supplies the step lines.
                val local = _agentActivity.value.associateBy { it.id }
                _agentActivity.value = fetched.map { f ->
                    val prev = local[f.id] ?: return@map f
                    f.copy(feed = if (prev.feed.size > f.feed.size) prev.feed else f.feed)
                }
            }
            is ApiResult.Err -> Unit // the approvals fold already surfaces offline; keep stale cards
        }
    }

    private fun applyFetched(approvals: List<Approval>) {
        if (approvals.isEmpty()) {
            _state.value = ApprovalsUiState.Empty
            return
        }
        val existing = currentCards().associateBy { it.id }
        val cards = approvals.map { ap ->
            val prev = existing[ap.id]
            val secs = computeSecondsLeft(ap)
            ApprovalCardState(
                approval = ap,
                phase = if (secs <= 0) CardPhase.Expired else CardPhase.Pending(secs),
                expanded = prev?.expanded ?: false,
                secondsLeft = secs,
            )
        }
        _state.value = ApprovalsUiState.Items(cards)
    }

    private fun applyError(error: ApiError) {
        when (error) {
            is ApiError.NotConfigured -> _state.value = ApprovalsUiState.Offline()
            is ApiError.Offline -> _state.value = ApprovalsUiState.Offline(staleItems = currentCards())
            is ApiError.Unauthorized -> _state.value = ApprovalsUiState.Offline(staleItems = currentCards())
            else -> _state.value = ApprovalsUiState.Offline(staleItems = currentCards())
        }
    }

    fun toggleExpand(id: String) {
        updateCard(id) { it.copy(expanded = !it.expanded) }
    }

    /** Force a card's expanded state. Used by the notification deep-link to PRIME a card open
     *  (never collapse an already-open one, which a blind toggle would do). */
    fun setExpanded(id: String, expanded: Boolean) {
        updateCard(id) { it.copy(expanded = expanded) }
    }

    fun approve(id: String) {
        val card = currentCards().firstOrNull { it.id == id } ?: return
        if (!card.actionsEnabled) return
        val priorSeconds = card.secondsLeft
        updateCard(id) { it.copy(phase = CardPhase.Releasing) }
        scope.launch {
            val outcome = repo.approve(id)
            updateCard(id) { it.copy(phase = phaseFor(outcome, priorSeconds)) }
        }
    }

    fun deny(id: String) {
        val card = currentCards().firstOrNull { it.id == id } ?: return
        if (!card.actionsEnabled) return
        scope.launch {
            val outcome = repo.deny(id)
            updateCard(id) {
                it.copy(
                    phase = when (outcome) {
                        is DenyOutcome.Denied -> CardPhase.Denied
                        is DenyOutcome.AlreadyResolved -> CardPhase.Resolved(ResolvedOutcome.Elsewhere)
                        is DenyOutcome.Failed -> it.phase // leave pending; the error is transient
                    },
                )
            }
        }
    }

    private fun phaseFor(outcome: ApproveOutcome, priorSeconds: Long): CardPhase = when (outcome) {
        is ApproveOutcome.Sent -> CardPhase.Resolved(ResolvedOutcome.Success)
        is ApproveOutcome.SendFailed -> CardPhase.Resolved(ResolvedOutcome.SendFailed)
        is ApproveOutcome.AlreadyResolved -> CardPhase.Resolved(ResolvedOutcome.Elsewhere)
        // Transient transport failure (offline/401): fall back to pending so the owner can retry.
        is ApproveOutcome.Failed -> CardPhase.Pending(priorSeconds)
    }

    /** Owner cancels a running delegated task. Honest lifecycle: the card goes to
     *  CancelPending on a cooperative cancel and only flips Cancelled when the stream
     *  reports the observed stop (or the server says nothing was running). */
    fun cancelTask(id: String) {
        val card = _agentActivity.value.firstOrNull { it.id == id } ?: return
        if (!card.canCancel || card.cancelInFlight || card.isTerminal) return
        updateAgentCard(id) { it.copy(cancelInFlight = true) }
        scope.launch {
            when (val outcome = repo.cancelTask(id)) {
                is CancelOutcome.Requested -> updateAgentCard(id) {
                    it.copy(state = AgentTaskState.CancelPending, cancelInFlight = false,
                            detail = outcome.detail, feed = it.feed.append(outcome.detail))
                }
                is CancelOutcome.Cancelled -> updateAgentCard(id) {
                    it.copy(state = AgentTaskState.Cancelled, cancelInFlight = false,
                            canCancel = false, canRedirect = false,
                            detail = outcome.detail, feed = it.feed.append(outcome.detail))
                }
                is CancelOutcome.NotCancellable -> {
                    updateAgentCard(id) { it.copy(cancelInFlight = false) }
                    loadAgentTasks()   // our card was stale — refetch the truth
                }
                is CancelOutcome.Failed -> updateAgentCard(id) {
                    it.copy(cancelInFlight = false,
                            detail = "cancel didn't reach EVE — check the connection and retry")
                }
            }
        }
    }

    /** Owner steers a running delegated task; the steer lands at the agent's next check-in
     *  and the feed shows it landing (agent_task_redirected status=redirect_delivered). */
    fun redirectTask(id: String, instructions: String) {
        val text = instructions.trim()
        if (text.isEmpty()) return
        val card = _agentActivity.value.firstOrNull { it.id == id } ?: return
        if (!card.canRedirect || card.redirectInFlight || card.isTerminal) return
        updateAgentCard(id) { it.copy(redirectInFlight = true) }
        scope.launch {
            when (val outcome = repo.redirectTask(id, text)) {
                is RedirectOutcome.Staged -> updateAgentCard(id) {
                    it.copy(redirectInFlight = false, detail = outcome.detail,
                            feed = it.feed.append(outcome.detail))
                }
                is RedirectOutcome.NotSteerable -> {
                    updateAgentCard(id) { it.copy(redirectInFlight = false) }
                    loadAgentTasks()
                }
                is RedirectOutcome.Failed -> updateAgentCard(id) {
                    it.copy(redirectInFlight = false,
                            detail = "redirect didn't reach EVE — check the connection and retry")
                }
            }
        }
    }

    /** Folds one agent talk-back lifecycle event into the activity cards. */
    private fun onAgentTaskEvent(event: StreamEvent) {
        val id = event.taskId ?: event.id ?: return
        val existing = _agentActivity.value.firstOrNull { it.id == id }
        if (existing == null) {
            // Assigned missed (app was closed) — open the card from what the event carries.
            val card = AgentTaskCard(
                id = id,
                agent = AgentTaskCard.brainDisplayName(event.agent),
                taskText = event.task ?: event.summary ?: "",
                state = AgentTaskState.Working,
            )
            _agentActivity.value = listOf(card) + _agentActivity.value
        }
        updateAgentCard(id) { card ->
            when {
                event.isAgentTaskAssigned -> card.copy(
                    state = AgentTaskState.Working,
                    taskText = event.task ?: event.summary ?: card.taskText,
                    feed = card.feed.append(
                        "assigned to ${AgentTaskCard.brainDisplayName(event.agent ?: card.agent)}"),
                )
                event.isAgentProgress -> card.copy(
                    // A progress line never un-cancels: CancelPending sticks until terminal.
                    state = if (card.state == AgentTaskState.CancelPending) card.state
                            else AgentTaskState.Working,
                    feed = card.feed.append(event.text ?: ""),
                )
                event.isAgentQuestion -> card.copy(
                    state = if (card.state == AgentTaskState.CancelPending) card.state
                            else AgentTaskState.WaitingOnYou,
                    question = event.text,
                    feed = card.feed.append("asking: ${event.text ?: ""}"),
                )
                event.isAgentResult -> card.copy(
                    state = AgentTaskState.Done, question = null,
                    canCancel = false, canRedirect = false,
                    redirectReason = "task already finished",
                    fullResult = event.text,
                    feed = card.feed.append("done: ${(event.text ?: "").take(200)}"),
                )
                event.isAgentBlocker -> card.copy(
                    state = AgentTaskState.Failed, question = null,
                    canCancel = false, canRedirect = false,
                    redirectReason = "task already failed",
                    feed = card.feed.append("blocked: ${(event.text ?: "").take(200)}"),
                )
                event.isAgentTaskCancelled -> {
                    val terminal = event.status == "cancelled"
                    card.copy(
                        state = if (terminal) AgentTaskState.Cancelled
                                else AgentTaskState.CancelPending,
                        question = null, canRedirect = false,
                        redirectReason = "cancel outranks a steer",
                        canCancel = !terminal,
                        feed = card.feed.append(
                            if (terminal) "cancelled — the agent stopped"
                            else "cancel requested — stopping at the next check-in",
                        ),
                    )
                }
                event.isAgentTaskRedirected -> card.copy(
                    feed = card.feed.append(
                        if (event.status == "redirect_delivered")
                            "new instructions delivered: ${(event.text ?: "").take(200)}"
                        else
                            "redirect staged: ${(event.text ?: "").take(200)}",
                    ),
                )
                else -> card
            }
        }
    }

    private fun List<String>.append(line: String): List<String> {
        if (line.isBlank()) return this
        return (this + line).takeLast(AgentTaskCard.FEED_CAP)
    }

    private inline fun updateAgentCard(id: String, transform: (AgentTaskCard) -> AgentTaskCard) {
        _agentActivity.value = _agentActivity.value.map {
            if (it.id == id) transform(it) else it
        }
    }

    /** Folds one jarvis_agent brain-delegation trace event (claude code over ACP / codex /
     *  glm — single-brain routing, display only) into a watch-only activity card. */
    private fun onBrainDelegationEvent(event: StreamEvent) {
        val delegId = event.delegId ?: return
        val id = "deleg:$delegId"
        if (_agentActivity.value.none { it.id == id }) {
            _agentActivity.value = listOf(
                AgentTaskCard(
                    id = id,
                    agent = AgentTaskCard.brainDisplayName(event.brains?.firstOrNull() ?: event.brain),
                    taskText = event.task ?: "",
                    state = AgentTaskState.Working,
                    canCancel = false,
                    canRedirect = false,
                    redirectReason = AgentTaskCard.BRAIN_WATCH_ONLY_REASON,
                ),
            ) + _agentActivity.value
        }
        updateAgentCard(id) { card ->
            val brain = AgentTaskCard.brainDisplayName(event.brain)
            when {
                event.isDelegationStart -> card.copy(
                    taskText = event.task ?: card.taskText,
                    agent = AgentTaskCard.brainDisplayName(event.brains?.firstOrNull()),
                    feed = card.feed.append("assigned to ${AgentTaskCard.brainDisplayName(event.brains?.firstOrNull())}"),
                )
                event.isDelegationStep -> when (event.phase) {
                    StreamEvent.PHASE_TRY -> card.copy(feed = card.feed.append("trying $brain"))
                    StreamEvent.PHASE_WORKING -> {
                        // Heartbeats REPLACE the previous heartbeat line — a long run must
                        // not flood the feed with one line per tick.
                        val line = "$brain: working (${event.detail ?: "…"})"
                        val feed = if (card.feed.lastOrNull()?.startsWith("$brain: working") == true) {
                            card.feed.dropLast(1) + line
                        } else {
                            card.feed.append(line)
                        }
                        card.copy(feed = feed)
                    }
                    StreamEvent.PHASE_ANSWER -> card.copy(
                        feed = card.feed.append("$brain answered (${event.detail ?: ""})"),
                    )
                    StreamEvent.PHASE_FAIL -> card.copy(
                        feed = card.feed.append("$brain failed: ${event.detail ?: "no reason"}"),
                    )
                    else -> card
                }
                event.isDelegationEnd -> {
                    if (event.ok == true) {
                        card.copy(
                            state = AgentTaskState.Done,
                            fullResult = event.result,
                            feed = card.feed.append("done: ${(event.result ?: "").take(200)}"),
                        )
                    } else {
                        card.copy(
                            state = AgentTaskState.Failed,
                            feed = card.feed.append(
                                "failed: ${event.failures?.joinToString("; ") ?: "no reason given"}",
                            ),
                        )
                    }
                }
                else -> card
            }
        }
    }

    /** Folds a single live stream event into the current cards. Public for direct unit testing. */
    fun onStreamEvent(event: StreamEvent) {
        if (event.isAgentTaskEvent) {
            onAgentTaskEvent(event)
            return
        }
        if (event.isDelegationStart || event.isDelegationStep || event.isDelegationEnd) {
            onBrainDelegationEvent(event)
            return
        }
        val id = event.id ?: return
        when {
            event.isResolved -> updateCard(id) { card ->
                // If the card is already terminal locally (we acted), don't downgrade it.
                if (card.phase is CardPhase.Resolved || card.phase is CardPhase.Denied) {
                    card
                } else {
                    val outcome = when {
                        event.denied == true -> {
                            return@updateCard card.copy(phase = CardPhase.Denied)
                        }
                        event.ok == false -> ResolvedOutcome.SendFailed
                        else -> ResolvedOutcome.Elsewhere
                    }
                    card.copy(phase = CardPhase.Resolved(outcome))
                }
            }
            event.isExpired -> updateCard(id) { it.copy(phase = CardPhase.Expired) }
            // approval_pending: a new request arrived; trigger a refresh to fetch its details.
            event.isPending -> refresh()
        }
    }

    private suspend fun observeStream() {
        // The injected stream Flow COMPLETES WITH AN EXCEPTION on a dropped/refused/TLS-failed
        // WSS connection (callbackFlow close(e)). An unguarded collect would rethrow that into
        // this scope.launch and — with no CoroutineExceptionHandler on viewModelScope — crash the
        // process. This is the real-device HTTPS crash. retryWhen reconnects with backoff (the
        // live stream is best-effort; the inbox still works via refresh()); catch is the final
        // belt so nothing can escape. CancellationException is rethrown by retryWhen/catch.
        streamEvents
            .retryWhen { cause, _ ->
                // Reconnect on any non-cancellation failure after a short pause; let cancellation
                // (scope teardown) fall through to terminate the flow normally.
                if (cause is kotlin.coroutines.cancellation.CancellationException) {
                    false
                } else {
                    // The feed is DOWN — say so (reconnecting badge), never a frozen
                    // feed that looks live.
                    _streamHealthy.value = false
                    delay(3_000)
                    true
                }
            }
            .catch { _streamHealthy.value = false }
            .collect {
                _streamHealthy.value = true
                onStreamEvent(it)
            }
    }

    private suspend fun runTicker() {
        while (scopeActive()) {
            delay(tickMs)
            tick()
        }
    }

    private fun tick() {
        val cards = currentCards()
        if (cards.isEmpty()) return
        var changed = false
        val updated = cards.map { card ->
            if (card.phase is CardPhase.Pending) {
                val secs = computeSecondsLeft(card.approval)
                when {
                    secs <= 0 -> { changed = true; card.copy(phase = CardPhase.Expired, secondsLeft = 0) }
                    // Countdown unchanged this tick → keep the SAME instance so strong-skipping's
                    // identity check lets the card skip recomposition (no per-second churn).
                    secs == card.secondsLeft -> card
                    else -> { changed = true; card.copy(phase = CardPhase.Pending(secs), secondsLeft = secs) }
                }
            } else {
                card
            }
        }
        // Only publish a new list when something actually changed — an all-unchanged tick must not
        // emit a fresh StateFlow value (which would reflow the whole LazyColumn for nothing).
        if (changed) replaceCards(updated)
    }

    private fun computeSecondsLeft(ap: Approval): Long {
        val remaining = (ap.expiresAt * 1000.0 - nowMs()) / 1000.0
        return remaining.toLong().coerceAtLeast(0)
    }

    // ---- card-list plumbing -------------------------------------------------

    private fun currentCards(): List<ApprovalCardState> = when (val s = _state.value) {
        is ApprovalsUiState.Items -> s.cards
        is ApprovalsUiState.Offline -> s.staleItems
        else -> emptyList()
    }

    private fun replaceCards(cards: List<ApprovalCardState>) {
        _state.value = when (val s = _state.value) {
            is ApprovalsUiState.Items -> s.copy(cards = cards)
            is ApprovalsUiState.Offline -> s.copy(staleItems = cards)
            else -> _state.value
        }
    }

    private inline fun updateCard(id: String, crossinline transform: (ApprovalCardState) -> ApprovalCardState) {
        val cards = currentCards()
        if (cards.none { it.id == id }) return
        replaceCards(cards.map { if (it.id == id) transform(it) else it })
    }

    private fun scopeActive(): Boolean = scope.isActive
}
