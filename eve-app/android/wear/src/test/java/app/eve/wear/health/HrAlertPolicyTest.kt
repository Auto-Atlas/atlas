package app.eve.wear.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure brain of the watch HR alert (Health v2). Health Services has NO bpm threshold goals
 * (passive goals are daily-aggregate only — verified against the androidx docs 2026-07-10), so the
 * watch streams passive HEART_RATE_BPM samples through this policy. Rules under test:
 *
 *  - fire on the CROSSING (below -> at/above threshold), not on every high sample;
 *  - hysteresis: after an alert, re-arm only once bpm dips below (threshold - margin), so a
 *    workout hovering above threshold doesn't machine-gun alerts;
 *  - hard cooldown between alerts regardless of crossings (flapping around the line);
 *  - low alerts mirror high alerts (hr_low when bpm <= lowThreshold).
 */
class HrAlertPolicyTest {

    private val minute = 60_000L

    @Test
    fun `below threshold never alerts`() {
        val policy = HrAlertPolicy(highBpm = 120)
        assertNull(policy.onSample(80.0, atMs = 0))
        assertNull(policy.onSample(119.0, atMs = minute))
    }

    @Test
    fun `crossing the high threshold fires one hr_high alert with the numbers`() {
        val policy = HrAlertPolicy(highBpm = 120)
        policy.onSample(90.0, atMs = 0)
        val alert = policy.onSample(142.0, atMs = minute)
        assertNotNull(alert)
        assertEquals("hr_high", alert.type)
        assertEquals(142, alert.bpm)
        assertEquals(120, alert.thresholdBpm)
        assertEquals(minute, alert.observedAtEpochMs)
        assertTrue(alert.requestId.isNotBlank())
    }

    @Test
    fun `sustained high does not re-alert`() {
        val policy = HrAlertPolicy(highBpm = 120)
        policy.onSample(90.0, atMs = 0)
        assertNotNull(policy.onSample(130.0, atMs = minute))
        assertNull(policy.onSample(135.0, atMs = 2 * minute))
        assertNull(policy.onSample(140.0, atMs = 30 * minute))
    }

    @Test
    fun `re-arms only after dipping below the hysteresis margin`() {
        val policy = HrAlertPolicy(highBpm = 120, rearmMargin = 10, cooldownMs = 5 * minute)
        policy.onSample(90.0, atMs = 0)
        assertNotNull(policy.onSample(130.0, atMs = minute))
        // 115 is below threshold but above (120-10) — still armed OFF.
        policy.onSample(115.0, atMs = 10 * minute)
        assertNull(policy.onSample(131.0, atMs = 11 * minute))
        // 105 is below the margin — re-armed; the next crossing fires (cooldown long passed).
        policy.onSample(105.0, atMs = 20 * minute)
        assertNotNull(policy.onSample(132.0, atMs = 21 * minute))
    }

    @Test
    fun `cooldown suppresses a re-crossing even after a full dip`() {
        val policy = HrAlertPolicy(highBpm = 120, rearmMargin = 10, cooldownMs = 10 * minute)
        policy.onSample(90.0, atMs = 0)
        assertNotNull(policy.onSample(130.0, atMs = minute))
        policy.onSample(80.0, atMs = 2 * minute)       // re-armed...
        assertNull(policy.onSample(130.0, atMs = 3 * minute))  // ...but inside cooldown
        policy.onSample(80.0, atMs = 12 * minute)
        assertNotNull(policy.onSample(130.0, atMs = 13 * minute)) // cooldown passed
    }

    @Test
    fun `resting drop below the low threshold fires hr_low`() {
        val policy = HrAlertPolicy(highBpm = 120, lowBpm = 40)
        policy.onSample(60.0, atMs = 0)
        val alert = policy.onSample(36.0, atMs = minute)
        assertNotNull(alert)
        assertEquals("hr_low", alert.type)
        assertEquals(36, alert.bpm)
        assertEquals(40, alert.thresholdBpm)
    }

    @Test
    fun `low alerts disabled by default`() {
        val policy = HrAlertPolicy(highBpm = 120)
        policy.onSample(60.0, atMs = 0)
        assertNull(policy.onSample(30.0, atMs = minute))
    }

    @Test
    fun `each alert gets a distinct request id`() {
        val policy = HrAlertPolicy(highBpm = 120, cooldownMs = 0, rearmMargin = 0)
        policy.onSample(90.0, atMs = 0)
        val a = policy.onSample(130.0, atMs = minute)
        policy.onSample(90.0, atMs = 2 * minute)
        val b = policy.onSample(130.0, atMs = 3 * minute)
        assertNotNull(a); assertNotNull(b)
        assertTrue(a.requestId != b.requestId)
    }
}
