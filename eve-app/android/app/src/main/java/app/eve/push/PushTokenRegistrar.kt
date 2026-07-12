package app.eve.push

import android.content.Context
import android.util.Log
import app.eve.EveApplication
import app.eve.data.ApiResult
import app.eve.ritual.RitualScheduler
import app.eve.ritual.WakeAudioCache
import java.util.TimeZone

/**
 * Shared plumbing for FCM push-token registration, used by both [EveMessagingService.onNewToken]
 * (token rotated) and [EveApplication] (app start re-registers the cached token).
 *
 * It persists the latest token locally so app-start can re-register even if the original onNewToken
 * registration failed (server was offline, app not yet connected), and it sends the device's REAL
 * timezone + the user's CONFIGURED ritual wake time — nothing user-specific is hardcoded.
 *
 * Every call is best-effort: FCM may be inert (no google-services.json -> no token), the app may be
 * unconfigured (no base URL/token yet), or the server may be unreachable. None of these may crash or
 * surface — they just mean "register later".
 */
object PushTokenRegistrar {
    private const val PREFS = "eve_push"
    private const val KEY_TOKEN = "fcm_token"
    private const val TAG = "PushTokenRegistrar"

    /** Persist the latest FCM token so app-start can re-register it. */
    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    /** The last token we saw from FCM, or null if we've never received one. */
    fun savedToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    /**
     * Persist [token] and try to register it with the server. Reads the configured ritual wake time
     * from [RitualScheduler] and the device's default timezone. Returns the [ApiResult] for callers
     * that care; both call sites currently swallow it (best-effort). Never throws.
     */
    suspend fun register(context: Context, token: String): ApiResult<*>? {
        if (token.isBlank()) return null
        saveToken(context, token)
        return try {
            val app = context.applicationContext as? EveApplication ?: return null
            val ritual = RitualScheduler.config(context)
            val result = app.container.apiClient.registerPushToken(
                token = token,
                wakeHour = ritual.hour,
                wakeMinute = ritual.minute,
                tz = TimeZone.getDefault().id,
            )
            // Right after a successful registration the connection is known-good, so pre-fetch the
            // wake WAV onto the phone (best-effort, conditional-GET) — it'll be ready before 5 AM.
            if (result is ApiResult.Ok) {
                WakeAudioCache.refresh(context)
            }
            result
        } catch (t: Throwable) {
            // Unconfigured app / offline server / inert FCM all land here; register later.
            Log.d(TAG, "push token registration deferred: ${t.message}")
            null
        }
    }
}
