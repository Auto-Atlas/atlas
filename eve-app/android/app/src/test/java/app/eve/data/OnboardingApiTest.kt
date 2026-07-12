package app.eve.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Transport-level tests for the onboarding endpoints. The MockEngine captures the exact JSON body
 * the app posts so the (already-live) server contract — POST /v1/identity, POST /v1/enroll — is
 * verified field-by-field, and the responses are parsed by the real models.
 */
class OnboardingApiTest {

    private val conn: suspend () -> EveConnection = {
        EveConnection(baseUrl = "https://host.ts.net:8443", token = "secret-token-1234567890123456")
    }

    private fun bodyText(request: io.ktor.client.request.HttpRequestData): String =
        (request.body as io.ktor.http.content.TextContent).text

    @Test
    fun set_identity_posts_user_and_nick_only_when_present() = runTest {
        var seenPath: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenBody = bodyText(request)
            respond(
                content = """{"ok":true,"user":"the owner","nick":"J","whys":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.setIdentity(user = "the owner", nick = "J")

        assertEquals("/v1/identity", seenPath)
        val body = requireNotNull(seenBody)
        assertTrue(body.contains("\"user\":\"the owner\""), "user in body")
        assertTrue(body.contains("\"nick\":\"J\""), "nick in body")
        assertFalse(body.contains("whys"), "whys NOT sent when null")
        val ok = assertIs<ApiResult.Ok<*>>(result)
        val parsed = ok.value as app.eve.data.models.IdentityResult
        assertTrue(parsed.ok)
        assertEquals("the owner", parsed.user)
    }

    @Test
    fun set_identity_drops_blank_fields_and_sends_only_whys() = runTest {
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenBody = bodyText(request)
            respond(
                content = """{"ok":true,"whys":2}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        // Blank user/nick must be dropped; only the (trimmed, non-blank) whys are sent.
        val result = api.setIdentity(user = "   ", nick = "", whys = listOf(" build ", "", "family"))

        val body = requireNotNull(seenBody)
        assertFalse(body.contains("\"user\""), "blank user dropped")
        assertFalse(body.contains("\"nick\""), "blank nick dropped")
        assertTrue(body.contains("\"whys\":[\"build\",\"family\"]"), "trimmed, non-blank whys only")
        val ok = assertIs<ApiResult.Ok<*>>(result)
        assertEquals(2, (ok.value as app.eve.data.models.IdentityResult).whys)
    }

    @Test
    fun enroll_posts_name_tier_owner_and_clips_b64_array() = runTest {
        var seenPath: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenBody = bodyText(request)
            respond(
                content = """{"ok":true,"name":"the owner","tier":"owner","clips":3}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.enroll(name = "the owner", tier = "owner", clipsB64 = listOf("UklGRg==", "AAAA", "BBBB"))

        assertEquals("/v1/enroll", seenPath)
        val body = requireNotNull(seenBody)
        assertTrue(body.contains("\"name\":\"the owner\""), "name in body")
        assertTrue(body.contains("\"tier\":\"owner\""), "tier owner in body")
        assertTrue(
            body.contains("\"clips_b64\":[\"UklGRg==\",\"AAAA\",\"BBBB\"]"),
            "clips_b64 array preserved in order",
        )
        val ok = assertIs<ApiResult.Ok<*>>(result)
        val parsed = ok.value as app.eve.data.models.EnrollResult
        assertTrue(parsed.ok)
        assertEquals(3, parsed.clips)
        assertEquals("owner", parsed.tier)
    }

    @Test
    fun identity_unauthorized_maps_to_error_never_throws() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.Unauthorized) }
        val api = ApiClient(engine = engine, connection = conn)
        val err = assertIs<ApiResult.Err>(api.setIdentity(user = "the owner"))
        assertEquals(ApiError.Unauthorized, err.error)
    }
}
