package app.eve.onboarding

import android.content.Context

/**
 * The first-run gate, persisted in a tiny SharedPreferences file (kept deliberately SEPARATE from
 * the DataStore connection settings). The owner has been set up once `onboarding_complete` is true;
 * until then MainActivity routes to the wizard. We gate on this LOCAL flag — never on server state —
 * so the decision is instant, offline-safe, and simple. Re-running setup from Settings just clears
 * the flag.
 */
class OnboardingState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)
        set(value) { prefs.edit().putBoolean(KEY_COMPLETE, value).apply() }

    fun markComplete() { isComplete = true }

    /** Re-run setup: forget the flag so the wizard shows again. */
    fun reset() { isComplete = false }

    private companion object {
        const val PREFS = "eve_onboarding"
        const val KEY_COMPLETE = "onboarding_complete"
    }
}
