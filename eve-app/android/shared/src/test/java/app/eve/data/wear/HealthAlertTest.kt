package app.eve.data.wear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wire contract for the watch->phone heart-rate alert (Health v2: "if my heart rate jumps she can
 * warn me"). Round-trips over the same EveWireJson bytes both sides use; garbage bytes fail LOUDLY
 * (a health alert must never decode into a fake event).
 */
class HealthAlertTest {

    @Test
    fun health_alert_roundtrips() {
        val original = HealthAlert(
            requestId = "hr-1",
            type = "hr_high",
            bpm = 142,
            thresholdBpm = 120,
            observedAtEpochMs = 1_780_000_000_000,
        )
        assertEquals(original, HealthAlert.fromBytes(original.toBytes()))
    }

    @Test
    fun garbage_bytes_fail_loudly() {
        assertFailsWith<Exception> { HealthAlert.fromBytes("{nope".toByteArray()) }
    }

    @Test
    fun path_lives_under_the_action_namespace() {
        // WearBridgeService drops any path outside /eve/action/ in code — the alert must ride inside.
        assertTrue(WearLink.PATH_ACTION_HEALTH_EVENT.startsWith("/eve/action/"))
    }
}
