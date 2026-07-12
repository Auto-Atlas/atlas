package app.eve.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import app.eve.MainActivity
import app.eve.R

/**
 * The reminder-alarm notification. Its own channel (separate from the morning ritual) carries the
 * ALARM audio attributes + the default alarm ringtone + vibration, and it posts a FULL-SCREEN intent
 * (category=alarm) so it wakes a locked phone and rings like an alarm clock. Tapping it opens EVE.
 *
 * Graceful degrade: on Android 14+ the OS may withhold the full-screen-intent privilege; the system
 * then shows this as a high-priority heads-up notification the user taps — degraded, but it still
 * reaches them. Notification id + PendingIntent request code are derived from the reminder id so
 * several reminders can ring independently and [cancel] targets exactly one.
 */
object ReminderNotification {
    const val CHANNEL_REMINDER = "eve_reminder"
    const val EXTRA_REMINDER = "eve_reminder_id"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_REMINDER,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_desc)
            setSound(alarmSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 500, 500, 500, 500)
        }
        mgr.createNotificationChannel(channel)
    }

    /** Ring the alarm for [id] displaying [what]. */
    fun fire(context: Context, id: String, what: String) {
        ensureChannel(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REMINDER, id)
        }
        val fullScreen = PendingIntent.getActivity(
            context,
            id.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val notif = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.reminder_notif_title))
            .setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // NotificationCompat.Builder.setSound(Uri, Int) expects an AudioManager.STREAM_* type,
            // not an AudioAttributes.USAGE_* constant. STREAM_ALARM is the correct alarm stream for
            // the pre-O sound path; on O+ the channel's ALARM audioAttrs (ensureChannel) govern.
            .setSound(alarmSound, AudioManager.STREAM_ALARM)
            .setVibrate(longArrayOf(0, 500, 500, 500, 500, 500))
            .setAutoCancel(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId(id), notif)
    }

    fun cancel(context: Context, id: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(notifId(id))
    }

    /** Stable per-reminder notification id (positive, kept clear of the ritual's 0x5A4D01 id). */
    private fun notifId(id: String): Int = 0x5A4E_0000 or (id.hashCode() and 0xFFFF)
}
