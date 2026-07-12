package app.eve.wear.notify

import app.eve.data.models.Approval
import app.eve.data.wear.ApprovalsSnapshot

/**
 * The pure, testable decision of what wrist notifications to change for one incoming approvals
 * snapshot — no Android, no I/O. Given the ids we have ALREADY notified and the newest snapshot:
 *
 *  - [toNotify]      — approvals whose id is NEW (not yet notified). One wrist notification each.
 *  - [toCancel]      — ids we notified before that have VANISHED from an authoritative snapshot
 *                      (resolved/expired on the server); cancel their notifications.
 *  - [newNotifiedIds]— the id set to persist as "currently notified" after applying this plan.
 *
 * Honesty rule (no silent fallback): a `serverReachable=false` snapshot is NOT authoritative — the
 * approvals may still be pending, the phone just couldn't confirm. So it changes NOTHING: nothing
 * new is notified, nothing is cancelled, and the persisted id set is left exactly as it was.
 */
data class NotifyPlan(
    val toNotify: List<Approval>,
    val toCancel: Set<String>,
    val newNotifiedIds: Set<String>,
)

/**
 * Compute the [NotifyPlan] for [snapshot] given the ids already notified ([previouslyNotified]).
 * Pure — the Android shell ([ApprovalNotifier]) applies it. See [NotifyPlan] for the rules.
 */
fun planApprovalNotifications(
    previouslyNotified: Set<String>,
    snapshot: ApprovalsSnapshot,
): NotifyPlan {
    // Server-down is not authoritative: leave every existing wrist notification exactly as-is.
    if (!snapshot.serverReachable) {
        return NotifyPlan(toNotify = emptyList(), toCancel = emptySet(), newNotifiedIds = previouslyNotified)
    }
    val currentIds = snapshot.approvals.mapTo(LinkedHashSet()) { it.id }
    val toNotify = snapshot.approvals.filter { it.id !in previouslyNotified }
    // Ids we told the wrist about that are no longer pending in this authoritative list.
    val toCancel = previouslyNotified - currentIds
    return NotifyPlan(toNotify = toNotify, toCancel = toCancel, newNotifiedIds = currentIds)
}
