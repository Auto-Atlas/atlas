package app.eve.wear.approvals

import app.eve.data.models.Approval

/**
 * The honest top-level states of the watch approvals experience. Each names a real condition — there
 * is no placeholder/optimistic state. Only [ServerDown] carries a stale list, and it is labelled as
 * stale (never shown as if current).
 */
sealed interface WearApprovalsUiState {
    /** No snapshot from the phone yet (and no phone-link problem diagnosed). */
    data object Loading : WearApprovalsUiState

    /**
     * The watch<->phone Data Layer leg is down (diagnosed by the NodeClient query BEFORE the first
     * snapshot). [reason] is the real cause. Offers a retry-once of the link check.
     */
    data class NoPhone(val reason: String) : WearApprovalsUiState

    /**
     * The phone reached the watch but could NOT reach EVE (`serverReachable=false`). [detail] is the
     * phone's real error. If a previous good list exists it is carried in [staleApprovals] and MUST
     * be rendered explicitly as stale (with [fetchedAtEpochMs] driving the "from <time ago>" label).
     */
    data class ServerDown(
        val detail: String,
        val staleApprovals: List<Approval>?,
        val fetchedAtEpochMs: Long,
    ) : WearApprovalsUiState

    /** Reached EVE, nothing pending. The REAL empty state, not filler. */
    data object Empty : WearApprovalsUiState

    /** Reached EVE, one or more pending approvals. */
    data class Pending(val approvals: List<Approval>) : WearApprovalsUiState
}

/**
 * Per-approval action lifecycle (approve/deny). Kept separate from [WearApprovalsUiState] because
 * the authoritative list is always the phone's snapshot — an action only annotates ONE row with its
 * in-flight/terminal banner; it never deletes the row locally (the next snapshot does that).
 */
sealed interface WearActionState {
    /** No action taken on this approval. */
    data object Idle : WearActionState

    /** An approve/deny was sent; awaiting the phone's result. [requestId] correlates the reply. */
    data class InFlight(val requestId: String) : WearActionState

    /**
     * Terminal outcome. [message] is the EXACT honest user copy (naming which leg broke on failure);
     * [tone] drives color only (text always carries the meaning — a11y, never color alone).
     */
    data class Resolved(val message: String, val tone: Tone) : WearActionState

    /** Color intent for a resolved banner. */
    enum class Tone { Positive, Neutral, Negative }
}
