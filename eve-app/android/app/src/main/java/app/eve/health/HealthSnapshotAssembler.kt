package app.eve.health

import java.time.Duration
import java.time.Instant

/**
 * Pure readout → wire-snapshot transform. NO Android, NO Health Connect, NO clock — [takenAt] is
 * passed in — so the whole assembly (incl. gap honesty and the HR min/max/latest math) is unit-tested
 * on the JVM. Every [Field.Missing] becomes an OMITTED field plus a `gaps[field] = reason` entry; a
 * [Field.Present] emits the real value and no gap. There is no path that invents a value.
 */
object HealthSnapshotAssembler {

    fun assemble(readout: HealthReadout, takenAt: Instant): HealthSnapshot {
        val gaps = LinkedHashMap<String, String>()

        val heartRate = when (val f = readout.heartRate) {
            is Field.Present -> {
                val samples = f.value
                if (samples.isEmpty()) {
                    // Present-but-empty is a source honesty gap, not a fabricated zero-BPM reading.
                    gaps[HealthSnapshot.KEY_HEART_RATE] = Field.NO_DATA
                    null
                } else {
                    val latest = samples.maxBy { it.at }
                    HealthSnapshot.HeartRate(
                        latestBpm = latest.bpm,
                        latestAt = iso(latest.at),
                        minBpm = samples.minOf { it.bpm },
                        maxBpm = samples.maxOf { it.bpm },
                        samples24h = samples.size,
                    )
                }
            }
            is Field.Missing -> { gaps[HealthSnapshot.KEY_HEART_RATE] = f.reason; null }
        }

        val steps = when (val f = readout.steps) {
            is Field.Present -> f.value
            is Field.Missing -> { gaps[HealthSnapshot.KEY_STEPS] = f.reason; null }
        }

        val sleep = when (val f = readout.sleep) {
            is Field.Present -> HealthSnapshot.Sleep(
                start = iso(f.value.start),
                end = iso(f.value.end),
                minutes = minutesBetween(f.value.start, f.value.end),
            )
            is Field.Missing -> { gaps[HealthSnapshot.KEY_SLEEP] = f.reason; null }
        }

        val spo2 = when (val f = readout.spo2) {
            is Field.Present -> HealthSnapshot.Spo2(pct = f.value.pct, at = iso(f.value.at))
            is Field.Missing -> { gaps[HealthSnapshot.KEY_SPO2] = f.reason; null }
        }

        val bloodPressure = when (val f = readout.bloodPressure) {
            is Field.Present -> HealthSnapshot.BloodPressure(
                systolic = f.value.systolic,
                diastolic = f.value.diastolic,
                at = iso(f.value.at),
            )
            is Field.Missing -> { gaps[HealthSnapshot.KEY_BLOOD_PRESSURE] = f.reason; null }
        }

        val exercise = when (val f = readout.exercise) {
            is Field.Present -> {
                if (f.value.isEmpty()) {
                    gaps[HealthSnapshot.KEY_EXERCISE] = Field.NO_DATA
                    null
                } else {
                    f.value
                        .sortedBy { it.start }
                        .map {
                            HealthSnapshot.Exercise(
                                type = it.type,
                                minutes = minutesBetween(it.start, it.end),
                                at = iso(it.start),
                            )
                        }
                }
            }
            is Field.Missing -> { gaps[HealthSnapshot.KEY_EXERCISE] = f.reason; null }
        }

        return HealthSnapshot(
            takenAt = iso(takenAt),
            heartRate = heartRate,
            stepsToday = steps,
            sleepLast = sleep,
            spo2Latest = spo2,
            bloodPressureLatest = bloodPressure,
            exerciseRecent = exercise,
            gaps = gaps,
        )
    }

    /** ISO-8601 UTC (e.g. `2026-07-10T14:03:09.512Z`) — [Instant.toString] is exactly that. */
    private fun iso(instant: Instant): String = instant.toString()

    /** Whole minutes between two instants, clamped at 0 so a reversed/degenerate pair is never negative. */
    private fun minutesBetween(start: Instant, end: Instant): Long =
        Duration.between(start, end).toMinutes().coerceAtLeast(0)
}
