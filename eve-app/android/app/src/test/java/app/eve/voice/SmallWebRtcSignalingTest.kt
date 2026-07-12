package app.eve.voice

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transport-level tests against Ktor MockEngine (mirrors data/ApiClientTest). MockEngine drives
 * ONLY the wire; the request shaping, snake_case body, answer parse and 422→failure mapping
 * under test are the app's real code. The answer fixture is the live phone_bot capture.
 */
class SmallWebRtcSignalingTest {

    private val base = "http://127.0.0.1:8789"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun offer_posts_to_api_offer_and_parses_live_answer() = runTest {
        var seenMethod: HttpMethod? = null
        var seenPath: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenPath = request.url.encodedPath
            seenBody = String(request.body.toByteArray())
            respond(
                content = fixture("offer_answer_sample.json"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val signaling = SmallWebRtcSignaling(baseUrl = base, engine = engine)
        val result = signaling.offer(SdpRequest(sdp = "v=0...", type = "offer"))

        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("/api/offer", seenPath)
        assertTrue(seenBody!!.contains("\"sdp\""), "offer body must carry sdp: $seenBody")
        assertTrue(seenBody!!.contains("\"type\":\"offer\""), seenBody)
        assertTrue(!seenBody!!.contains("pc_id"), "null pc_id must be omitted: $seenBody")

        assertTrue(result.isSuccess, "200 must map to success")
        val answer = result.getOrThrow()
        assertEquals("answer", answer.type)
        assertTrue(answer.pcId.isNotBlank())
    }

    @Test
    fun patch_sends_snake_case_candidate_fields() = runTest {
        var seenMethod: HttpMethod? = null
        var seenPath: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenPath = request.url.encodedPath
            seenBody = String(request.body.toByteArray())
            respond(
                content = """{"status":"success"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val signaling = SmallWebRtcSignaling(baseUrl = base, engine = engine)
        val result = signaling.patchIce(
            IcePatch(
                pcId = "abc123",
                candidates = listOf(
                    IceCandidatePatch(
                        candidate = "candidate:1 1 udp 2130706431 172.23.0.1 50100 typ host",
                        sdpMid = "0",
                        sdpMlineIndex = 0,
                    ),
                ),
            ),
        )

        assertEquals(HttpMethod.Patch, seenMethod)
        assertEquals("/api/offer", seenPath)
        assertTrue(seenBody!!.contains("\"pc_id\":\"abc123\""), seenBody)
        // The single most important wire assertion (Winston's silent-no-audio catch):
        assertTrue(seenBody!!.contains("\"sdp_mid\":\"0\""), "must be snake_case: $seenBody")
        assertTrue(seenBody!!.contains("\"sdp_mline_index\":0"), "must be snake_case: $seenBody")
        assertTrue(result.isSuccess)
    }

    @Test
    fun offer_422_maps_to_failure() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"detail":[{"type":"missing","loc":["body","sdp"]}]}""",
                status = HttpStatusCode.UnprocessableEntity,
            )
        }
        val signaling = SmallWebRtcSignaling(baseUrl = base, engine = engine)
        val result = signaling.offer(SdpRequest(sdp = "", type = "offer"))
        assertTrue(result.isFailure, "422 must map to Result.failure, never an invented success")
        val ex = result.exceptionOrNull()
        assertTrue(ex is SignalingException && ex.status == 422, "422 surfaced honestly: $ex")
    }

    @Test
    fun patch_404_unknown_pc_id_maps_to_failure() = runTest {
        val engine = MockEngine { respond("""{"detail":"Peer connection not found"}""", HttpStatusCode.NotFound) }
        val signaling = SmallWebRtcSignaling(baseUrl = base, engine = engine)
        val result = signaling.patchIce(IcePatch(pcId = "gone", candidates = emptyList()))
        assertTrue(result.isFailure)
        assertEquals(404, (result.exceptionOrNull() as? SignalingException)?.status)
    }
}
