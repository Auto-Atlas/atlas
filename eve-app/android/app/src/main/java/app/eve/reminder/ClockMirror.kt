package app.eve.reminder

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar

/**
 * Best-effort mirror of a reminder into the user's STOCK Clock app, so it also shows up alongside
 * their normal alarms. Fires [AlarmClock.ACTION_SET_ALARM] with EXTRA_SKIP_UI so the clock app adds
 * it silently — but ONLY from a genuine foreground Activity ([ForegroundActivityTracker]), because
 * Android 14+ blocks background activity starts. If there is no foreground activity, no clock app
 * handles the intent, or the start throws, we skip SILENTLY: the in-app [ReminderAlarmScheduler]
 * alarm is the reliable path and always fires regardless. We NEVER attempt a notification trampoline.
 */
object ClockMirror {
    private const val TAG = "ReminderClockMirror"

    fun mirror(context: Context, reminder: ReminderAlarmScheduler.Reminder) {
        val activity = ForegroundActivityTracker.current() ?: return
        try {
            val cal = Calendar.getInstance().apply { timeInMillis = reminder.dueMs }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_MESSAGE, reminder.what)
                putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
            }
            // Only start it if some clock app can handle it (else ActivityNotFoundException).
            if (intent.resolveActivity(context.packageManager) != null) {
                activity.startActivity(intent)
                Log.d(TAG, "mirrored reminder ${reminder.id} into stock clock")
            }
        } catch (t: Throwable) {
            // Background-start restriction, missing clock app, anything — the in-app alarm still rings.
            Log.d(TAG, "clock mirror skipped: ${t.message}")
        }
    }
}
