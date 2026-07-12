package app.eve.wear.ui

import android.content.Context
import android.provider.Settings

/**
 * The OS "Remove animations" accessibility setting drives ANIMATOR_DURATION_SCALE to 0. Read the
 * same way as :app ApprovalsScreen.isReducedMotion() (kept in sync). Returns true when the user has
 * animations off — the hold-to-approve visual snaps, but the 520ms SAFETY GATE is never shortened.
 */
fun isReducedMotion(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
    return scale == 0f
}
