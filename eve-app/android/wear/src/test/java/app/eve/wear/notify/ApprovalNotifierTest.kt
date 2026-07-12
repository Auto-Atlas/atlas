package app.eve.wear.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import app.eve.wear.MainActivity
import app.eve.wear.approvals.TestApprovals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric guard on the WATCH-local approval notification: it enforces the house safety contract
 * (EXACTLY ONE action — Deny; NO approve of any kind), the content-tap deep link to the DETAIL
 * screen, and the pure notify/cancel plan applied through [ApprovalNotifier.onSnapshot]. @Config
 * sdk=34 + kotlin.test asserts, matching the module's other Robolectric tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalNotifierTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val nm: NotificationManager
        get() = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager

    /** In-memory dedupe store so the test controls the "already notified" set exactly. */
    private class FakeStore(var ids: Set<String> = emptySet()) : NotifiedIdsStore {
        override fun load(): Set<String> = ids
        override fun save(ids: Set<String>) { this.ids = ids }
    }

    // ---- the no-one-tap-approve contract ------------------------------------

    @Test
    fun notification_has_exactly_one_action_and_it_is_deny() {
        val n = ApprovalNotifier(FakeStore()).buildApproval(app, TestApprovals.invoice("a1"))
        assertEquals(1, n.actions?.size ?: 0, "the wrist approval must expose ONE action only")
        assertEquals("Deny", n.actions!![0].title.toString())
        // The house rule: no approve/fire button of any kind — approve must hold on the detail screen.
        assertTrue(
            n.actions!!.none { it.title.toString().contains("approve", ignoreCase = true) },
            "no approve action may exist on the wrist notification",
        )
    }

    @Test
    fun title_and_text_come_from_the_shared_formatting() {
        // invoice("a1"): 2 x $600 = $1,200 invoice, requested by Jamie.
        val n = ApprovalNotifier(FakeStore()).buildApproval(app, TestApprovals.invoice("a1"))
        assertEquals("$1,200 invoice", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Requested by Jamie · $1,200", n.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun content_tap_deep_links_to_the_approval_detail_screen() {
        val n = ApprovalNotifier(FakeStore()).buildApproval(app, TestApprovals.invoice("a1"))
        val saved = shadowOf(n.contentIntent).savedIntent
        assertEquals("a1", saved.getStringExtra(ApprovalNotifier.EXTRA_OPEN_APPROVAL_ID))
        assertEquals(MainActivity::class.java.name, saved.component?.className)
    }

    @Test
    fun deny_action_broadcasts_to_the_deny_receiver_with_id_and_title() {
        val n = ApprovalNotifier(FakeStore()).buildApproval(app, TestApprovals.invoice("a1"))
        val saved = shadowOf(n.actions!![0].actionIntent).savedIntent
        assertEquals(ApprovalNotifier.ACTION_DENY, saved.action)
        assertEquals("a1", saved.getStringExtra(ApprovalNotifier.EXTRA_APPROVAL_ID))
        assertEquals("$1,200 invoice", saved.getStringExtra(ApprovalNotifier.EXTRA_TITLE))
        assertEquals(WearDenyReceiver::class.java.name, saved.component?.className)
    }

    // ---- channel + notify/cancel plan ---------------------------------------

    @Test
    fun ensure_channel_creates_the_high_importance_approvals_channel() {
        ApprovalNotifier(FakeStore()).ensureChannel(app)
        val channel = nm.getNotificationChannel(ApprovalNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Approvals", channel.name.toString())
    }

    @Test
    fun on_snapshot_posts_new_pending_then_cancels_when_it_vanishes() {
        val store = FakeStore()
        val notifier = ApprovalNotifier(store)

        notifier.onSnapshot(app, TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        assertNotNull(shadowOf(nm).getNotification("a1".hashCode()), "new pending approval must be posted")
        assertEquals(setOf("a1"), store.ids)

        // Authoritative empty snapshot: the row resolved on the server → the wrist notification clears.
        notifier.onSnapshot(app, TestApprovals.pendingSnapshot(emptyList()))
        assertNull(shadowOf(nm).getNotification("a1".hashCode()), "vanished approval must be cancelled")
        assertTrue(store.ids.isEmpty())
    }

    @Test
    fun server_down_snapshot_posts_nothing_and_leaves_the_store_untouched() {
        val store = FakeStore(setOf("a1"))
        ApprovalNotifier(store).onSnapshot(app, TestApprovals.serverDownSnapshot("cannot reach EVE: timeout"))
        assertNull(shadowOf(nm).getNotification("a9".hashCode()))
        assertEquals(setOf("a1"), store.ids, "server-down must not touch the dedupe set")
    }

    // ---- in-flight + terminal deny updates ----------------------------------

    @Test
    fun denying_update_removes_the_deny_action_to_kill_the_double_tap() {
        val n = ApprovalNotifier(FakeStore()).buildDenying(app, "a1", "$1,200 invoice")
        assertEquals(0, n.actions?.size ?: 0, "no Deny action while denying (no double-tap race)")
        assertEquals("Denying…", n.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun deny_result_renders_message_and_auto_dismisses_on_success() {
        val denied = ApprovalNotifier(FakeStore())
            .buildDenyResult(app, "a1", "T", DenyUpdate("Denied", autoDismiss = true))
        assertEquals("Denied", denied.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(0, denied.actions?.size ?: 0)
        assertTrue(denied.timeoutAfter > 0, "a successful deny should self-clear")

        val failed = ApprovalNotifier(FakeStore())
            .buildDenyResult(app, "a1", "T", DenyUpdate.NoReply)
        assertEquals("No reply from phone — check the EVE app", failed.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(0L, failed.timeoutAfter, "a failure must stay visible (no auto-dismiss)")
    }
}
