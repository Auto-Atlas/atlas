package app.eve.ui.approvals

import androidx.compose.runtime.Immutable
import app.eve.data.models.Approval

/** Top-level screen state for the Approvals inbox (screens/approvals.md "States"). */
sealed interface ApprovalsUiState {
    /** First fetch in flight. */
    data object Loading : ApprovalsUiState

    /**
     * Can't reach EVE (off the tailnet). Visually distinct from Empty — never "all clear" while
     * blind. Carries the last-known queue to render greyed/stale with actions disabled.
     */
    data class Offline(val staleItems: List<ApprovalCardState> = emptyList()) : ApprovalsUiState

    /** Reached EVE, nothing waiting. */
    data object Empty : ApprovalsUiState

    /** Reached EVE, one or more cards. */
    data class Items(val cards: List<ApprovalCardState>) : ApprovalsUiState
}

/** Per-card lifecycle state. The card model carries the immutable approval plus its live state. */
@Immutable
data class ApprovalCardState(
    val approval: Approval,
    val phase: CardPhase,
    /** Whether the expanded detail is shown. */
    val expanded: Boolean = false,
    /** Live seconds remaining, recomputed by the ticker (independent of the fetched value). */
    val secondsLeft: Long,
) {
    val id: String get() = approval.id
    val isUrgent: Boolean get() = phase is CardPhase.Pending && secondsLeft in 0..59
    /** Actions are live only while pending AND online (offline state wraps cards separately). */
    val actionsEnabled: Boolean get() = phase is CardPhase.Pending && secondsLeft > 0
}

/** The mutually-exclusive lifecycle phases of a single approval card. */
sealed interface CardPhase {
    data class Pending(val secondsLeft: Long) : CardPhase
    data object Releasing : CardPhase
    data class Resolved(val outcome: ResolvedOutcome) : CardPhase
    data object Expired : CardPhase
    data object Denied : CardPhase
}

sealed interface ResolvedOutcome {
    /** release() returned ok:true — the tool fired. */
    data object Success : ResolvedOutcome

    /** Approved but release() returned ok:false — couldn't reach the service. Offer Retry. */
    data object SendFailed : ResolvedOutcome

    /** Crash mid-release; row stuck 'releasing'. Outcome unverified. */
    data object Unverified : ResolvedOutcome

    /** Resolved on another device (WS approval_resolved or a 409 on hold). */
    data object Elsewhere : ResolvedOutcome
}
