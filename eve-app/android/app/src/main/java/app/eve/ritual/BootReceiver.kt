package app.eve.ritual

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.eve.reminder.ReminderAlarmScheduler

/**
 * Alarms don't survive a reboot — re-arm the morning ritual AND every still-future reminder alarm
 * once the device finishes booting (past-due reminders are dropped inside [ReminderAlarmScheduler]).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            RitualScheduler.reschedule(context)
            ReminderAlarmScheduler.rescheduleAll(context)
        }
    }
}
