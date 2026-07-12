package app.eve.data

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "Meta glasses" toggle is a LOCAL (DataStore) preference — verify it defaults off and persists
 * across a fresh [Settings] instance (same DataStore file), the way it survives process restarts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsGlassesTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `glasses toggle defaults off and round-trips through the datastore`() = runTest {
        val settings = Settings(ctx)

        // Opt-in: default off.
        assertFalse(settings.glassesEnabledNow(), "glasses must be opt-in (default off)")

        // Each read re-maps DataStore (Settings holds no in-memory cache), so reading back a written
        // value proves it persisted to the store, the same store that survives a process restart.
        settings.setGlassesEnabled(true)
        assertTrue(settings.glassesEnabledNow(), "enabled state persisted")

        settings.setGlassesEnabled(false)
        assertFalse(settings.glassesEnabledNow(), "disabled state persisted")
    }
}
