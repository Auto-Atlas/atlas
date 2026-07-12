package app.eve.ritual

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by AlarmManager at the configured ritual time. Re-arms the next day's alarm (alarm-clock
 * alarms are one-shot), then wakes the phone with a full-screen-intent notification that launches
 * EVE straight into the morning ritual — the alarm-clock pattern.
 */
class RitualAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Re-arm tomorrow FIRST, before anything that could throw, so a hiccup never silently kills
        // the recurring alarm.
        RitualScheduler.reschedule(context)
        RitualNotification.fireMorningRitual(context)
    }

    companion object {
        const val ACTION_FIRE = "app.eve.action.RITUAL_FIRE"
    }
}
