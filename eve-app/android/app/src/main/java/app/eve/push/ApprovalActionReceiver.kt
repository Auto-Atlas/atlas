package app.eve.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.eve.EveApplication
import app.eve.data.DenyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the notification **Deny** action (the only direction safe to fire from a
 * notification). Approve is deliberately NOT handled here — approving requires opening the
 * primed card and holding (Notifications.reviewContentIntent), never a one-tap broadcast.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_DENY) return
        val id = intent.getStringExtra(Notifications.EXTRA_APPROVAL_ID) ?: return

        val app = context.applicationContext as? EveApplication ?: return
        val repo = app.container.approvalRepository

        // Deny is fire-and-forget over a goAsync window so the broadcast can complete the call.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Only dismiss the notification when the deny actually took (or was already
                // resolved). On failure the request is still pending — re-post with a retry
                // affordance instead of silently cancelling.
                when (repo.deny(id)) {
                    is DenyOutcome.Denied,
                    is DenyOutcome.AlreadyResolved -> Notifications.cancel(context, id)
                    is DenyOutcome.Failed -> Notifications.notifyDenyFailed(context, id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
