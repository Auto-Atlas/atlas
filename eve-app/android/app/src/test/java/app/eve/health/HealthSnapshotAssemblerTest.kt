package app.eve.health

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure readout → wire-snapshot transform: the HR min/max/latest math, duration derivations, and —
 * the load-bearing rule — GAP HONESTY: every missing/empty type is an omitted field PLUS a `gaps`
 * entry naming exactly that field, never a fabricated value. No Android, no Health Connect, no clock.
 */
class HealthSnapshotAssemblerTest {

    private val takenAt: Instant = Instant.parse("2026-07-10T14:03:09.512Z")

    @Test
    fun `assembles heart rate latest-min-max-count from samples`() {
        val readout = readout(
            heartRate = Field.Present(
                listOf(
                    HrSample(72, Instant.parse("2026-07-10T13:58:00Z")),
                    HrSample(54, Instant.parse("2026-07-10T05:00:00Z")),
                    HrSample(141, Instant.parse("2026-07-10T11:00:00Z")),
                ),
            ),
        )

        val hr = HealthSnapshotAssembler.assemble(readout, takenAt).heartRate
        requireNotNull(hr)
        assertEquals(72, hr.latestBpm, "latest = the sample with the newest timestamp")
        assertEquals("2026-07-10T13:58:00Z", hr.latestAt)
        assertEquals(54, hr.minBpm)
        assertEquals(141, hr.maxBpm)
        assertEquals(3, hr.samples24h)
    }

    @Test
    fun `taken_at is ISO-8601 and source is health_connect`() {
        val snap = HealthSnapshotAssembler.assemble(readout(), takenAt)
        assertEquals("2026-07-10T14:03:09.512Z", snap.takenAt)
        assertEquals("health_connect", snap.source)
    }

    @Test
    fun `steps sleep spo2 bp exercise are copied and durations derived`() {
        val readout = readout(
            heartRate = Field.Present(listOf(HrSample(70, Instant.parse("2026-07-10T13:00:00Z")))),
            steps = Field.Present(8213),
            sleep = Field.Present(
                SleepReadout(Instant.parse("2026-07-10T05:12:00Z"), Instant.parse("2026-07-10T12:41:00Z")),
            ),
            spo2 = Field.Present(Spo2Readout(97.0, Instant.parse("2026-07-10T12:40:00Z"))),
            bloodPressure = Field.Present(
                BloodPressureReadout(118, 76, Instant.parse("2026-07-09T21:15:00Z")),
            ),
            exercise = Field.Present(
                listOf(
                    // Deliberately out of order to prove the assembler sorts by start.
                    ExerciseReadout("Walking", Instant.parse("2026-07-10T08:00:00Z"), Instant.parse("2026-07-10T08:20:00Z")),
                    ExerciseReadout("Running", Instant.parse("2026-07-10T06:00:00Z"), Instant.parse("2026-07-10T06:32:00Z")),
                ),
            ),
        )

        val snap = HealthSnapshotAssembler.assemble(readout, takenAt)
        assertEquals(8213, snap.stepsToday)
        assertEquals(449, snap.sleepLast!!.minutes, "7h29m sleep = 449 min")
        assertEquals("2026-07-10T05:12:00Z", snap.sleepLast!!.start)
        assertEquals(97.0, snap.spo2Latest!!.pct)
        assertEquals(118, snap.bloodPressureLatest!!.systolic)
        assertEquals(76, snap.bloodPressureLatest!!.diastolic)
        val ex = snap.exerciseRecent!!
        assertEquals(listOf("Running", "Walking"), ex.map { it.type }, "sorted by start time")
        assertEquals(32, ex[0].minutes)
        assertEquals(20, ex[1].minutes)
        assertTrue(snap.gaps.isEmpty(), "nothing missing → no gaps")
    }

    @Test
    fun `missing permission and missing data become null fields with named gaps`() {
        val readout = readout(
            heartRate = Field.Missing(Field.NO_PERMISSION),
            steps = Field.Missing(Field.NO_DATA),
            sleep = Field.Missing(Field.NO_PERMISSION),
            spo2 = Field.Missing(Field.NO_DATA),
            bloodPressure = Field.Missing(Field.NO_DATA),
            exercise = Field.Missing(Field.NO_PERMISSION),
        )

        val snap = HealthSnapshotAssembler.assemble(readout, takenAt)
        assertNull(snap.heartRate)
        assertNull(snap.stepsToday)
        assertNull(snap.sleepLast)
        assertNull(snap.spo2Latest)
        assertNull(snap.bloodPressureLatest)
        assertNull(snap.exerciseRecent)
        assertEquals(
            mapOf(
                HealthSnapshot.KEY_HEART_RATE to "no permission",
                HealthSnapshot.KEY_STEPS to "no data",
                HealthSnapshot.KEY_SLEEP to "no permission",
                HealthSnapshot.KEY_SPO2 to "no data",
                HealthSnapshot.KEY_BLOOD_PRESSURE to "no data",
                HealthSnapshot.KEY_EXERCISE to "no permission",
            ),
            snap.gaps,
        )
    }

    @Test
    fun `present-but-empty heart rate and exercise are honest no-data gaps, never fabricated`() {
        val readout = readout(
            heartRate = Field.Present(emptyList()),
            exercise = Field.Present(emptyList()),
        )
        val snap = HealthSnapshotAssembler.assemble(readout, takenAt)
        assertNull(snap.heartRate)
        assertNull(snap.exerciseRecent)
        assertEquals("no data", snap.gaps[HealthSnapshot.KEY_HEART_RATE])
        assertEquals("no data", snap.gaps[HealthSnapshot.KEY_EXERCISE])
    }

    @Test
    fun `a read-error reason is carried through verbatim, not swallowed`() {
        val readout = readout(spo2 = Field.Missing("read error: boom"))
        val snap = HealthSnapshotAssembler.assemble(readout, takenAt)
        assertNull(snap.spo2Latest)
        assertEquals("read error: boom", snap.gaps[HealthSnapshot.KEY_SPO2])
    }

    // ---- helpers ----

    private fun readout(
        heartRate: Field<List<HrSample>> = Field.Missing(Field.NO_DATA),
        steps: Field<Long> = Field.Missing(Field.NO_DATA),
        sleep: Field<SleepReadout> = Field.Missing(Field.NO_DATA),
        spo2: Field<Spo2Readout> = Field.Missing(Field.NO_DATA),
        bloodPressure: Field<BloodPressureReadout> = Field.Missing(Field.NO_DATA),
        exercise: Field<List<ExerciseReadout>> = Field.Missing(Field.NO_DATA),
    ) = HealthReadout(heartRate, steps, sleep, spo2, bloodPressure, exercise)
}
