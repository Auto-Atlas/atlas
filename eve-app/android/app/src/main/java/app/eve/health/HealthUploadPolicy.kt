package app.eve.health

import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.models.HealthSnapshotAck

/**
 * What the [HealthUploadWorker] should DO with an upload result — extracted as a pure function so the
 * error mapping is JVM-tested without WorkManager. Distinguishes transient failures worth a retry
 * (offline, 5xx, unknown) from terminal ones a retry can't fix (bad token, 4xx, decode). A failure is
 * NEVER swallowed into a success: only an [ApiResult.Ok] with `ok == true` maps to [SUCCESS].
 */
enum class HealthUploadOutcome {
    /** Stored server-side — record last-upload time, done. */
    SUCCESS,

    /** Transient (offline / 5xx / unknown) — WorkManager should back off and retry. */
    RETRY,

    /** Terminal (auth / client-error / decode / ok=false) — log loudly, don't spin on it. */
    FAILURE,
}

object HealthUploadPolicy {

    fun outcomeFor(result: ApiResult<HealthSnapshotAck>): HealthUploadOutcome = when (result) {
        is ApiResult.Ok ->
            // An honest server says {"ok": true}. A 2xx that decodes to ok=false is NOT a success we
            // pretend into — surface it as a terminal failure so it's visible, not silently "stored".
            if (result.value.ok) HealthUploadOutcome.SUCCESS else HealthUploadOutcome.FAILURE

        is ApiResult.Err -> when (val e = result.error) {
            // Reachability problems and server-side 5xx are transient — retry later.
            is ApiError.Offline -> HealthUploadOutcome.RETRY
            is ApiError.Http -> if (e.status in 500..599) HealthUploadOutcome.RETRY else HealthUploadOutcome.FAILURE
            is ApiError.Unknown -> HealthUploadOutcome.RETRY

            // Nothing a retry fixes: fail terminally (and loudly, at the call site).
            is ApiError.NotConfigured -> HealthUploadOutcome.FAILURE
            is ApiError.Unauthorized -> HealthUploadOutcome.FAILURE
            is ApiError.NotFound -> HealthUploadOutcome.FAILURE
            is ApiError.AlreadyResolved -> HealthUploadOutcome.FAILURE
            is ApiError.Decode -> HealthUploadOutcome.FAILURE
        }
    }
}
