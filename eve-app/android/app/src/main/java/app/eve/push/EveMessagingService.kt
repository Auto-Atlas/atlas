package app.eve.push

import android.util.Log
import app.eve.reminder.ReminderAlarmScheduler
import app.eve.ritual.RitualNotification
import app.eve.ritual.RitualPlaybackService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM entry point. Lets the SERVER wake this phone for the 5 AM morning ritual even when the app
 * was killed: a high-priority data message `{"type":"morning_ritual"}` fires the EXACT same path the
 * alarm uses — [RitualNotification.fireMorningRitual] (full-screen wake -> MainActivity with the
 * ritual extra -> auto-connect to Talk -> ritual plays).
 *
 * This class is inert until Firebase is configured (google-services.json present): without it FCM
 * never delivers a token or a message, so onNewToken/onMessageReceived simply never fire.
 */
class EveMessagingService : FirebaseMessagingService() {

    // Token registration is a one-shot network call that can outlive the brief onNewToken callback,
    // so run it on a service-scoped supervisor (not GlobalScope) — any failure is isolated.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * A new/rotated FCM token. Persist it and register it with the server so the server can target
     * this device for the morning-ritual push. Best-effort — swallows failures (app may be
     * unconfigured or offline; app-start re-registers the cached token later).
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed")
        scope.launch {
            PushTokenRegistrar.register(applicationContext, token)
        }
    }

    /**
     * A push arrived. We only act on the morning-ritual data message; every other type is ignored
     * gracefully (the server may add more push types over time).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            TYPE_MORNING_RITUAL -> {
                Log.d(TAG, "morning_ritual push -> local audio wake")
                // PRIMARY, connection-free path: start the foreground service that PLAYS the cached
                // wake WAV (EVE's real voice) LOCALLY on the alarm stream — works from Doze, no
                // WebRTC, no mic, no echo. The FCM `text` is passed as a last-resort TTS fallback.
                RitualPlaybackService.start(applicationContext, message.data["text"])
                // Do NOT also fire the auto-connecting Talk screen: the whys play LOCALLY now, so an
                // auto-connected voice screen would just show "connected" with nothing to deliver
                // (confusing). The playback service turns the screen on (wake lock) + shows its own
                // notification; tapping it opens EVE normally so you can choose to talk.
            }
            TYPE_SET_ALARM -> {
                Log.d(TAG, "set_alarm push -> arm reminder alarm")
                // Rings the phone like an alarm clock at the reminder's time, even if the app is
                // killed. Bad/missing fields and past-due times are ignored gracefully inside.
                ReminderAlarmScheduler.handleSet(
                    applicationContext,
                    message.data["id"],
                    message.data["due_epoch"],
                    message.data["what"],
                )
            }
            TYPE_CANCEL_ALARM -> {
                Log.d(TAG, "cancel_alarm push -> cancel reminder alarm")
                ReminderAlarmScheduler.handleCancel(applicationContext, message.data["id"])
            }
            else -> {
                // Unknown/unsupported push type — ignore quietly.
            }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EveMessagingService"
        const val TYPE_MORNING_RITUAL = "morning_ritual"
        const val TYPE_SET_ALARM = "set_alarm"
        const val TYPE_CANCEL_ALARM = "cancel_alarm"
    }
}
