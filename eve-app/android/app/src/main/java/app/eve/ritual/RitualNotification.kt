package app.eve.ritual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.eve.MainActivity
import app.eve.R

/**
 * The morning-ritual alarm notification. Uses a FULL-SCREEN intent (category=alarm) so it wakes a
 * locked phone and launches EVE straight into the ritual, exactly like an alarm clock.
 *
 * Graceful degrade: on Android 14+ the OS may withhold the full-screen-intent privilege from a
 * non-calling app (USE_FULL_SCREEN_INTENT is no longer auto-granted). In that case the system shows
 * this as a high-priority heads-up notification the user taps to open EVE — degraded, but the
 * ritual still reaches them. Either way the launch carries [EXTRA_RITUAL] so MainActivity knows to
 * auto-connect.
 */
object RitualNotification {
    const val CHANNEL_RITUAL = "eve_ritual"
    const val EXTRA_RITUAL = "eve_ritual"
    const val RITUAL_MORNING = "morning"

    private const val NOTIF_ID = 0x5A4D01
    private const val RC_FULLSCREEN = 0x5A4D02

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RITUAL,
                context.getString(R.string.ritual_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.ritual_channel_desc) },
        )
    }

    fun fireMorningRitual(context: Context) {
        ensureChannel(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_RITUAL, RITUAL_MORNING)
        }
        val fullScreen = PendingIntent.getActivity(
            context,
            RC_FULLSCREEN,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_RITUAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.ritual_notif_title))
            .setContentText(context.getString(R.string.ritual_notif_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
    }

    /**
     * The notification that fronts [app.eve.ritual.RitualPlaybackService] while EVE's wake audio
     * plays locally. Reuses the IMPORTANCE_HIGH ritual channel and carries a full-screen + content
     * intent (category=alarm) so a tap opens EVE to talk — but the LOCAL audio is the primary,
     * connection-free wake; this notification is the visual/affordance around it.
     */
    fun playbackNotification(context: Context): Notification {
        ensureChannel(context)
        // Opens EVE NORMALLY on tap (no EXTRA_RITUAL) — the whys play locally, so we must not
        // auto-connect the voice screen. No full-screen intent either: the playback service's wake
        // lock turns the screen on; a full-screen auto-launch would re-open the "connected, silent"
        // Talk screen.
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(
            context,
            RC_FULLSCREEN,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_RITUAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.ritual_notif_title))
            .setContentText(context.getString(R.string.ritual_notif_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }
}
