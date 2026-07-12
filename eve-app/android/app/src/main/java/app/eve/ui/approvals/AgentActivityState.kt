package app.eve.ui.approvals

import androidx.compose.runtime.Immutable
import app.eve.data.models.AgentTaskDto

/**
 * One live "Agent Activity" card: a delegated task (Hermes/Claude/Codex via the talk-back
 * fabric) with its scrolling step feed and HONEST controls (live-delegation-approvals).
 * State names match what the owner sees: a cancel is only Cancelled once the stop was
 * actually observed — until then it is CancelPending, never a green-looking lie.
 */
@Immutable
data class AgentTaskCard(
    val id: String,
    val agent: String,
    val taskText: String,
    val state: AgentTaskState,
    /** Newest-last live step lines (capped at [FEED_CAP]). */
    val feed: List<String> = emptyList(),
    val canCancel: Boolean = true,
    val canRedirect: Boolean = true,
    /** Why Redirect is disabled — shown next to the disabled button, never a dead no-op. */
    val redirectReason: String? = null,
    /** The outstanding question when state is WaitingOnYou. */
    val question: String? = null,
    val cancelInFlight: Boolean = false,
    val redirectInFlight: Boolean = false,
    /** Last honest action/server detail line (e.g. transport error) surfaced on the card. */
    val detail: String? = null,
    /** Untruncated final result — the tap-detail view shows it in full. */
    val fullResult: String? = null,
) {
    val isTerminal: Boolean
        get() = state == AgentTaskState.Done || state == AgentTaskState.Failed ||
            state == AgentTaskState.Cancelled

    companion object {
        const val FEED_CAP = 50

        /** Honest reason shown on the disabled controls of a brain-delegation card. */
        const val BRAIN_WATCH_ONLY_REASON =
            "brain runs have no talk-back channel yet — watch-only"

        /** jarvis_agent brain ids → what the owner calls them. "acp" IS Claude Code
         *  (driven over ACP `claude --bg` — no `claude -p`, no agent SDK). */
        fun brainDisplayName(brain: String?): String = when (brain) {
            "acp" -> "claude code"
            null, "" -> "agent"
            else -> brain
        }

        /** Map a server agent_tasks row status onto the visual state. */
        fun stateFor(status: String?): AgentTaskState = when (status) {
            "awaiting_user" -> AgentTaskState.WaitingOnYou
            "cancel_requested" -> AgentTaskState.CancelPending
            "resolved" -> AgentTaskState.Done
            "failed", "expired" -> AgentTaskState.Failed
            "cancelled" -> AgentTaskState.Cancelled
            // pending / claimed / resolving / answered — the agent is on it.
            else -> AgentTaskState.Working
        }

        /** Build a card from a fetched /v1/agent-tasks row (screen-open seed). */
        fun fromDto(dto: AgentTaskDto): AgentTaskCard {
            val state = stateFor(dto.effectiveStatus ?: dto.status)
            val resultText = dto.result?.get("text")?.toString()?.trim('"')
            val seedFeed = buildList {
                if (state == AgentTaskState.Done && !resultText.isNullOrBlank()) {
                    add("done: ${resultText.take(200)}")
                }
                if (state == AgentTaskState.Failed && !resultText.isNullOrBlank()) {
                    add("blocked: ${resultText.take(200)}")
                }
                if (dto.redirectPending) add("redirect staged — lands at the next check-in")
            }
            return AgentTaskCard(
                id = dto.id,
                agent = brainDisplayName(dto.agent),
                taskText = dto.task ?: dto.summary ?: "",
                state = state,
                feed = seedFeed,
                canCancel = dto.capabilities?.cancel ?: !stateIsTerminal(state),
                canRedirect = dto.capabilities?.redirect ?: false,
                redirectReason = dto.capabilities?.redirectReason,
                question = dto.question?.question,
            )
        }

        private fun stateIsTerminal(state: AgentTaskState): Boolean =
            state == AgentTaskState.Done || state == AgentTaskState.Failed ||
                state == AgentTaskState.Cancelled
    }
}

/** Visual state of a delegated-task card — color + label are decided in the UI layer. */
sealed interface AgentTaskState {
    /** The agent is actively working. */
    data object Working : AgentTaskState

    /** The agent asked a question and is blocked on the owner. */
    data object WaitingOnYou : AgentTaskState

    /** Cancel requested; the stop has not been observed yet (honest intermediate). */
    data object CancelPending : AgentTaskState

    data object Done : AgentTaskState
    data object Failed : AgentTaskState
    data object Cancelled : AgentTaskState
}
