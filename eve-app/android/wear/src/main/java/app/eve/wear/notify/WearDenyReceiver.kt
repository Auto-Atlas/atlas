package app.eve.wear.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.eve.wear.WearApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the wrist approval notification's **Deny** action — the ONLY direction safe to fire from a
 * notification (one tap). There is deliberately NO approve here; approving requires opening the
 * detail screen and holding.
 *
 * Flow: immediately swap the notification to "Denying…" (Deny action removed, so a second tap can't
 * race), then over a goAsync window run [DenyFlow] (send + await the correlated result, 8s bound
 * inside the ~10s goAsync limit) and post the honest terminal [DenyUpdate]. Every leg is named — a
 * dead Data Layer, no reply, or an Atlas-side failure are distinct wrist messages, never a fake
 * "denied".
 */
class WearDenyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApprovalNotifier.ACTION_DENY) return
        val approvalId = intent.getStringExtra(ApprovalNotifier.EXTRA_APPROVAL_ID) ?: return
        val title = intent.getStringExtra(ApprovalNotifier.EXTRA_TITLE) ?: "Approval"

        val app = context.applicationContext as? WearApplication
        if (app == null) {
            Log.e(TAG, "no WearApplication container — cannot deny $approvalId")
            return
        }
        val container = app.container
        val notifier = container.approvalNotifier

        // Kill the double-tap window immediately: show "Denying…" with the Deny action gone.
        notifier.postDenying(context, approvalId, title)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val update = try {
                container.denyFlow.deny(approvalId)
            } catch (t: Throwable) {
                Log.e(TAG, "deny flow for $approvalId failed: ${t.message}", t)
                DenyUpdate.DataLayerDown
            }
            try {
                notifier.postDenyResult(context, approvalId, title, update)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WearDenyReceiver"
    }
}
