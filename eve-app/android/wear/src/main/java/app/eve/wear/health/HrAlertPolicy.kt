package app.eve.wear.health

import app.eve.data.wear.HealthAlert
import java.util.UUID

/**
 * Pure threshold policy for the watch's passive heart-rate stream (Health v2 — "if my heart rate
 * jumps she can warn me"). Health Services offers NO bpm threshold goals (passive goals are
 * daily-aggregate only), so [HrAlertService] streams HEART_RATE_BPM samples through this brain.
 *
 * Alert discipline (JVM-tested in HrAlertPolicyTest):
 *  - fire on the CROSSING into the high zone, not on every high sample;
 *  - hysteresis: after firing, re-arm only when bpm dips below (threshold - [rearmMargin]) — a
 *    workout hovering above the line yields ONE alert, not a machine gun;
 *  - [cooldownMs] floor between alerts regardless of crossings (flapping guard);
 *  - optional [lowBpm] mirror for resting-low alerts (disabled unless configured).
 *
 * Timestamps come from the SAMPLE (audio... sensor time), never wall-clock reads inside the policy —
 * batched delivery (minutes-late on a dozing watch) must not distort the state machine.
 * Not thread-safe; the caller confines it to one dispatcher.
 */
class HrAlertPolicy(
    private val highBpm: Int,
    private val lowBpm: Int? = null,
    private val rearmMargin: Int = DEFAULT_REARM_MARGIN,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    private var highArmed = true
    private var lowArmed = true
    private var lastAlertAtMs: Long? = null // null = never alerted (avoids MIN_VALUE overflow math)

    /** Fold one bpm sample (sensor-stamped at [atMs]); returns an alert exactly when one is due. */
    fun onSample(bpm: Double, atMs: Long): HealthAlert? {
        val rounded = bpm.toInt()

        // Re-arm legs first, so a single dip both re-arms and can never itself fire.
        if (!highArmed && bpm < highBpm - rearmMargin) highArmed = true
        if (lowBpm != null && !lowArmed && bpm > lowBpm + rearmMargin) lowArmed = true

        val last = lastAlertAtMs
        val coolingDown = last != null && atMs - last < cooldownMs

        if (highArmed && bpm >= highBpm) {
            highArmed = false
            if (!coolingDown) {
                lastAlertAtMs = atMs
                return HealthAlert(
                    requestId = newRequestId(), type = "hr_high",
                    bpm = rounded, thresholdBpm = highBpm, observedAtEpochMs = atMs,
                )
            }
        }
        if (lowBpm != null && lowArmed && bpm <= lowBpm) {
            lowArmed = false
            if (!coolingDown) {
                lastAlertAtMs = atMs
                return HealthAlert(
                    requestId = newRequestId(), type = "hr_low",
                    bpm = rounded, thresholdBpm = lowBpm, observedAtEpochMs = atMs,
                )
            }
        }
        return null
    }

    companion object {
        /** Defaults are product defaults, not owner-specific values (house rule: no owner hardcoding). */
        const val DEFAULT_HIGH_BPM = 120
        const val DEFAULT_REARM_MARGIN = 10
        const val DEFAULT_COOLDOWN_MS = 10 * 60_000L
    }
}
