package app.eve.health

import java.time.Instant

/**
 * The plain-Kotlin readout the [HealthSnapshotReader] seam returns — NO androidx.health.connect
 * types cross this boundary (they live only in [HealthConnectReader]). This is what the pure
 * [HealthSnapshotAssembler] turns into the wire [HealthSnapshot], so both the reading impl and the
 * assembly logic can be reasoned about (and JVM-tested) independently.
 *
 * Every field is a [Field]: either [Field.Present] with real values, or [Field.Missing] carrying the
 * HONEST reason a value is absent ([NO_PERMISSION] or [NO_DATA]). There is no third "empty but OK"
 * state — a readable-but-empty query is [Field.Missing] with [NO_DATA], never a fabricated zero.
 */
data class HealthReadout(
    /** Raw 24h heart-rate samples (assembler computes latest/min/max/count). */
    val heartRate: Field<List<HrSample>>,
    /** Today's step total (already aggregated by the source). */
    val steps: Field<Long>,
    /** The most recent completed sleep session. */
    val sleep: Field<SleepReadout>,
    /** The latest oxygen-saturation reading. */
    val spo2: Field<Spo2Readout>,
    /** The latest blood-pressure reading (only ever from a cuff — see the spec's Samsung note). */
    val bloodPressure: Field<BloodPressureReadout>,
    /** Recent exercise sessions in the window (type already resolved to a readable name by the impl). */
    val exercise: Field<List<ExerciseReadout>>,
)

/** One heart-rate sample: [bpm] at instant [at]. */
data class HrSample(val bpm: Long, val at: Instant)

/** A sleep session's bounds; the assembler derives its duration in minutes. */
data class SleepReadout(val start: Instant, val end: Instant)

/** A blood-oxygen reading as a percentage 0..100. */
data class Spo2Readout(val pct: Double, val at: Instant)

/** A blood-pressure reading in mmHg. */
data class BloodPressureReadout(val systolic: Int, val diastolic: Int, val at: Instant)

/** One exercise session; [type] is the readable name resolved in the impl, bounds give duration. */
data class ExerciseReadout(val type: String, val start: Instant, val end: Instant)

/**
 * A single health field: present with a value, or missing with a spoken-plainly reason. Modeled as a
 * sealed type (not nullable) so "why is this null" is never lost — the assembler copies the reason
 * straight into the snapshot's `gaps` map.
 */
sealed interface Field<out T> {
    data class Present<T>(val value: T) : Field<T>
    data class Missing(val reason: String) : Field<Nothing>

    companion object {
        /** The read permission for this type isn't granted. */
        const val NO_PERMISSION = "no permission"
        /** Permission is granted, but the source has no data for this type in the window. */
        const val NO_DATA = "no data"
    }
}
