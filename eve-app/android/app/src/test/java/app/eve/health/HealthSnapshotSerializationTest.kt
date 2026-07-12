package app.eve.health

import app.eve.data.EveWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the EXACT wire JSON the sidecar's `POST /v1/health/snapshot` parses (encoded with the
 * canonical [EveWireJson]). A drift here is a contract break with the Python server, so the fully-
 * present shape is asserted byte-for-byte, and the gap case proves null fields are OMITTED while their
 * reason rides in `gaps`.
 */
class HealthSnapshotSerializationTest {

    private fun encode(s: HealthSnapshot): String =
        EveWireJson.encodeToString(HealthSnapshot.serializer(), s)

    private val full = HealthSnapshot(
        takenAt = "2026-07-10T14:03:09.512Z",
        heartRate = HealthSnapshot.HeartRate(72, "2026-07-10T13:58:00Z", 54, 141, 288),
        stepsToday = 8213,
        sleepLast = HealthSnapshot.Sleep("2026-07-10T05:12:00Z", "2026-07-10T12:41:00Z", 449),
        spo2Latest = HealthSnapshot.Spo2(97.0, "2026-07-10T12:40:00Z"),
        bloodPressureLatest = HealthSnapshot.BloodPressure(118, 76, "2026-07-09T21:15:00Z"),
        exerciseRecent = listOf(HealthSnapshot.Exercise("Running", 32, "2026-07-10T11:02:00Z")),
        gaps = emptyMap(),
    )

    @Test
    fun `fully-present snapshot encodes to the documented shape`() {
        val expected = """
            {"taken_at":"2026-07-10T14:03:09.512Z",""" +
            """"heart_rate":{"latest_bpm":72,"latest_at":"2026-07-10T13:58:00Z","min_bpm":54,"max_bpm":141,"samples_24h":288},""" +
            """"steps_today":8213,""" +
            """"sleep_last":{"start":"2026-07-10T05:12:00Z","end":"2026-07-10T12:41:00Z","minutes":449},""" +
            """"spo2_latest":{"pct":97.0,"at":"2026-07-10T12:40:00Z"},""" +
            """"blood_pressure_latest":{"systolic":118,"diastolic":76,"at":"2026-07-09T21:15:00Z"},""" +
            """"exercise_recent":[{"type":"Running","minutes":32,"at":"2026-07-10T11:02:00Z"}],""" +
            """"source":"health_connect","gaps":{}}"""
        assertEquals(expected.trimIndent().replace("\n", ""), encode(full))
    }

    @Test
    fun `missing types are omitted and their reasons ride in gaps`() {
        val gappy = full.copy(
            spo2Latest = null,
            bloodPressureLatest = null,
            gaps = mapOf(
                HealthSnapshot.KEY_SPO2 to "no data",
                HealthSnapshot.KEY_BLOOD_PRESSURE to "no data",
            ),
        )
        val json = encode(gappy)
        assertFalse(json.contains("\"spo2_latest\":{"), "omitted, not null-valued")
        assertFalse(json.contains("\"blood_pressure_latest\":{"))
        assertTrue(json.contains("\"gaps\":{\"spo2_latest\":\"no data\",\"blood_pressure_latest\":\"no data\"}"))
    }
}
