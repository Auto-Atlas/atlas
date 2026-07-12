package app.eve.wear.health

import android.content.Context

/**
 * The owner's heart-alert settings on this watch. SharedPrefs (survives restarts, cleared with app
 * data) — [app.eve.wear.WearApplication] and [HrBootReceiver] consult [enabled] before
 * re-registering the passive stream, so alerts never resurrect after the owner turned them off.
 *
 * [highBpm] is the alert threshold — a PERSONAL health knob (house rule: nothing owner-specific
 * baked in), so it lives here as data, not in code. 120 is only the product default; a settings
 * surface / phone-written config can change it without a rebuild (the relay reads it at app start).
 */
class HrAlertsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    var highBpm: Int
        get() = prefs.getInt(KEY_HIGH_BPM, HrAlertPolicy.DEFAULT_HIGH_BPM)
        set(value) { prefs.edit().putInt(KEY_HIGH_BPM, value).apply() }

    private companion object {
        const val PREFS = "hr_alerts"
        const val KEY_ENABLED = "enabled"
        const val KEY_HIGH_BPM = "high_bpm"
    }
}
