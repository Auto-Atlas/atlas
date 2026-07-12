package app.eve.wear.approvals

import app.eve.ASSISTANT_NAME
import app.eve.data.models.Approval
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.data.wear.WearLink
import app.eve.wear.PhoneLinkState
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import app.eve.wear.data.SnapshotSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns the watch approvals experience. Folds two honest inputs:
 *  1. the phone's retained [ApprovalsSnapshot] stream ([SnapshotSource]) — the SINGLE source of
 *     truth for what is pending / server-down / empty, and
 *  2. per-action approve/deny round-trips over the [GatewayClient], each mapped to an honest
 *     terminal banner that NAMES which leg broke (Data-Layer down / no reply / server unreachable).
 *
 * The ViewModel NEVER deletes an approval locally: an action only marks that row's
 * [WearActionState]; the row actually disappears when the phone's NEXT snapshot (which the phone
 * bridge always re-fetches after acting) no longer lists it. This keeps the watch and the server
 * from ever drifting.
 *
 * Determinism/testability mirrors :app's ApprovalsViewModel: the scope, clock and id generator are
 * injected so coroutines-test drives virtual time and correlates results by a known requestId. All
 * mutation happens on the injected (single-threaded Main/Test) dispatcher, so the [pendingResults]
 * map needs no extra synchronization — same convention as the phone ViewModel.
 */
class WearApprovalsViewModel(
    private val snapshotSource: SnapshotSource,
    private val gateway: GatewayClient,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _uiState = MutableStateFlow<WearApprovalsUiState>(WearApprovalsUiState.Loading)
    val uiState: StateFlow<WearApprovalsUiState> = _uiState.asStateFlow()

    /** Per-approval action banners, keyed by approval id. Absent id == [WearActionState.Idle]. */
    private val _actions = MutableStateFlow<Map<String, WearActionState>>(emptyMap())
    val actions: StateFlow<Map<String, WearActionState>> = _actions.asStateFlow()

    /** The last serverReachable=true list, so a later ServerDown can label a stale list honestly. */
    private var lastGoodApprovals: List<Approval> = emptyList()

    /** True once ANY snapshot has been received — after that the snapshot is the source of truth. */
    private var haveSnapshot = false

    /** In-flight action correlation: requestId -> the awaiter completed when its result arrives. */
    private val pendingResults = mutableMapOf<String, CompletableDeferred<WearActionResult>>()

    init {
        scope.launch { collectSnapshots() }
        scope.launch { collectResults() }
    }

    // ---- Snapshot fold (single source of truth) -----------------------------

    private suspend fun collectSnapshots() {
        snapshotSource.snapshots().collect { snapshot -> applySnapshot(snapshot) }
    }

    private fun applySnapshot(snapshot: ApprovalsSnapshot) {
        haveSnapshot = true
        // A real snapshot answers the "is data ever coming?" question — stop the watchdog.
        firstSnapshotWatchdog?.cancel()
        _uiState.value = when {
            !snapshot.serverReachable -> WearApprovalsUiState.ServerDown(
                detail = snapshot.errorDetail ?: "Phone can't reach $ASSISTANT_NAME",
                staleApprovals = lastGoodApprovals.takeIf { it.isNotEmpty() },
                fetchedAtEpochMs = snapshot.fetchedAtEpochMs,
            )
            snapshot.approvals.isEmpty() -> {
                lastGoodApprovals = emptyList()
                pruneActionsTo(emptySet())
                WearApprovalsUiState.Empty
            }
            else -> {
                lastGoodApprovals = snapshot.approvals
                // An authoritative (serverReachable=true) list is the truth: drop action banners for
                // rows that are no longer pending (they resolved on the server and vanished).
                pruneActionsTo(snapshot.approvals.mapTo(mutableSetOf()) { it.id })
                WearApprovalsUiState.Pending(snapshot.approvals)
            }
        }
    }

    /** Keep only action banners whose approval id survives in the newest authoritative snapshot. */
    private fun pruneActionsTo(liveIds: Set<String>) {
        _actions.update { current -> current.filterKeys { it in liveIds } }
    }

    // ---- Phone-link diagnosis (pre-first-snapshot only) ---------------------

    /**
     * Feed the latest phone-link diagnosis from the NodeClient query (MainActivity). It only affects
     * the top-level state BEFORE the first snapshot: once the phone has written any snapshot, the
     * snapshot is authoritative and this is ignored (a stale/retained snapshot still beats guessing).
     */
    fun reportPhoneLink(link: PhoneLinkState) {
        if (haveSnapshot) return
        _uiState.value = when (link) {
            is PhoneLinkState.Checking -> WearApprovalsUiState.Loading
            is PhoneLinkState.Connected -> WearApprovalsUiState.Loading // wait for the snapshot
            is PhoneLinkState.NotReachable ->
                WearApprovalsUiState.NoPhone("Phone unreachable — Data Layer down")
            is PhoneLinkState.Failed ->
                WearApprovalsUiState.NoPhone("Phone link failed: ${link.reason}")
        }
    }

    // ---- Foreground refresh (one-shot, never a loop) ------------------------

    /** The pending "refresh sent — is a first snapshot ever coming?" watchdog (one at a time). */
    private var firstSnapshotWatchdog: Job? = null

    /**
     * Ask the phone to push fresh snapshots now. Call ONCE per foreground (onResume) — no timer.
     *
     * Pre-first-snapshot honesty (gap found on the Wear OS 5 emulator, 2026-07-10: a connected
     * companion node WITHOUT the Atlas gateway capability left the UI on an eternal spinner): while no
     * snapshot has ever arrived, the refresh outcome is folded into a named state —
     *  - NoGatewayNode → "phone link up, but Atlas isn't reachable on the phone" (open the Atlas app)
     *  - SendFailed    → the Data-Layer leg with the real reason
     *  - Sent          → a single [FIRST_SNAPSHOT_WAIT_MS] watchdog; if the phone never writes,
     *                    say so instead of spinning. Any arriving snapshot wins immediately.
     * After the first snapshot, snapshots are authoritative and refresh outcomes are not surfaced.
     */
    fun requestRefresh() {
        scope.launch {
            val outcome = gateway.requestRefresh()
            if (haveSnapshot) return@launch
            when (outcome) {
                SendOutcome.NoGatewayNode -> _uiState.value = WearApprovalsUiState.NoPhone(
                    "Phone link up, but $ASSISTANT_NAME isn't reachable on the phone — open the $ASSISTANT_NAME app",
                )
                is SendOutcome.SendFailed -> _uiState.value = WearApprovalsUiState.NoPhone(
                    "${WearActionCopy.DATA_LAYER_DOWN}: ${outcome.reason}",
                )
                SendOutcome.Sent -> {
                    firstSnapshotWatchdog?.cancel()
                    firstSnapshotWatchdog = scope.launch {
                        delay(FIRST_SNAPSHOT_WAIT_MS)
                        if (!haveSnapshot) {
                            _uiState.value = WearApprovalsUiState.NoPhone(
                                "No data from the phone yet — open the $ASSISTANT_NAME app on your phone",
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Actions ------------------------------------------------------------

    fun approve(approvalId: String) = submit(WearLink.PATH_ACTION_APPROVE, approvalId)

    fun deny(approvalId: String) = submit(WearLink.PATH_ACTION_DENY, approvalId)

    private fun submit(path: String, approvalId: String) {
        val requestId = newRequestId()
        val isInvoice = approvalById(approvalId)?.isInvoice == true
        val deferred = CompletableDeferred<WearActionResult>()
        // Register the awaiter BEFORE sending so a fast phone reply can never be missed.
        pendingResults[requestId] = deferred
        setAction(approvalId, WearActionState.InFlight(requestId))

        scope.launch {
            try {
                when (val send = gateway.sendAction(path, WearAction(requestId, approvalId))) {
                    // Leg 1 — watch<->phone Data Layer down. Immediate, named, honest.
                    SendOutcome.NoGatewayNode ->
                        setAction(approvalId, fail(WearActionCopy.DATA_LAYER_DOWN))
                    is SendOutcome.SendFailed ->
                        setAction(approvalId, fail("${WearActionCopy.DATA_LAYER_DOWN}: ${send.reason}"))
                    // Sent — await the correlated result; a silence past the window is an honest failure.
                    SendOutcome.Sent -> {
                        val result = withTimeoutOrNull(RESULT_TIMEOUT_MS) { deferred.await() }
                        setAction(
                            approvalId,
                            if (result == null) fail("No reply from phone") else mapResult(result, isInvoice),
                        )
                    }
                }
            } finally {
                pendingResults.remove(requestId)
            }
        }
    }

    private suspend fun collectResults() {
        gateway.results().collect { result ->
            // Complete the matching awaiter. An unmatched result (already timed out / unknown id) is
            // intentionally a no-op: the row's fate is the next snapshot, never a local mutation.
            pendingResults[result.requestId]?.complete(result)
        }
    }

    /**
     * Map the phone's honest [WearActionResult] to user copy — delegated to [WearActionCopy] so the
     * in-app banner and the wrist-notification deny flow share ONE source of the wording.
     */
    private fun mapResult(result: WearActionResult, isInvoice: Boolean): WearActionState.Resolved =
        WearActionCopy.forResult(result, isInvoice)

    private fun fail(message: String) = WearActionState.Resolved(message, WearActionState.Tone.Negative)

    // ---- helpers ------------------------------------------------------------

    private fun setAction(approvalId: String, state: WearActionState) {
        _actions.update { it + (approvalId to state) }
    }

    private fun approvalById(id: String): Approval? =
        when (val s = _uiState.value) {
            is WearApprovalsUiState.Pending -> s.approvals
            is WearApprovalsUiState.ServerDown -> s.staleApprovals ?: emptyList()
            else -> lastGoodApprovals
        }.firstOrNull { it.id == id }

    companion object {
        /** No reply from the phone within this window is an honest failure, NEVER a fake success. */
        internal const val RESULT_TIMEOUT_MS = 20_000L

        /** Refresh was delivered but no snapshot ever arrived — after this, say so (never spin). */
        internal const val FIRST_SNAPSHOT_WAIT_MS = 15_000L
    }
}
