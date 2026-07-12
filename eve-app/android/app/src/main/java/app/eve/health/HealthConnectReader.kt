package app.eve.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * The production [HealthSnapshotReader]: reads the last-24h window (steps use today-so-far) from
 * Health Connect and maps each record type to a plain [HealthReadout.Field]. This is the ONLY place
 * androidx.health.connect record reads happen. It stays deliberately THIN — per-type permission gate,
 * one query, mechanical mapping — and is NOT unit-tested on the JVM (the SDK + real provider are an
 * instrumentation concern); the interesting logic it feeds is the pure [HealthSnapshotAssembler].
 *
 * Honesty rules, per type: permission not granted → Missing([Field.NO_PERMISSION]); granted but the
 * query is empty → Missing([Field.NO_DATA]); an unexpected SDK read error is LOGGED loudly and
 * surfaced as Missing("read error: …") — never swallowed into a fake value or a fake success.
 *
 * [granted] is snapshotted once by the caller (the worker) so all six decisions use one consistent
 * view of permissions.
 */
class HealthConnectReader(
    private val client: HealthConnectClient,
    private val granted: Set<String>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) : HealthSnapshotReader {

    override suspend fun read(): HealthReadout {
        val end = now()
        val dayAgo = end.minus(WINDOW)
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()

        return HealthReadout(
            heartRate = readHeartRate(dayAgo, end),
            steps = readStepsToday(todayStart, end),
            sleep = readLastSleep(dayAgo, end),
            spo2 = readLatestSpo2(dayAgo, end),
            bloodPressure = readLatestBloodPressure(dayAgo, end),
            exercise = readExercise(dayAgo, end),
        )
    }

    private suspend fun readHeartRate(start: Instant, end: Instant): Field<List<HrSample>> =
        gated(HeartRateRecord::class, "heart_rate") {
            val records = readRecords(HeartRateRecord::class, start, end)
            val samples = records.flatMap { rec ->
                rec.samples.map { HrSample(bpm = it.beatsPerMinute, at = it.time) }
            }
            if (samples.isEmpty()) Field.Missing(Field.NO_DATA) else Field.Present(samples)
        }

    private suspend fun readStepsToday(start: Instant, end: Instant): Field<Long> =
        gated(StepsRecord::class, "steps") {
            val result: AggregationResult = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            val total = result[StepsRecord.COUNT_TOTAL]
            if (total == null) Field.Missing(Field.NO_DATA) else Field.Present(total)
        }

    private suspend fun readLastSleep(start: Instant, end: Instant): Field<SleepReadout> =
        gated(SleepSessionRecord::class, "sleep") {
            val latest = readRecords(SleepSessionRecord::class, start, end).maxByOrNull { it.endTime }
            if (latest == null) {
                Field.Missing(Field.NO_DATA)
            } else {
                Field.Present(SleepReadout(start = latest.startTime, end = latest.endTime))
            }
        }

    private suspend fun readLatestSpo2(start: Instant, end: Instant): Field<Spo2Readout> =
        gated(OxygenSaturationRecord::class, "spo2") {
            val latest = readRecords(OxygenSaturationRecord::class, start, end).maxByOrNull { it.time }
            if (latest == null) {
                Field.Missing(Field.NO_DATA)
            } else {
                Field.Present(Spo2Readout(pct = latest.percentage.value, at = latest.time))
            }
        }

    private suspend fun readLatestBloodPressure(start: Instant, end: Instant): Field<BloodPressureReadout> =
        gated(BloodPressureRecord::class, "blood_pressure") {
            val latest = readRecords(BloodPressureRecord::class, start, end).maxByOrNull { it.time }
            if (latest == null) {
                Field.Missing(Field.NO_DATA)
            } else {
                Field.Present(
                    BloodPressureReadout(
                        systolic = latest.systolic.inMillimetersOfMercury.toInt(),
                        diastolic = latest.diastolic.inMillimetersOfMercury.toInt(),
                        at = latest.time,
                    ),
                )
            }
        }

    private suspend fun readExercise(start: Instant, end: Instant): Field<List<ExerciseReadout>> =
        gated(ExerciseSessionRecord::class, "exercise") {
            val sessions = readRecords(ExerciseSessionRecord::class, start, end).map { rec ->
                val name = rec.title?.takeIf { it.isNotBlank() } ?: exerciseTypeName(rec.exerciseType)
                ExerciseReadout(type = name, start = rec.startTime, end = rec.endTime)
            }
            if (sessions.isEmpty()) Field.Missing(Field.NO_DATA) else Field.Present(sessions)
        }

    /** Reads all records of [type] in [start, end]. */
    private suspend fun <T : androidx.health.connect.client.records.Record> readRecords(
        type: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> =
        client.readRecords(
            ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        ).records

    /**
     * Permission gate + honest error handling shared by every read. Missing read permission short-circuits
     * to [Field.NO_PERMISSION]; a cancellation propagates (structured concurrency); any other throwable is
     * logged loudly and returned as a "read error" gap — never swallowed into a value.
     */
    private inline fun <T> gated(
        type: KClass<out androidx.health.connect.client.records.Record>,
        label: String,
        block: () -> Field<T>,
    ): Field<T> {
        if (HealthPermission.getReadPermission(type) !in granted) {
            return Field.Missing(Field.NO_PERMISSION)
        }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "health read failed for $label: ${t.message}", t)
            Field.Missing("read error: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }

    private fun exerciseTypeName(type: Int): String = EXERCISE_TYPE_NAMES[type] ?: "Workout"

    companion object {
        private const val TAG = "HealthReader"
        private val WINDOW: java.time.Duration = java.time.Duration.ofHours(24)

        /**
         * Readable labels for the common ExerciseSessionRecord.EXERCISE_TYPE_* constants. Health
         * Connect 1.1.0 exposes NO public int→name getter (the maps are `internal`), so we keep our
         * own; anything unmapped falls back to "Workout". Not owner-specific — plain activity names.
         */
        private val EXERCISE_TYPE_NAMES: Map<Int, String> = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
            ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "Baseball",
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Biking",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Stationary biking",
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Boot camp",
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dancing",
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
            ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "Gymnastics",
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "Rowing",
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill run",
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stair climbing",
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair machine",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength training",
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Stretching",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Open-water swim",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Pool swim",
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking",
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
        )
    }
}
