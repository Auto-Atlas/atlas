package app.eve.push

import app.eve.ASSISTANT_NAME
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.eve.MainActivity
import app.eve.R
import app.eve.data.wear.WearLink

/**
 * Builds approval notifications. CRITICAL safety contract (screens/approvals.md, spec §2.2):
 * the **Review** action is a CONTENT intent that OPENS the primed expanded card — it must
 * NEVER be a one-tap fire that approves directly. Only **Deny** (the safe direction) may fire
 * from the notification. This is enforced and unit-tested.
 */
object Notifications {

    const val CHANNEL_STREAM = "eve_stream"
    const val CHANNEL_APPROVALS = "eve_approvals"

    const val EXTRA_APPROVAL_ID = "approval_id"
    const val EXTRA_OPEN_CARD = "open_card"

    /** Action contract values — exposed for tests to assert the Review action is never a fire. */
    const val ACTION_REVIEW = "app.eve.action.REVIEW"
    const val ACTION_DENY = "app.eve.action.DENY"

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STREAM,
                context.getString(R.string.stream_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.stream_channel_desc) },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVALS,
                context.getString(R.string.approvals_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.approvals_channel_desc) },
        )
    }

    /**
     * The Review action's intent: opens MainActivity primed to the given approval id. This is a
     * CONTENT intent (ACTIVITY pending intent), never a broadcast that fires the approval.
     */
    fun reviewContentIntent(context: Context, approvalId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_REVIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CARD, approvalId)
        }
        return PendingIntent.getActivity(
            context,
            approvalId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The WearableExtender that tags an approval notification with [WearLink.BRIDGE_TAG_APPROVAL].
     * The watch excludes this tag from auto-bridging, so a phone approval notification never mirrors
     * to the wrist as a duplicate — the watch owns the wrist approval (hold-to-approve). Applied ONLY
     * to approval notifications; ritual/reminder/stream notifications keep default bridging so they
     * still auto-bridge to the watch.
     */
    private fun approvalBridgeExtender(): NotificationCompat.WearableExtender =
        NotificationCompat.WearableExtender().setBridgeTag(WearLink.BRIDGE_TAG_APPROVAL)

    /** The Deny action's intent: a broadcast to ApprovalActionReceiver (safe direction). */
    fun denyBroadcastIntent(context: Context, approvalId: String): PendingIntent {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = ACTION_DENY
            putExtra(EXTRA_APPROVAL_ID, approvalId)
        }
        return PendingIntent.getBroadcast(
            context,
            ("deny:$approvalId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildApprovalNotification(
        context: Context,
        approvalId: String,
        title: String,
        body: String,
    ): Notification {
        val review = reviewContentIntent(context, approvalId)
        val deny = denyBroadcastIntent(context, approvalId)
        return NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            // Tapping the body OR the Review action opens the primed card — no one-tap fire.
            .setContentIntent(review)
            .addAction(0, "Review", review)
            .addAction(0, "Deny", deny)
            // Keep the phone's approval notification OFF the wrist — the watch posts its own.
            .extend(approvalBridgeExtender())
            .build()
    }

    /**
     * Re-post the approval as a "deny failed" notification so a failed Deny (offline / server
     * unreachable) is never silently lost. The request is still pending server-side, so we keep
     * Review + a Retry-Deny action instead of cancelling.
     */
    fun notifyDenyFailed(context: Context, approvalId: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val review = reviewContentIntent(context, approvalId)
        val deny = denyBroadcastIntent(context, approvalId)
        val body =
            "$ASSISTANT_NAME couldn't reach the server to deny this request — it's still pending. " +
                "Retry Deny, or open to review."
        val n = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Couldn't deny — still pending")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setContentIntent(review)
            .addAction(0, "Review", review)
            .addAction(0, "Retry Deny", deny)
            // Same approval — keep it off the wrist; the watch owns the wrist approval surface.
            .extend(approvalBridgeExtender())
            .build()
        mgr.notify(approvalId.hashCode(), n)
    }

    fun cancel(context: Context, approvalId: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(approvalId.hashCode())
    }

    fun notificationId(approvalId: String): Int = approvalId.hashCode()
}
