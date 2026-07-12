package app.eve.wear

import android.app.Application
import android.util.Log
import androidx.wear.phone.interactions.notifications.BridgingConfig
import androidx.wear.phone.interactions.notifications.BridgingManager
import app.eve.data.wear.WearLink
import app.eve.wear.di.WearContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The watch app entry point. Builds the manual-DI [WearContainer], ensures the approvals channel,
 * and configures notification bridging so the wrist shows exactly ONE copy of an approval.
 *
 * Bridging: EVERYTHING still auto-bridges from the phone (ritual, reminders, stream) EXCEPT the
 * phone's approval notification (tagged [WearLink.BRIDGE_TAG_APPROVAL]) — the watch owns that one
 * natively via [app.eve.wear.notify.ApprovalNotifier] (where hold-to-approve lives). This config is
 * PERSISTENT: [BridgingManager] stores it on the device, so it survives process death and takes
 * effect for future bridged notifications until changed — we still (re)assert it on every launch so
 * a reinstall/clear-data can never leave the exclusion off.
 */
class WearApplication : Application() {

    lateinit var container: WearContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = WearContainer(this)
        container.approvalNotifier.ensureChannel(this)
        configureBridging()
        reassertHeartAlerts()
    }

    /**
     * Health v2: passive registrations can be lost (reboot, Health Services reset), so every app
     * start re-asserts the owner's choice — registered when alerts are ON and the permission still
     * stands, and every refusal leg is logged with its name (never a silent no-op).
     */
    private fun reassertHeartAlerts() {
        if (!container.hrAlertsStore.enabled) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val outcome = container.hrPassiveMonitor.ensureRegistered()
            Log.i(TAG, "app-start HR re-registration: $outcome")
        }
    }

    private fun configureBridging() {
        // BridgingManager can throw when there is no Wear host (off-watch, or in unit tests) — this
        // must never crash startup, so it is loud-but-non-fatal.
        try {
            BridgingManager.fromContext(this).setConfig(
                BridgingConfig.Builder(this, /* isBridgingEnabled = */ true)
                    .addExcludedTag(WearLink.BRIDGE_TAG_APPROVAL)
                    .build(),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Bridging config failed (no Wear host / test env?): ${t.message}", t)
        }
    }

    private companion object {
        const val TAG = "WearApplication"
    }
}
