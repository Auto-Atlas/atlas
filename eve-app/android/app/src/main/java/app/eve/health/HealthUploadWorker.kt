package app.eve.health

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.eve.EveApplication
import app.eve.data.EveWireJson
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the 24h health snapshot and uploads it to the sidecar. Runs BOTH as a periodic worker
 * (every 30 min, network-gated) and as an expedited one-shot the Status row triggers on demand.
 *
 * LOUD failure, never silent: a transient problem (offline / 5xx) returns [Result.retry] so
 * WorkManager backs off and tries again; a terminal one (bad token, 4xx, Health Connect unavailable)
 * logs at ERROR and returns [Result.failure]; the outcome is also written to [HealthUploadStore] so
 * the UI can show the last error. Nothing is ever reported as stored unless the server said `ok`.
 */
class HealthUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? EveApplication ?: run {
            Log.e(TAG, "no EveApplication container — cannot upload health snapshot")
            return Result.failure()
        }
        val container = app.container
        val manager = container.healthConnectManager
        val store = container.healthUploadStore

        val availability = manager.availability()
        if (availability != HealthAvailability.AVAILABLE) {
            // Not a retry-able condition — nothing to read until the user installs/updates HC.
            Log.w(TAG, "Health Connect not available ($availability); skipping upload")
            store.recordFailure("Health Connect $availability")
            return Result.failure()
        }
        val client = manager.clientOrNull() ?: run {
            Log.w(TAG, "Health Connect client unavailable; skipping upload")
            store.recordFailure("Health Connect client unavailable")
            return Result.failure()
        }

        // One consistent view of granted permissions for all six reads.
        val granted = manager.grantedPermissions()
        val reader = HealthConnectReader(client, granted)

        val snapshot = try {
            val readout = reader.read()
            HealthSnapshotAssembler.assemble(readout, Instant.now())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // The reader guards per-type reads; a throw here is unexpected. Loud + retry.
            Log.e(TAG, "health snapshot read failed: ${t.message}", t)
            store.recordFailure("read failed: ${t.message}")
            return Result.retry()
        }

        val json = EveWireJson.encodeToJsonElement(HealthSnapshot.serializer(), snapshot) as JsonObject

        return when (val outcome = HealthUploadPolicy.outcomeFor(container.apiClient.uploadHealthSnapshot(json))) {
            HealthUploadOutcome.SUCCESS -> {
                val at = System.currentTimeMillis()
                store.recordSuccess(at)
                Log.i(TAG, "health snapshot uploaded (gaps=${snapshot.gaps.keys})")
                Result.success()
            }
            HealthUploadOutcome.RETRY -> {
                Log.w(TAG, "health snapshot upload transient failure ($outcome) — will retry")
                store.recordFailure("upload failed (retrying)")
                Result.retry()
            }
            HealthUploadOutcome.FAILURE -> {
                Log.e(TAG, "health snapshot upload failed terminally ($outcome)")
                store.recordFailure("upload failed")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "HealthUpload"

        /** Unique names so periodic + on-demand work never pile up duplicates. */
        private const val UNIQUE_PERIODIC = "eve_health_periodic"
        private const val UNIQUE_SYNC_NOW = "eve_health_sync_now"

        /** Spec: every 30 minutes (watch data is batched — minutes stale is expected). */
        private const val PERIOD_MINUTES = 30L

        private val networkRequired: Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * (Re)schedule the periodic upload. KEEP policy: idempotent — calling it every launch (only
         * when permissions are granted) never resets the running schedule or duplicates it.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthUploadWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkRequired)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stop the periodic upload (e.g. permissions revoked) — no-op if it wasn't scheduled. */
        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }

        /**
         * Fire an immediate upload from the UI. Expedited so it runs promptly; RUN_AS_NON_EXPEDITED_WORK_REQUEST
         * degrades gracefully (no foreground notification needed) when the app is out of expedited quota.
         * REPLACE so a fresh tap always reads current data rather than coalescing into a stale queued run.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthUploadWorker>()
                .setConstraints(networkRequired)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
