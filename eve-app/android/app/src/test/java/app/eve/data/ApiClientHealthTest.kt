package app.eve.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Transport-level checks for the health upload: it POSTs the given JSON verbatim to
 * /v1/health/snapshot with the bearer, parses the ack, and maps a non-2xx to an honest [ApiError]
 * (never a fake OK). MockEngine drives only the wire; the ApiClient under test is real.
 */
class ApiClientHealthTest {

    private val conn: suspend () -> EveConnection = {
        EveConnection(baseUrl = "https://host.ts.net:8443", token = "secret-token-1234567890123456")
    }

    @Test
    fun upload_health_snapshot_posts_body_with_bearer_and_parses_ack() = runTest {
        var seenPath: String? = null
        var seenAuth: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = """{"ok": true, "age_s": 0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)

        val body = buildJsonObject {
            put("taken_at", "2026-07-10T14:03:09Z")
            put("steps_today", 8213)
            put("source", "health_connect")
        }
        val result = api.uploadHealthSnapshot(body)

        assertEquals("/v1/health/snapshot", seenPath)
        assertEquals("Bearer secret-token-1234567890123456", seenAuth)
        assertTrue(seenBody!!.contains("\"steps_today\":8213"), "posts the snapshot body verbatim")
        val ok = assertIs<ApiResult.Ok<*>>(result)
        assertTrue((ok.value as app.eve.data.models.HealthSnapshotAck).ok)
    }

    @Test
    fun upload_health_snapshot_maps_500_to_http_error() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.uploadHealthSnapshot(buildJsonObject { put("taken_at", "x") })
        val err = assertIs<ApiResult.Err>(result)
        val http = assertIs<ApiError.Http>(err.error)
        assertEquals(500, http.status)
    }
}
