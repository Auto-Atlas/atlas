package app.eve.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The compact health snapshot uploaded to `POST /v1/health/snapshot` (kept in :app, NOT :shared —
 * it is a phone→sidecar wire contract the watch never touches, so it doesn't belong in the shared
 * DTO surface). Encoded with the canonical [app.eve.data.EveWireJson]: `explicitNulls=false` DROPS a
 * null field entirely (absent == null on the Python side), `encodeDefaults=true` keeps [source] and
 * [gaps].
 *
 * Exact shape (spec §Phone JSON), example with every type present:
 * ```
 * {
 *   "taken_at": "2026-07-10T14:03:09.512Z",
 *   "heart_rate": {"latest_bpm": 72, "latest_at": "2026-07-10T13:58:00Z",
 *                  "min_bpm": 54, "max_bpm": 141, "samples_24h": 288},
 *   "steps_today": 8213,
 *   "sleep_last": {"start": "2026-07-10T05:12:00Z", "end": "2026-07-10T12:41:00Z", "minutes": 449},
 *   "spo2_latest": {"pct": 97.0, "at": "2026-07-10T12:40:00Z"},
 *   "blood_pressure_latest": {"systolic": 118, "diastolic": 76, "at": "2026-07-09T21:15:00Z"},
 *   "exercise_recent": [{"type": "Running", "minutes": 32, "at": "2026-07-10T11:02:00Z"}],
 *   "source": "health_connect",
 *   "gaps": {}
 * }
 * ```
 * HONEST GAPS: a type EVE couldn't read is OMITTED from the object (its key absent == null) and its
 * reason recorded in [gaps] — e.g. `"gaps": {"blood_pressure_latest": "no data", "spo2_latest": "no permission"}`.
 * A field is NEVER present-but-fabricated; if it's here, it's real.
 */
@Serializable
data class HealthSnapshot(
    @SerialName("taken_at") val takenAt: String,
    @SerialName("heart_rate") val heartRate: HeartRate? = null,
    @SerialName("steps_today") val stepsToday: Long? = null,
    @SerialName("sleep_last") val sleepLast: Sleep? = null,
    @SerialName("spo2_latest") val spo2Latest: Spo2? = null,
    @SerialName("blood_pressure_latest") val bloodPressureLatest: BloodPressure? = null,
    @SerialName("exercise_recent") val exerciseRecent: List<Exercise>? = null,
    val source: String = SOURCE,
    /** field-name → reason ("no permission" | "no data") for every OMITTED field above. */
    val gaps: Map<String, String> = emptyMap(),
) {
    @Serializable
    data class HeartRate(
        @SerialName("latest_bpm") val latestBpm: Long,
        @SerialName("latest_at") val latestAt: String,
        @SerialName("min_bpm") val minBpm: Long,
        @SerialName("max_bpm") val maxBpm: Long,
        @SerialName("samples_24h") val samples24h: Int,
    )

    @Serializable
    data class Sleep(
        val start: String,
        val end: String,
        val minutes: Long,
    )

    @Serializable
    data class Spo2(
        val pct: Double,
        val at: String,
    )

    @Serializable
    data class BloodPressure(
        val systolic: Int,
        val diastolic: Int,
        val at: String,
    )

    @Serializable
    data class Exercise(
        val type: String,
        val minutes: Long,
        val at: String,
    )

    companion object {
        /** The only source in v1 — the on-phone Health Connect hub. */
        const val SOURCE = "health_connect"

        // Stable field-name keys used both as the JSON keys and as the `gaps` map keys, so a gap
        // names EXACTLY the field it explains.
        const val KEY_HEART_RATE = "heart_rate"
        const val KEY_STEPS = "steps_today"
        const val KEY_SLEEP = "sleep_last"
        const val KEY_SPO2 = "spo2_latest"
        const val KEY_BLOOD_PRESSURE = "blood_pressure_latest"
        const val KEY_EXERCISE = "exercise_recent"
    }
}
