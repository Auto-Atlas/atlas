package app.eve.wear.notify

import app.eve.data.wear.ApprovalsSnapshot
import app.eve.wear.approvals.TestApprovals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on [planApprovalNotifications] — the whole notify/cancel decision, no Android. Every
 * branch of the contract is pinned: notify only NEW ids, cancel ids that vanished from an
 * AUTHORITATIVE snapshot, and change NOTHING on a server-down snapshot.
 */
class NotifyPlanTest {

    @Test
    fun first_snapshot_notifies_every_pending_id() {
        val snapshot = TestApprovals.pendingSnapshot(
            listOf(TestApprovals.invoice("a1"), TestApprovals.channel("c1")),
        )
        val plan = planApprovalNotifications(previouslyNotified = emptySet(), snapshot = snapshot)

        assertEquals(listOf("a1", "c1"), plan.toNotify.map { it.id })
        assertTrue(plan.toCancel.isEmpty())
        assertEquals(setOf("a1", "c1"), plan.newNotifiedIds)
    }

    @Test
    fun only_new_ids_are_notified_known_ones_are_not_repeated() {
        val snapshot = TestApprovals.pendingSnapshot(
            listOf(TestApprovals.invoice("a1"), TestApprovals.invoice("a2")),
        )
        val plan = planApprovalNotifications(previouslyNotified = setOf("a1"), snapshot = snapshot)

        assertEquals(listOf("a2"), plan.toNotify.map { it.id }, "a1 already notified — must not repost")
        assertTrue(plan.toCancel.isEmpty())
        assertEquals(setOf("a1", "a2"), plan.newNotifiedIds)
    }

    @Test
    fun vanished_id_from_authoritative_snapshot_is_cancelled() {
        // We had notified a1 + a2; the authoritative list now only has a2 → a1 resolved/expired.
        val snapshot = TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a2")))
        val plan = planApprovalNotifications(previouslyNotified = setOf("a1", "a2"), snapshot = snapshot)

        assertTrue(plan.toNotify.isEmpty(), "a2 already notified, a1 is gone — nothing new")
        assertEquals(setOf("a1"), plan.toCancel)
        assertEquals(setOf("a2"), plan.newNotifiedIds)
    }

    @Test
    fun authoritative_empty_list_cancels_all_prior_notifications() {
        val snapshot = TestApprovals.pendingSnapshot(emptyList())
        val plan = planApprovalNotifications(previouslyNotified = setOf("a1", "a2"), snapshot = snapshot)

        assertTrue(plan.toNotify.isEmpty())
        assertEquals(setOf("a1", "a2"), plan.toCancel)
        assertTrue(plan.newNotifiedIds.isEmpty())
    }

    @Test
    fun server_down_snapshot_changes_nothing() {
        // Not authoritative: the approvals may still be pending — never cancel, never re-notify, and
        // leave the persisted id set EXACTLY as it was.
        val prior = setOf("a1", "a2")
        val snapshot = TestApprovals.serverDownSnapshot("cannot reach EVE: timeout")
        val plan = planApprovalNotifications(previouslyNotified = prior, snapshot = snapshot)

        assertTrue(plan.toNotify.isEmpty())
        assertTrue(plan.toCancel.isEmpty())
        assertEquals(prior, plan.newNotifiedIds, "server-down must leave the dedupe set untouched")
    }

    @Test
    fun server_down_with_stale_list_still_changes_nothing() {
        // Defensive: even if a down snapshot carried a non-empty (stale) list, it is not authoritative.
        val prior = setOf("a1")
        val snapshot = ApprovalsSnapshot(
            approvals = listOf(TestApprovals.invoice("a9")),
            fetchedAtEpochMs = 5_000L,
            serverReachable = false,
            errorDetail = "unauthorized (401)",
        )
        val plan = planApprovalNotifications(previouslyNotified = prior, snapshot = snapshot)

        assertTrue(plan.toNotify.isEmpty(), "a9 from a non-authoritative snapshot must NOT be notified")
        assertTrue(plan.toCancel.isEmpty())
        assertEquals(prior, plan.newNotifiedIds)
    }
}
