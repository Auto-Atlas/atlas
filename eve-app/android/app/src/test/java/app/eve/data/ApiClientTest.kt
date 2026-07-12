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
 * Transport-level tests against Ktor MockEngine. The MockEngine drives ONLY the wire (it
 * returns the committed real-shape fixture); the parsing, error mapping, and repository logic
 * under test are the app's real code — nothing in the domain is mocked.
 */
class ApiClientTest {

    private val conn: suspend () -> EveConnection = {
        EveConnection(baseUrl = "https://host.ts.net:8443", token = "secret-token-1234567890123456")
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun sends_bearer_header_and_parses_pending_approvals() = runTest {
        var seenAuth: String? = null
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenPath = request.url.encodedPath
            respond(
                content = fixture("approvals_sample.json"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.pendingApprovals()

        assertEquals("Bearer secret-token-1234567890123456", seenAuth, "Bearer token must be sent")
        assertEquals("/v1/approvals", seenPath)
        val ok = assertIs<ApiResult.Ok<*>>(result)
        @Suppress("UNCHECKED_CAST")
        val approvals = (ok.value as app.eve.data.models.ApprovalsResponse).approvals
        assertEquals(2, approvals.size)
        assertEquals(1200.0, approvals[0].totalDollars)
    }

    @Test
    fun register_push_token_posts_device_payload_and_parses_wake() = runTest {
        var seenPath: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = """{"ok": true, "wake": "05:00 America/New_York"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.registerPushToken(token = "fcm-abc", wakeHour = 5, wakeMinute = 0, tz = "America/New_York")

        assertEquals("/v1/push/register", seenPath)
        val body = requireNotNull(seenBody)
        assertTrue(body.contains("\"token\":\"fcm-abc\""), "token in body")
        assertTrue(body.contains("\"platform\":\"android\""), "platform pinned to android")
        assertTrue(body.contains("\"wake_hour\":5"), "wake_hour in body")
        assertTrue(body.contains("\"wake_minute\":0"), "wake_minute in body")
        assertTrue(body.contains("\"tz\":\"America/New_York\""), "device tz in body")
        val ok = assertIs<ApiResult.Ok<*>>(result)
        val reg = ok.value as app.eve.data.models.PushRegisterResult
        assertTrue(reg.ok)
        assertEquals("05:00 America/New_York", reg.wake)
    }

    @Test
    fun download_wake_audio_200_returns_bytes_and_etag_and_sends_no_inm_when_null() = runTest {
        val wav = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 1, 2, 3)
        var seenPath: String? = null
        var seenInm: String? = null
        var seenAuth: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenInm = request.headers[HttpHeaders.IfNoneMatch]
            seenAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = wav,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ETag, "abc123def456"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.downloadWakeAudio(currentEtag = null)

        assertEquals("/v1/wake/audio", seenPath)
        assertEquals("Bearer secret-token-1234567890123456", seenAuth, "wake download is authenticated")
        assertEquals(null, seenInm, "no If-None-Match when we have no cached etag")
        val dl = assertIs<WakeAudioResult.Downloaded>(result)
        assertTrue(wav.contentEquals(dl.bytes), "raw WAV bytes preserved")
        assertEquals("abc123def456", dl.etag, "etag taken from response header")
    }

    @Test
    fun download_wake_audio_sends_if_none_match_and_maps_304_to_not_modified() = runTest {
        var seenInm: String? = null
        val engine = MockEngine { request ->
            seenInm = request.headers[HttpHeaders.IfNoneMatch]
            respond(content = "", status = HttpStatusCode.NotModified, headers = headersOf(HttpHeaders.ETag, "cachedtag"))
        }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.downloadWakeAudio(currentEtag = "cachedtag")

        assertEquals("cachedtag", seenInm, "cached etag is replayed as If-None-Match")
        assertIs<WakeAudioResult.NotModified>(result)
    }

    @Test
    fun download_wake_audio_unconfigured_fails_without_request() = runTest {
        var requested = false
        val engine = MockEngine { requested = true; respond("", HttpStatusCode.OK) }
        val api = ApiClient(engine = engine, connection = { EveConnection(baseUrl = "", token = "") })
        val result = api.downloadWakeAudio(currentEtag = null)
        assertIs<WakeAudioResult.Failed>(result)
        assertTrue(!requested, "must not hit network when unconfigured")
    }

    @Test
    fun download_wake_audio_transport_error_maps_to_failed_never_throws() = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.downloadWakeAudio(currentEtag = "x")
        val failed = assertIs<WakeAudioResult.Failed>(result)
        assertTrue(failed.reason.contains("connection refused"), "honest failure reason")
    }

    @Test
    fun maps_401_to_unauthorized() = runTest {
        val engine = MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) }
        val api = ApiClient(engine = engine, connection = conn)
        val result = api.health()
        val err = assertIs<ApiResult.Err>(result)
        assertEquals(ApiError.Unauthorized, err.error)
    }

    @Test
    fun maps_409_to_already_resolved_in_repository() = runTest {
        val engine = MockEngine { respond("conflict", HttpStatusCode.Conflict) }
        val api = ApiClient(engine = engine, connection = conn)
        val repo = ApprovalRepository(api)
        val outcome = repo.approve("some-id")
        assertEquals(ApproveOutcome.AlreadyResolved, outcome, "409 -> AlreadyResolved domain state")
    }

    @Test
    fun maps_404_to_not_found() = runTest {
        val engine = MockEngine { respond("missing", HttpStatusCode.NotFound) }
        val api = ApiClient(engine = engine, connection = conn)
        val err = assertIs<ApiResult.Err>(api.activity("today"))
        assertEquals(ApiError.NotFound, err.error)
    }

    @Test
    fun not_configured_short_circuits_before_any_request() = runTest {
        var requested = false
        val engine = MockEngine { requested = true; respond("", HttpStatusCode.OK) }
        val api = ApiClient(engine = engine, connection = {
            EveConnection(baseUrl = "", token = "")
        })
        val err = assertIs<ApiResult.Err>(api.health())
        assertEquals(ApiError.NotConfigured, err.error)
        assertTrue(!requested, "must not hit the network when unconfigured")
    }

    @Test
    fun approve_ok_false_maps_to_send_failed_never_false_success() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"ok": false, "released_tool": "create_invoice", "result": {"ok": false, "error": "service down"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = ApprovalRepository(ApiClient(engine = engine, connection = conn))
        assertEquals(ApproveOutcome.SendFailed, repo.approve("id"))
    }

    @Test
    fun transport_exception_maps_to_offline_never_throws() = runTest {
        // Simulates a TLS handshake / cert / DNS / connection-refused failure on the real-device
        // HTTPS path: the engine throws, the client must return Offline, NOT crash the caller.
        val engine = MockEngine { throw java.io.IOException("TLS handshake failed: cert untrusted") }
        val api = ApiClient(engine = engine, connection = conn)
        val err = assertIs<ApiResult.Err>(api.health())
        val offline = assertIs<ApiError.Offline>(err.error)
        assertTrue(offline.cause.contains("TLS"), "underlying cause is preserved honestly")
    }

    @Test
    fun cancellation_is_rethrown_not_swallowed_as_offline() = runTest {
        // Structured concurrency: a CancellationException must propagate, never become Offline.
        val engine = MockEngine { throw kotlinx.coroutines.CancellationException("cancelled") }
        val api = ApiClient(engine = engine, connection = conn)
        var threw = false
        try {
            api.health()
        } catch (_: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue(threw, "CancellationException must be rethrown")
    }

    // ---- Agent tasks (live delegation activity + cancel/redirect) ----

    @Test
    fun agent_tasks_parses_active_and_recent_with_capabilities() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/agent-tasks", request.url.encodedPath)
            respond(
                content = fixture("agent_tasks_sample.json"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val ok = assertIs<ApiResult.Ok<app.eve.data.models.AgentTasksResponse>>(api.agentTasks())
        assertEquals(2, ok.value.active.size)
        assertEquals(1, ok.value.recent.size)
        val first = ok.value.active[0]
        assertEquals("hermes", first.agent)
        assertEquals("pending", first.status)
        assertTrue(first.capabilities?.cancel == true)
        assertTrue(first.capabilities?.redirect == true)
        val waiting = ok.value.active[1]
        assertEquals("awaiting_user", waiting.status)
        assertEquals("which date works?", waiting.question?.question)
        val done = ok.value.recent[0]
        assertEquals(false, done.capabilities?.redirect)
        assertEquals("task already finished", done.capabilities?.redirectReason)
    }

    @Test
    fun cancel_agent_task_posts_and_parses_honest_status() = runTest {
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            respond(
                content = """{"ok": true, "status": "cancel_requested",
                              "detail": "cancel requested - hermes will stop at its next check-in"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val ok = assertIs<ApiResult.Ok<app.eve.data.models.AgentTaskActionResult>>(
            api.cancelAgentTask("abc123"),
        )
        assertEquals("/v1/agent-tasks/abc123/cancel", seenPath)
        assertEquals("cancel_requested", ok.value.status)
        assertTrue(ok.value.detail.contains("check-in"))
    }

    @Test
    fun redirect_agent_task_posts_instructions_body() = runTest {
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenBody = String((request.body as io.ktor.http.content.TextContent).bytes())
            respond(
                content = """{"ok": true, "status": "redirect_pending",
                              "detail": "redirect staged - hermes gets it at its next check-in"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        val ok = assertIs<ApiResult.Ok<app.eve.data.models.AgentTaskActionResult>>(
            api.redirectAgentTask("abc123", "focus on pricing"),
        )
        assertEquals("redirect_pending", ok.value.status)
        assertTrue(seenBody!!.contains("focus on pricing"))
        assertTrue(seenBody!!.contains("instructions"))
    }

    @Test
    fun cancel_409_maps_to_not_cancellable_outcome_in_repository() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"detail": "task is not cancellable"}""",
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = ApprovalRepository(ApiClient(engine = engine, connection = conn))
        val outcome = repo.cancelTask("abc123")
        assertIs<CancelOutcome.NotCancellable>(outcome)
    }
}
