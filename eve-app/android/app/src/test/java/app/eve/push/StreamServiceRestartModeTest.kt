package app.eve.push

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the crash-safety decision behind the Android 14+ foreground-service fix: when the service
 * could NOT be promoted to the foreground (e.g. ForegroundServiceStartNotAllowedException because it
 * was started from the background, or POST_NOTIFICATIONS denied so the FGS notification can't post),
 * the service must NOT ask the OS to restart it (START_NOT_STICKY) — a sticky restart would fire from
 * the background and re-throw, which is precisely the production crash we fixed. Only when promotion
 * succeeded do we keep the connection sticky.
 */
class StreamServiceRestartModeTest {

    @Test
    fun promoted_isSticky() {
        assertEquals(Service.START_STICKY, StreamService.restartMode(promoted = true))
    }

    @Test
    fun notPromoted_isNotSticky() {
        assertEquals(Service.START_NOT_STICKY, StreamService.restartMode(promoted = false))
    }
}
