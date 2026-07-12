package app.eve.wear.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.eve.data.models.Approval
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.wear.MainActivity
import app.eve.wear.R
import app.eve.wear.ui.ApprovalFormatting

/**
 * The WATCH-local approval notification surface. The phone tags its own approval notification with
 * [app.eve.data.wear.WearLink.BRIDGE_TAG_APPROVAL] and the watch EXCLUDES that tag from bridging
 * (see [app.eve.wear.WearApplication]), so THIS is the single wrist copy of a pending approval.
 *
 * Safety contract (mirrors the phone's Notifications, house rule "Approve always holds"): the wrist
 * notification has EXACTLY ONE action — Deny (the safe direction, one tap). There is NO approve
 * action of any kind; approving requires opening the detail screen and holding. Content tap opens
 * [MainActivity] deep-linked to that approval's DETAIL screen (where hold-to-approve lives).
 *
 * The notify/cancel decision is the pure [planApprovalNotifications]; this shell only applies it and
 * persists the dedupe ids via [NotifiedIdsStore].
 */
class ApprovalNotifier(private val store: NotifiedIdsStore) {

    /**
     * Apply one incoming authoritative-or-not snapshot: notify NEW pending approvals, cancel ones
     * that vanished from an authoritative snapshot, persist the new id set. A `serverReachable=false`
     * snapshot changes nothing (the pure plan enforces this). Loud-but-non-fatal: a failure to post
     * one notification is logged, never crashes the Data-Layer listener.
     */
    fun onSnapshot(context: Context, snapshot: ApprovalsSnapshot) {
        ensureChannel(context)
        val plan = planApprovalNotifications(store.load(), snapshot)
        plan.toCancel.forEach { cancel(context, it) }
        plan.toNotify.forEach { approval ->
            try {
                notificationManager(context).notify(notificationId(approval.id), buildApproval(context, approval))
            } catch (t: Throwable) {
                Log.e(TAG, "posting wrist approval ${approval.id} failed: ${t.message}", t)
            }
        }
        store.save(plan.newNotifiedIds)
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Approvals",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "EVE approvals that need your decision" }
        notificationManager(context).createNotificationChannel(channel)
    }

    fun cancel(context: Context, approvalId: String) {
        notificationManager(context).cancel(notificationId(approvalId))
    }

    // ---- notification builders (internal so Robolectric can assert them directly) --------------

    /** The pending-approval notification: title, trust+amount line, content->detail, ONE Deny action. */
    internal fun buildApproval(context: Context, approval: Approval): Notification {
        val title = ApprovalFormatting.title(approval)
        val text = notificationText(approval)
        return baseBuilder(context, approval.id, title, text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // The ONLY action — Deny (safe, one tap). NO approve action: approve holds on the detail screen.
            .addAction(0, "Deny", denyIntent(context, approval.id, title))
            .build()
    }

    /** In-flight update: "Denying…", Deny action REMOVED (no double-tap race), ongoing. */
    internal fun buildDenying(context: Context, approvalId: String, title: String): Notification =
        baseBuilder(context, approvalId, title, DENYING_TEXT)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

    /** Terminal update: the honest [DenyUpdate] copy; auto-dismisses only on the safe resolved states. */
    internal fun buildDenyResult(context: Context, approvalId: String, title: String, update: DenyUpdate): Notification {
        val b = baseBuilder(context, approvalId, title, update.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(update.message))
            .setOngoing(false)
            .setAutoCancel(true)
        if (update.autoDismiss) b.setTimeoutAfter(RESOLVED_DISMISS_MS)
        return b.build()
    }

    /** Post the "Denying…" in-flight update (from [WearDenyReceiver]). */
    fun postDenying(context: Context, approvalId: String, title: String) {
        notificationManager(context).notify(notificationId(approvalId), buildDenying(context, approvalId, title))
    }

    /** Post the terminal deny result (from [WearDenyReceiver]). */
    fun postDenyResult(context: Context, approvalId: String, title: String, update: DenyUpdate) {
        notificationManager(context).notify(notificationId(approvalId), buildDenyResult(context, approvalId, title, update))
    }

    // ---- shared bits ---------------------------------------------------------------------------

    private fun baseBuilder(context: Context, approvalId: String, title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eve_mark)
            .setContentTitle(title)
            .setContentText(text)
            // Content tap always opens the DETAIL screen for this approval (where hold-to-approve is).
            .setContentIntent(openDetailIntent(context, approvalId))

    /** Requester trust line, plus the invoice/channel amount when there is one. */
    internal fun notificationText(approval: Approval): String {
        val line = ApprovalFormatting.requesterLine(approval)
        return ApprovalFormatting.amountLabel(approval)?.let { "$line · $it" } ?: line
    }

    private fun openDetailIntent(context: Context, approvalId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            // WearRecents lint: avoid FLAG_ACTIVITY_CLEAR_TOP (and NEW_TASK) on Wear so the app
            // stays a single well-behaved recents entry. MainActivity is launchMode=singleTop, so
            // SINGLE_TOP alone delivers the approval id via onNewIntent when it's already on top;
            // PendingIntent.getActivity supplies NEW_TASK itself for a cold start.
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_APPROVAL_ID, approvalId)
        }
        return PendingIntent.getActivity(
            context,
            approvalId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun denyIntent(context: Context, approvalId: String, title: String): PendingIntent {
        val intent = Intent(context, WearDenyReceiver::class.java).apply {
            action = ACTION_DENY
            putExtra(EXTRA_APPROVAL_ID, approvalId)
            // Carry the title so the receiver can rebuild the "Denying…"/result update without the row.
            putExtra(EXTRA_TITLE, title)
        }
        return PendingIntent.getBroadcast(
            context,
            ("deny:$approvalId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notificationId(approvalId: String): Int = approvalId.hashCode()

    companion object {
        const val CHANNEL_ID = "eve_approvals_wear"

        /** Content-intent extra: the approval id MainActivity should deep-link to the DETAIL screen. */
        const val EXTRA_OPEN_APPROVAL_ID = "open_approval_id"

        /** Deny-broadcast action + extras handled by [WearDenyReceiver]. */
        const val ACTION_DENY = "app.eve.wear.action.DENY"
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_TITLE = "approval_title"

        private const val DENYING_TEXT = "Denying…"
        private const val RESOLVED_DISMISS_MS = 6_000L
        private const val TAG = "ApprovalNotifier"
    }
}
