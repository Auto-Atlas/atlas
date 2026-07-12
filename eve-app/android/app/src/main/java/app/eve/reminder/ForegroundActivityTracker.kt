package app.eve.reminder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the currently-resumed Activity so best-effort features (the stock-Clock mirror) can start
 * an activity ONLY when the app is genuinely in the foreground — Android 14+ background activity
 * starts are blocked, and we must never attempt a notification trampoline. Registered once from
 * [app.eve.EveApplication]. Held weakly so it can never leak an Activity.
 */
object ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null

    /** The resumed (foreground) Activity, or null when the app has none — do not start activities then. */
    fun current(): Activity? = resumed?.get()

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
