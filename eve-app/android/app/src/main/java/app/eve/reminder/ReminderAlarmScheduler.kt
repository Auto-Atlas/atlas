package app.eve.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Rings the phone like a real alarm clock when EVE sets a reminder.
 *
 * The server sends an FCM data push (`set_alarm` / `cancel_alarm`, see [app.eve.push.EveMessagingService])
 * that lands here even if the app was killed. Each reminder is persisted (so it survives a reboot)
 * and armed with [AlarmManager.setAlarmClock] — the Doze/battery-optimization-exempt path that needs
 * NO `SCHEDULE_EXACT_ALARM` permission, exactly like [app.eve.ritual.RitualScheduler]. When the alarm
 * fires, [ReminderAlarmReceiver] posts a high-priority ALARM notification and drops the record.
 *
 * Persistence lives in a plain synchronous [android.content.SharedPreferences] (not the coroutine
 * DataStore the rest of the app uses) precisely so the BroadcastReceiver / boot path can read it
 * without a coroutine scope — mirroring RitualScheduler's design.
 *
 * Per reminder we store two keys: `due:<id>` (epoch SECONDS as Long) and `what:<id>` (text). Any
 * key prefixed `due:` is the authoritative index of pending reminders.
 */
object ReminderAlarmScheduler {
    private const val TAG = "ReminderAlarm"

    private const val PREFS = "eve_reminders"
    private const val KEY_DUE_PREFIX = "due:"
    private const val KEY_WHAT_PREFIX = "what:"

    /** A pending reminder alarm. [dueEpochSec] is unix time in SECONDS (the push wire format). */
    data class Reminder(val id: String, val dueEpochSec: Long, val what: String) {
        val dueMs: Long get() = dueEpochSec * 1000L
    }

    /**
     * PURE: validate + parse a `set_alarm` payload into a [Reminder], or null if any field is
     * missing/blank/unparseable. Does NOT judge past-due (callers decide that against a clock) so it
     * stays a clean structural parse — fully JVM-unit-testable with no Android.
     */
    fun parseReminder(id: String?, dueEpoch: String?, what: String?): Reminder? {
        if (id.isNullOrBlank()) return null
        if (what.isNullOrBlank()) return null
        val due = dueEpoch?.trim()?.toLongOrNull() ?: return null
        if (due <= 0L) return null
        return Reminder(id.trim(), due, what)
    }

    /** PURE: true when the reminder is still in the future relative to [nowMs] (millis). */
    fun isFuture(reminder: Reminder, nowMs: Long): Boolean = reminder.dueMs > nowMs

    // ---- Push entry points -------------------------------------------------------------------

    /** Handle a `set_alarm` push: parse, ignore past-due/garbage gracefully, else persist + arm. */
    fun handleSet(context: Context, id: String?, dueEpoch: String?, what: String?) {
        val reminder = parseReminder(id, dueEpoch, what)
        if (reminder == null) {
            Log.w(TAG, "set_alarm ignored — missing/invalid fields (id=$id due=$dueEpoch)")
            return
        }
        if (!isFuture(reminder, System.currentTimeMillis())) {
            Log.w(TAG, "set_alarm ignored — already past due (id=${reminder.id})")
            return
        }
        persist(context, reminder)
        arm(context, reminder)
        // Best-effort: also drop it into the user's stock Clock app (silent, foreground-only). The
        // in-app alarm above is the reliable path; the mirror never affects it.
        ClockMirror.mirror(context, reminder)
        Log.d(TAG, "set_alarm armed id=${reminder.id} at ${reminder.dueEpochSec}s")
    }

    /** Handle a `cancel_alarm` push: cancel the PendingIntent, drop the record, clear any notification. */
    fun handleCancel(context: Context, id: String?) {
        if (id.isNullOrBlank()) {
            Log.w(TAG, "cancel_alarm ignored — no id")
            return
        }
        cancelAlarm(context, id.trim())
        remove(context, id.trim())
        ReminderNotification.cancel(context, id.trim())
        Log.d(TAG, "cancel_alarm done id=${id.trim()}")
    }

    /** Called by [ReminderAlarmReceiver] once the alarm has fired: the record is one-shot, drop it. */
    fun onFired(context: Context, id: String) {
        remove(context, id)
    }

    /**
     * Boot re-arm: alarms don't survive a reboot. Re-arm every still-future reminder and DROP the
     * ones whose time already passed while the phone was off (a stale past-due alarm would fire
     * instantly and confusingly). Wrapped per-reminder so one bad record can't sink the rest.
     */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (reminder in all(context)) {
            try {
                if (isFuture(reminder, now)) {
                    arm(context, reminder)
                } else {
                    remove(context, reminder.id)
                    Log.d(TAG, "boot: dropped past-due reminder id=${reminder.id}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "boot: failed to re-arm reminder id=${reminder.id}: ${t.message}")
            }
        }
    }

    // ---- Persistence -------------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun persist(context: Context, reminder: Reminder) {
        prefs(context).edit()
            .putLong(KEY_DUE_PREFIX + reminder.id, reminder.dueEpochSec)
            .putString(KEY_WHAT_PREFIX + reminder.id, reminder.what)
            .apply()
    }

    fun get(context: Context, id: String): Reminder? {
        val p = prefs(context)
        if (!p.contains(KEY_DUE_PREFIX + id)) return null
        val due = p.getLong(KEY_DUE_PREFIX + id, 0L)
        val what = p.getString(KEY_WHAT_PREFIX + id, null) ?: return null
        return Reminder(id, due, what)
    }

    /** Every persisted reminder (the `due:` keys are the index). */
    fun all(context: Context): List<Reminder> {
        val p = prefs(context)
        return p.all.keys
            .filter { it.startsWith(KEY_DUE_PREFIX) }
            .mapNotNull { key -> get(context, key.removePrefix(KEY_DUE_PREFIX)) }
    }

    fun remove(context: Context, id: String) {
        prefs(context).edit()
            .remove(KEY_DUE_PREFIX + id)
            .remove(KEY_WHAT_PREFIX + id)
            .apply()
    }

    // ---- Alarm wiring ------------------------------------------------------------------------

    private fun arm(context: Context, reminder: Reminder) {
        val am = alarmManager(context)
        val op = operationIntent(context, reminder.id)
        // Tapping the system alarm affordance opens EVE.
        val show = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            Intent(context, app.eve.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Prefer an exact alarm-clock alarm. Some OEM builds still enforce the exact-alarm permission
        // even for setAlarmClock, so guard with canScheduleExactAlarms and ALWAYS catch
        // SecurityException — a scheduling failure must never crash EVE. Falls back to an inexact,
        // Doze-tolerant alarm that still fires (just not to the exact minute). Mirrors RitualScheduler.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.dueMs, show), op)
                return
            }
        } catch (_: SecurityException) {
            // fall through to the inexact path
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.dueMs, op)
    }

    private fun cancelAlarm(context: Context, id: String) {
        alarmManager(context).cancel(operationIntent(context, id))
    }

    /**
     * The broadcast that fires the alarm. Request code is derived from the id so each reminder has
     * its own PendingIntent (and cancel targets exactly one). The id rides as an extra so the
     * receiver knows which reminder fired even if the persisted record were somehow gone.
     */
    private fun operationIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ReminderAlarmReceiver.ACTION_FIRE)
                .putExtra(ReminderAlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
