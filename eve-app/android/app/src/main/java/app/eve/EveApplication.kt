package app.eve

import android.app.Application
import app.eve.di.AppContainer
import app.eve.push.Notifications
import app.eve.push.PushTokenRegistrar
import app.eve.reminder.ForegroundActivityTracker
import app.eve.reminder.ReminderNotification
import app.eve.ritual.RitualNotification
import app.eve.ritual.RitualScheduler
import app.eve.ritual.WakeAudioCache
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App entry point; constructs the manual DI container and notification channels. */
class EveApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        RitualNotification.ensureChannel(this)
        ReminderNotification.ensureChannel(this)
        // Track the foreground activity so the reminder->stock-Clock mirror only starts an activity
        // when the app is genuinely visible (Android 14+ blocks background activity starts).
        ForegroundActivityTracker.register(this)
        registerPushToken()
        // Pre-fetch the 5 AM wake audio so the WAV is already cached on the phone before the wake
        // fires (best-effort, conditional-GET so it only re-downloads when the whys change).
        appScope.launch { WakeAudioCache.refresh(this@EveApplication) }
        // Arm the morning ritual: defaults to 5 AM on first run, then re-arms whatever is configured
        // on every launch (alarm-clock alarms are one-shot + don't survive app updates). Wrapped so
        // a scheduling/permission failure can NEVER crash app startup (this onCreate also runs when
        // the alarm receiver wakes the process).
        try {
            RitualScheduler.ensureDefault(this)
        } catch (t: Throwable) {
            android.util.Log.w("EveApplication", "ritual scheduling skipped: ${t.message}")
        }
        maybeScheduleHealthSync()
    }

    /**
     * Health v1: keep the periodic 30-min upload scheduled — but ONLY when Health Connect is available
     * AND all read permissions are already granted. No nagging: if the user hasn't opted in, nothing is
     * scheduled and no permission prompt is raised here (that happens deliberately from the Status
     * "Health" row). Entirely guarded so a Health Connect/WorkManager hiccup can't crash startup.
     */
    private fun maybeScheduleHealthSync() {
        appScope.launch {
            try {
                val controller = container.healthController
                if (controller.availability() == app.eve.health.HealthAvailability.AVAILABLE &&
                    controller.hasPermissions()
                ) {
                    controller.ensurePeriodicScheduled()
                }
            } catch (t: Throwable) {
                android.util.Log.w("EveApplication", "health scheduling skipped: ${t.message}")
            }
        }
    }

    /**
     * Best-effort: fetch the current FCM token and (re-)register it with the server so the server
     * can push the morning ritual to this device. Entirely guarded — when Firebase is NOT configured
     * (no google-services.json) FirebaseMessaging.getInstance() throws IllegalStateException; we
     * swallow it so the app starts cleanly. Once the json is present this acquires a real token and
     * registers it on every launch (covering tokens that rotated while the app was killed).
     */
    private fun registerPushToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = task.result
                if (task.isSuccessful && !token.isNullOrBlank()) {
                    appScope.launch { PushTokenRegistrar.register(this@EveApplication, token) }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.d("EveApplication", "FCM not configured; push-wake inert: ${t.message}")
        }
    }
}
