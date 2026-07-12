package app.eve.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Transport-level tests for `GET /v1/visual/{id}` (surface_visual image fetch). The MockEngine only
 * drives the wire; the bearer auth, path building, byte read and 404→NotFound mapping under test are
 * the app's real ApiClient code.
 */
class ApiClientVisualTest {

    private val conn: suspend () -> EveConnection = {
        EveConnection(baseUrl = "https://host.ts.net:8443", token = "secret-token-1234567890123456")
    }

    @Test
    fun fetch_visual_sends_bearer_and_returns_jpeg_bytes() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        var seenAuth: String? = null
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenPath = request.url.encodedPath
            respond(
                content = jpeg,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)

        val result = api.fetchVisual("0123456789abcdef")

        assertEquals("Bearer secret-token-1234567890123456", seenAuth)
        assertEquals("/v1/visual/0123456789abcdef", seenPath)
        val ok = assertIs<ApiResult.Ok<ByteArray>>(result)
        assertTrue(jpeg.contentEquals(ok.value), "raw jpeg bytes returned unchanged")
    }

    @Test
    fun fetch_visual_404_maps_to_not_found_for_expired() = runTest {
        val engine = MockEngine {
            respond(content = "visual expired or unknown", status = HttpStatusCode.NotFound)
        }
        val api = ApiClient(engine = engine, connection = conn)

        val result = api.fetchVisual("deadbeefdeadbeef")

        val err = assertIs<ApiResult.Err>(result)
        assertEquals(ApiError.NotFound, err.error)
    }
}
