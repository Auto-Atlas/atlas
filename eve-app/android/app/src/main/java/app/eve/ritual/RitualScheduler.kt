package app.eve.ritual

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules Atlas's morning ritual as a real alarm-clock alarm.
 *
 * Uses [AlarmManager.setAlarmClock], which is exempt from Doze/battery optimization and — unlike
 * `setExactAndAllowWhileIdle` — does NOT require the `SCHEDULE_EXACT_ALARM` permission, so it works
 * on a clean install. AlarmClock alarms are one-shot, so [RitualAlarmReceiver] re-arms the next
 * day's after each fire, and [BootReceiver] re-arms after a reboot (alarms don't survive one).
 *
 * Ritual settings live in their own synchronous [android.content.SharedPreferences] (not the
 * coroutine-based DataStore the rest of the app uses) precisely so the BroadcastReceiver / boot
 * path can read them without a coroutine scope.
 */
object RitualScheduler {
    const val DEFAULT_HOUR = 5
    const val DEFAULT_MINUTE = 0

    private const val PREFS = "eve_ritual"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_CONFIGURED = "configured"

    // Distinct request codes so the operation (broadcast) and the show-intent (activity) PendingIntents
    // never collide.
    private const val RC_OPERATION = 0x5A4D
    private const val RC_SHOW = 0x5A4E

    data class RitualConfig(val enabled: Boolean, val hour: Int, val minute: Int)

    /**
     * PURE: the next epoch-millis at [hour]:[minute] strictly after [nowMs], computed in [calendar]'s
     * zone. If today's time has already passed, rolls to tomorrow. Extracted (no real clock, caller
     * supplies the Calendar) so it is fully JVM-unit-testable.
     */
    fun nextTriggerMs(
        nowMs: Long,
        hour: Int,
        minute: Int,
        calendar: Calendar = Calendar.getInstance(),
    ): Long {
        calendar.timeInMillis = nowMs
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= nowMs) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    fun config(context: Context): RitualConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RitualConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            hour = p.getInt(KEY_HOUR, DEFAULT_HOUR),
            minute = p.getInt(KEY_MINUTE, DEFAULT_MINUTE),
        )
    }

    /** Enable + persist the time + arm the alarm. */
    fun schedule(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_HOUR, hour)
            .putInt(KEY_MINUTE, minute)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
        arm(context, hour, minute)
    }

    /** Disable + cancel the pending alarm. The disabled state persists (a later reschedule no-ops). */
    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
        alarmManager(context).cancel(operationIntent(context))
    }

    /** Re-arm from persisted settings (after a fire, a reboot, or app start). No-op when disabled. */
    fun reschedule(context: Context) {
        val c = config(context)
        if (c.enabled) arm(context, c.hour, c.minute)
    }

    /**
     * First-run default: turn the 5 AM ritual on for the owner's dogfood device. Only sets it once
     * (guarded by KEY_CONFIGURED) so a later in-app "cancel" sticks across launches. On subsequent
     * launches it just re-arms whatever is configured.
     */
    fun ensureDefault(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_CONFIGURED, false)) {
            schedule(context, DEFAULT_HOUR, DEFAULT_MINUTE)
        } else {
            reschedule(context)
        }
    }

    private fun arm(context: Context, hour: Int, minute: Int) {
        val triggerAt = nextTriggerMs(System.currentTimeMillis(), hour, minute)
        val am = alarmManager(context)
        val op = operationIntent(context)
        // The show-intent is what the system surfaces (status-bar alarm icon / lock screen); tapping
        // it opens Atlas. The operation intent is the actual broadcast that fires the ritual.
        val show = PendingIntent.getActivity(
            context,
            RC_SHOW,
            Intent(context, app.eve.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Prefer an exact alarm-clock alarm (to the minute). Some OEM builds still enforce the
        // exact-alarm permission even for setAlarmClock, so guard with canScheduleExactAlarms and
        // ALWAYS catch SecurityException — a scheduling failure must never crash Atlas. Falls back to
        // an inexact, Doze-tolerant alarm that still fires (just not to the exact minute).
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), op)
                return
            }
        } catch (_: SecurityException) {
            // fall through to the inexact path
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
    }

    private fun operationIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RC_OPERATION,
            Intent(context, RitualAlarmReceiver::class.java).setAction(RitualAlarmReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
