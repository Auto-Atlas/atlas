package app.eve.wear.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.eve.wear.WearApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Health Services passive registrations DO NOT survive a reboot — this receiver re-registers the
 * HR stream after BOOT_COMPLETED, but only when the owner had alerts ON (HrAlertsStore) and the
 * permission is still granted. goAsync so the suspend registration outlives onReceive.
 */
class HrBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as WearApplication).container
        if (!container.hrAlertsStore.enabled) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val outcome = container.hrPassiveMonitor.ensureRegistered()
                Log.i(TAG, "post-boot HR re-registration: $outcome")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "HrBootReceiver"
    }
}
