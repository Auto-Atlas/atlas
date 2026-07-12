package app.eve.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by AlarmManager when a reminder's time arrives. Rings the phone (a high-priority ALARM
 * notification with the reminder text), then drops the one-shot record so it can't fire again or be
 * re-armed on a later boot. Reads the text from the persisted store (authoritative), falling back to
 * the id-tagged intent extra.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            Log.w(TAG, "reminder alarm fired with no id — ignoring")
            return
        }
        val reminder = ReminderAlarmScheduler.get(context, id)
        val what = reminder?.what
            ?: context.getString(app.eve.R.string.reminder_notif_default_body)
        // Drop the record FIRST (one-shot), before anything that could throw, so a hiccup in the
        // notification can never leave a zombie that re-fires on the next boot.
        ReminderAlarmScheduler.onFired(context, id)
        ReminderNotification.fire(context, id, what)
    }

    companion object {
        private const val TAG = "ReminderAlarm"
        const val ACTION_FIRE = "app.eve.action.REMINDER_FIRE"
        const val EXTRA_ID = "app.eve.extra.REMINDER_ID"
    }
}
