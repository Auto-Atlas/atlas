package app.eve.health

import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.models.HealthSnapshotAck
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Upload error mapping (spec: reuse ApiResult conventions). Transient problems retry; terminal ones
 * fail loudly; only an honest `{"ok": true}` is a success — a 2xx with `ok == false` is NEVER
 * pretended into "stored".
 */
class HealthUploadPolicyTest {

    private fun outcome(result: ApiResult<HealthSnapshotAck>) = HealthUploadPolicy.outcomeFor(result)

    @Test
    fun `ok true is success`() {
        assertEquals(HealthUploadOutcome.SUCCESS, outcome(ApiResult.Ok(HealthSnapshotAck(ok = true))))
    }

    @Test
    fun `ok false is a terminal failure, not a fake success`() {
        assertEquals(HealthUploadOutcome.FAILURE, outcome(ApiResult.Ok(HealthSnapshotAck(ok = false))))
    }

    @Test
    fun `offline and 5xx and unknown are retryable`() {
        assertEquals(HealthUploadOutcome.RETRY, outcome(ApiResult.Err(ApiError.Offline("no route"))))
        assertEquals(HealthUploadOutcome.RETRY, outcome(ApiResult.Err(ApiError.Http(503, "down"))))
        assertEquals(HealthUploadOutcome.RETRY, outcome(ApiResult.Err(ApiError.Unknown("weird"))))
    }

    @Test
    fun `auth, 4xx, decode, not-configured are terminal`() {
        assertEquals(HealthUploadOutcome.FAILURE, outcome(ApiResult.Err(ApiError.Unauthorized)))
        assertEquals(HealthUploadOutcome.FAILURE, outcome(ApiResult.Err(ApiError.Http(400, "bad"))))
        assertEquals(HealthUploadOutcome.FAILURE, outcome(ApiResult.Err(ApiError.Http(413, "too big"))))
        assertEquals(HealthUploadOutcome.FAILURE, outcome(ApiResult.Err(ApiError.Decode("schema"))))
        assertEquals(HealthUploadOutcome.FAILURE, outcome(ApiResult.Err(ApiError.NotConfigured)))
    }
}
