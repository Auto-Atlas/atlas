package app.eve.data

import app.eve.data.models.ActivityDigest
import app.eve.data.models.AgentTaskActionResult
import app.eve.data.models.AgentTasksResponse
import app.eve.data.models.ApproveResult
import app.eve.data.models.ApprovalsResponse
import app.eve.data.models.DenyResult
import app.eve.data.models.Health
import app.eve.data.models.MemoryAdd
import app.eve.data.models.MemoryAddResult
import app.eve.data.models.MemoryFacts
import app.eve.data.models.SettingsDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException

/**
 * Typed Ktor client for the Atlas approval API. CIO engine by default; an alternate engine
 * (e.g. MockEngine) can be injected for tests. Bearer token + base URL are pulled FRESH from
 * the provided [connection] lambda for every call, so rotating credentials in-app takes effect
 * without rebuilding the client.
 *
 * Every public call returns [ApiResult] — never throws into the caller, never invents success.
 */
class ApiClient(
    // OkHttp engine: uses Android's PLATFORM TLS stack (Conscrypt) instead of Ktor CIO's
    // pure-Kotlin TLS, which is flaky on real devices and was the suspected HTTPS-only crash.
    // MockEngine is still injected in tests.
    engine: HttpClientEngine = OkHttp.create(),
    private val connection: suspend () -> EveConnection,
    private val json: Json = DEFAULT_JSON,
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(this@ApiClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    suspend fun health(): ApiResult<Health> =
        call(HttpMethod.Get, "/v1/health") { decode(it) }

    suspend fun pendingApprovals(): ApiResult<ApprovalsResponse> =
        call(HttpMethod.Get, "/v1/approvals", query = mapOf("status" to "pending")) { decode(it) }

    suspend fun approve(id: String): ApiResult<ApproveResult> =
        call(HttpMethod.Post, "/v1/approvals/$id/approve") { decode(it) }

    suspend fun deny(id: String): ApiResult<DenyResult> =
        call(HttpMethod.Post, "/v1/approvals/$id/deny") { decode(it) }

    /**
     * Push-to-talk (watch): sends ONE utterance to Atlas's full brain (`POST /v1/ask {"text": ...}`)
     * and returns its `{"reply": "..."}`. The brain leg can take a while (tool calls, model latency),
     * so this call gets a longer [requestTimeoutMs] than the default 15s — the watch awaits 60s and
     * the server's own brain leg is capped at 50s, so 55s here sits honestly between them (each leg
     * shorter than the one above it). A timeout still surfaces as [ApiError.Offline], never a fake OK.
     */
    suspend fun ask(text: String): ApiResult<app.eve.data.models.AskResult> =
        call(
            HttpMethod.Post,
            "/v1/ask",
            jsonBody = buildJsonObject { put("text", text) },
            requestTimeoutMs = ASK_TIMEOUT_MS,
        ) { decode(it) }

    /**
     * v2 NATIVE watch voice turn: uploads ONE recorded utterance as base64 WAV to
     * `POST /v1/voice/turn` and returns Atlas's own STT transcript + brain reply + her synthesized voice
     * (16 kHz mono PCM16 WAV, base64) — no Google in the path. [audioB64] is a base64-encoded WAV
     * (RIFF, mono, 16-bit PCM at 16k). The server chains STT -> brain -> TTS, so this leg is the
     * longest of all: it gets [VOICE_TURN_TIMEOUT_MS] (65s), which sits below the watch's 75s channel
     * await and above the server's 50s brain cap (each leg shorter than the one above it). A timeout
     * still surfaces as [ApiError.Offline], never a fake OK; a null `audio_b64` with a `voice_error`
     * is an HONEST 200 (reply text delivered, voice leg named), not a failure.
     */
    suspend fun voiceTurn(audioB64: String, requestId: String): ApiResult<app.eve.data.models.VoiceTurnResult> =
        call(
            HttpMethod.Post,
            "/v1/voice/turn",
            // Body per the flow contract: {"audio_b64", "request_id"}. request_id is the watch's
            // correlation id, forwarded for server-side logging/idempotency.
            jsonBody = buildJsonObject {
                put("audio_b64", audioB64)
                put("request_id", requestId)
            },
            requestTimeoutMs = VOICE_TURN_TIMEOUT_MS,
        ) { decode(it) }

    /**
     * Health v2: forwards ONE watch-raised heart-rate alert to `POST /v1/health/event`. The body
     * mirrors the server contract ({"type", "bpm", "threshold_bpm", "observed_at", "source"});
     * the sidecar stamps its own receive time and the initiative engine turns it into Atlas's
     * spoken warning. Failures surface as honest [ApiError]s — the caller logs them LOUDLY
     * (a swallowed health alert would be the worst silent fallback in the app).
     */
    suspend fun healthEvent(alert: app.eve.data.wear.HealthAlert): ApiResult<Unit> =
        call(
            HttpMethod.Post,
            "/v1/health/event",
            jsonBody = buildJsonObject {
                put("type", alert.type)
                alert.bpm?.let { put("bpm", it) }
                alert.thresholdBpm?.let { put("threshold_bpm", it) }
                put("observed_at_epoch_ms", alert.observedAtEpochMs)
                put("request_id", alert.requestId)
                put("source", "watch_passive")
            },
        ) { }


    suspend fun agentTasks(): ApiResult<AgentTasksResponse> =
        call(HttpMethod.Get, "/v1/agent-tasks") { decode(it) }

    suspend fun cancelAgentTask(id: String): ApiResult<AgentTaskActionResult> =
        call(HttpMethod.Post, "/v1/agent-tasks/$id/cancel") { decode(it) }

    suspend fun redirectAgentTask(id: String, instructions: String): ApiResult<AgentTaskActionResult> =
        call(
            HttpMethod.Post,
            "/v1/agent-tasks/$id/redirect",
            jsonBody = buildJsonObject { put("instructions", instructions) },
        ) { decode(it) }

    suspend fun getSettings(): ApiResult<SettingsDto> =
        call(HttpMethod.Get, "/v1/settings") { decode(it) }

    suspend fun setRemoteApproval(enabled: Boolean): ApiResult<SettingsDto> =
        call(
            HttpMethod.Post,
            "/v1/settings",
            jsonBody = buildJsonObject { put("remote_approval_enabled", enabled) },
        ) { decode(it) }

    suspend fun setThinking(enabled: Boolean): ApiResult<SettingsDto> =
        call(
            HttpMethod.Post,
            "/v1/settings",
            jsonBody = buildJsonObject { put("thinking_enabled", enabled) },
        ) { decode(it) }

    suspend fun setBargeIn(enabled: Boolean): ApiResult<SettingsDto> =
        call(
            HttpMethod.Post,
            "/v1/settings",
            jsonBody = buildJsonObject { put("barge_in_enabled", enabled) },
        ) { decode(it) }

    suspend fun getMemory(speaker: String? = null): ApiResult<MemoryFacts> =
        call(
            HttpMethod.Get,
            "/v1/memory",
            query = speaker?.let { mapOf("speaker" to it) } ?: emptyMap(),
        ) { decode(it) }

    suspend fun addMemory(speaker: String?, fact: String): ApiResult<MemoryAddResult> =
        call(
            HttpMethod.Post,
            "/v1/memory",
            // explicitNulls=false in DEFAULT_JSON drops a null speaker -> owner page.
            jsonBody = json.encodeToJsonElement(MemoryAdd.serializer(), MemoryAdd(speaker, fact)) as JsonObject,
        ) { decode(it) }

    suspend fun activity(day: String = "today"): ApiResult<ActivityDigest> =
        call(HttpMethod.Get, "/v1/activity", query = mapOf("day" to day)) { decode(it) }

    /**
     * Onboarding — writes the owner's name / nick / "whys" to per-tenant config
     * (`POST /v1/identity`). All three are OPTIONAL: only the non-null fields are sent, so a step
     * that captures just the name (or just the whys) posts only those keys. A blank/empty value is
     * dropped too, so we never overwrite a stored value with emptiness. Server replies
     * `{"ok":true,"user","nick","whys":<count>}`.
     */
    suspend fun setIdentity(
        user: String? = null,
        nick: String? = null,
        whys: List<String>? = null,
    ): ApiResult<app.eve.data.models.IdentityResult> =
        call(
            HttpMethod.Post,
            "/v1/identity",
            jsonBody = buildJsonObject {
                user?.trim()?.takeIf { it.isNotBlank() }?.let { put("user", it) }
                nick?.trim()?.takeIf { it.isNotBlank() }?.let { put("nick", it) }
                // whys is sent whenever the caller provides a (possibly empty) list — an explicit
                // empty list is a deliberate "clear", distinct from null = "don't touch".
                whys?.let { lines ->
                    putJsonArray("whys") {
                        lines.map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
                    }
                }
            },
        ) { decode(it) }

    /**
     * Onboarding — enrolls the owner's voiceprint from clips recorded through the real mic
     * (`POST /v1/enroll`). Each clip in [clipsB64] MUST be a base64-encoded WAV (RIFF) file, mono
     * 16-bit PCM at 16k/24k (see [app.eve.onboarding.WavEncoder]). Server replies
     * `{"ok":true,"name","tier","clips":<n>}`.
     */
    suspend fun enroll(
        name: String,
        tier: String,
        clipsB64: List<String>,
    ): ApiResult<app.eve.data.models.EnrollResult> =
        call(
            HttpMethod.Post,
            "/v1/enroll",
            jsonBody = buildJsonObject {
                put("name", name)
                put("tier", tier)
                putJsonArray("clips_b64") { clipsB64.forEach { add(it) } }
            },
        ) { decode(it) }

    /**
     * look_via_phone: uploads ONE captured camera frame for a pending vision request
     * (`POST /v1/vision/frame`, same bearer). [jpegB64] is a base64-encoded JPEG (no wrapping);
     * the server decodes it, caps at 8 MB, and spools it transiently for the local vision model to
     * read exactly once. [requestId] must match the `capture_frame` event's id (plain lowercase hex).
     * Server replies `{"ok": true, "bytes": <n>}`.
     */
    suspend fun uploadVisionFrame(
        requestId: String,
        jpegB64: String,
    ): ApiResult<app.eve.data.models.VisionFrameResult> =
        call(
            HttpMethod.Post,
            "/v1/vision/frame",
            jsonBody = buildJsonObject {
                put("request_id", requestId)
                put("jpeg_b64", jpegB64)
            },
        ) { decode(it) }

    /**
     * Health v1: uploads ONE compact 24h health snapshot for the owner's watch/Health-Connect data
     * (`POST /v1/health/snapshot`, same bearer). [snapshot] is the already-built JSON object
     * ([app.eve.health.HealthSnapshot] encoded with EveWireJson) — ApiClient stays free of the health
     * DTO and just posts it, exactly like [uploadVisionFrame] posts its frame body. The sidecar stamps
     * it and stores it for Atlas's `health_status` tool; it replies `{"ok": true, ...}`. Every failure is
     * an honest [ApiResult.Err] — never a fake OK.
     */
    suspend fun uploadHealthSnapshot(
        snapshot: JsonObject,
    ): ApiResult<app.eve.data.models.HealthSnapshotAck> =
        call(HttpMethod.Post, "/v1/health/snapshot", jsonBody = snapshot) { decode(it) }

    /**
     * surface_visual: fetches ONE surfaced image (a desktop screenshot or a picture Atlas chose to
     * show) from the authenticated `GET /v1/visual/{id}`, returning the raw `image/jpeg` bytes for
     * BitmapFactory to decode. [visualId] is plain lowercase hex (the event's `visual_id`); it is a
     * single, already-validated path segment so no extra encoding is needed. Reads are
     * non-consuming server-side, so a reconnect can safely refetch. A `404` maps to
     * [ApiError.NotFound] — the app renders that as "expired".
     */
    suspend fun fetchVisual(visualId: String): ApiResult<ByteArray> =
        call(HttpMethod.Get, "/v1/visual/$visualId") { it.readBytes() }

    // ---- canonical OpenJarvis-proxied feed/status (the rich "what Atlas did" surfaces) ----

    suspend fun getActivityFeed(limit: Int = 25): ApiResult<app.eve.data.models.ActivityFeed> =
        call(HttpMethod.Get, "/v1/activity/feed", query = mapOf("limit" to limit.toString())) { decode(it) }

    suspend fun getActivityDetail(convId: String): ApiResult<app.eve.data.models.ConversationDetailResponse> =
        // The conv id contains colons (e.g. "voice:phone:178…"); encode it as a single path segment
        // so the colons don't read as a scheme/port and the whole id reaches the server intact.
        call(HttpMethod.Get, "/v1/activity/feed/${convId.encodeURLPathPart()}") { decode(it) }

    suspend fun getStatus(): ApiResult<app.eve.data.models.SystemStatus> =
        call(HttpMethod.Get, "/v1/status") { decode(it) }

    suspend fun getToday(): ApiResult<app.eve.data.models.Today> =
        call(HttpMethod.Get, "/v1/today") { decode(it) }

    suspend fun skills(): ApiResult<app.eve.data.models.SkillsResponse> =
        call(HttpMethod.Get, "/v1/skills") { decode(it) }

    suspend fun feedSkill(tool: String, mode: app.eve.data.models.FeedMode): ApiResult<app.eve.data.models.FeedResult> =
        call(
            HttpMethod.Post,
            "/v1/skills/$tool/feed",
            jsonBody = buildJsonObject { put("mode", mode.wire) },
        ) { decode(it) }

    suspend fun pendingFeeds(): ApiResult<app.eve.data.models.FeedsResponse> =
        call(HttpMethod.Get, "/v1/skills/feed") { decode(it) }

    suspend fun unprime(tool: String): ApiResult<app.eve.data.models.ClearResult> =
        call(HttpMethod.Delete, "/v1/skills/feed/$tool") { decode(it) }

    /**
     * Registers this device's FCM push token + desired ritual wake time with the server so it can
     * fire a high-priority `morning_ritual` data message even when the app is killed. Nothing here
     * is user-specific: [token], [tz] and the wake time are read per-device by the caller, not
     * hardcoded. Server replies `{"ok": true, "wake": "05:00 …"}`.
     */
    suspend fun registerPushToken(
        token: String,
        wakeHour: Int = 5,
        wakeMinute: Int = 0,
        tz: String,
    ): ApiResult<app.eve.data.models.PushRegisterResult> =
        call(
            HttpMethod.Post,
            "/v1/push/register",
            jsonBody = buildJsonObject {
                put("token", token)
                put("platform", "android")
                put("wake_hour", wakeHour)
                put("wake_minute", wakeMinute)
                put("tz", tz)
            },
        ) { decode(it) }

    /**
     * Downloads the 5 AM wake audio (`GET /v1/wake/audio`, `audio/wav`) so the phone can PLAY IT
     * LOCALLY at wake time — no voice connection, works from Doze. Conditional GET: pass the
     * previously-cached [currentEtag] in `If-None-Match`; the server replies `304 Not Modified` when
     * the whys text is unchanged so we skip re-downloading.
     *
     * Never throws — every failure (unconfigured, offline, bad status, decode) becomes
     * [WakeAudioResult.Failed]. The ETag is returned verbatim from the response header (the server
     * emits a bare 16-hex tag) so the caller can persist it and replay it on the next request.
     */
    suspend fun downloadWakeAudio(currentEtag: String?): WakeAudioResult {
        val conn = connection()
        if (!conn.isConfigured) return WakeAudioResult.Failed("not configured")

        val baseUrl = BaseUrl.normalize(conn.baseUrl)
            ?: return WakeAudioResult.Failed("invalid base URL")

        val response: HttpResponse = try {
            http.request {
                this.method = HttpMethod.Get
                url {
                    takeFrom(baseUrl)
                    encodedPath = "/v1/wake/audio"
                }
                header(HttpHeaders.Authorization, "Bearer ${conn.token}")
                if (!currentEtag.isNullOrBlank()) {
                    header(HttpHeaders.IfNoneMatch, currentEtag)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return WakeAudioResult.Failed(e.message ?: e::class.simpleName ?: "network error")
        }

        return when (val status = response.status.value) {
            304 -> WakeAudioResult.NotModified
            in 200..299 -> try {
                val bytes = response.readBytes()
                if (bytes.isEmpty()) {
                    WakeAudioResult.Failed("empty wake audio body")
                } else {
                    // ETag header is the source of truth for the cache key; fall back to keeping the
                    // old tag if the server omitted it (still a valid file to cache).
                    val etag = response.headers[HttpHeaders.ETag]?.trim('"')?.takeIf { it.isNotBlank() }
                        ?: currentEtag
                    WakeAudioResult.Downloaded(bytes, etag)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                WakeAudioResult.Failed(e.message ?: "failed to read wake audio")
            }
            else -> WakeAudioResult.Failed("HTTP $status")
        }
    }

    fun close() = http.close()

    // ---- internals ----------------------------------------------------------

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: JsonObject? = null,
        // Per-call override of the client-wide request/socket timeout, for a long leg like /v1/ask
        // (Atlas's brain). Null keeps the default 15s installed in the plugin above.
        requestTimeoutMs: Long? = null,
        parse: suspend (HttpResponse) -> T,
    ): ApiResult<T> {
        val conn = connection()
        if (!conn.isConfigured) return ApiResult.Err(ApiError.NotConfigured)

        // A malformed saved base URL would make `takeFrom` throw; catch it here as a clean
        // offline state (it is also caught by the request catch below, but this keeps the
        // failure honest and never lets a bad URL escape as an uncaught exception).
        val baseUrl = BaseUrl.normalize(conn.baseUrl)
            ?: return ApiResult.Err(ApiError.Offline("invalid base URL"))

        val response: HttpResponse = try {
            http.request {
                this.method = method
                url {
                    takeFrom(baseUrl)
                    encodedPath = path
                    query.forEach { (k, v) -> parameters.append(k, v) }
                }
                header(HttpHeaders.Authorization, "Bearer ${conn.token}")
                if (requestTimeoutMs != null) {
                    timeout {
                        requestTimeoutMillis = requestTimeoutMs
                        socketTimeoutMillis = requestTimeoutMs
                    }
                }
                if (jsonBody != null) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody)
                }
            }
        } catch (e: ClientRequestException) {
            // 4xx that the engine surfaced as an exception (shouldn't with expectSuccess=false,
            // but mapped honestly just in case an interceptor flips it).
            return ApiResult.Err(mapStatus(e.response.status.value, runCatching { e.response.bodyAsText() }.getOrDefault("")))
        } catch (e: ResponseException) {
            return ApiResult.Err(mapStatus(e.response.status.value, ""))
        } catch (e: CancellationException) {
            // Structured concurrency: a cancelled coroutine must propagate, never become an error.
            throw e
        } catch (e: Throwable) {
            // TLS handshake/cert/DNS/timeout/host-unreachable all land here as a clean Offline.
            return ApiResult.Err(ApiError.Offline(e.message ?: e::class.simpleName ?: "network error"))
        }

        val status = response.status.value
        if (status !in 200..299) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            return ApiResult.Err(mapStatus(status, detail))
        }
        return try {
            ApiResult.Ok(parse(response))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ApiResult.Err(ApiError.Decode(e.message ?: "failed to decode response"))
        }
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T =
        json.decodeFromString(response.bodyAsText())

    private fun mapStatus(status: Int, detail: String): ApiError = when (status) {
        401 -> ApiError.Unauthorized
        404 -> ApiError.NotFound
        409 -> ApiError.AlreadyResolved
        else -> ApiError.Http(status, detail)
    }

    companion object {
        // Alias of the canonical wire config in :shared (EveWireJson) — kept under the old name so
        // existing call sites and tests keep reading ApiClient.DEFAULT_JSON.
        val DEFAULT_JSON: Json = EveWireJson

        /** /v1/ask (watch talk) request timeout: below the watch's 60s await, above the server's 50s brain cap. */
        private const val ASK_TIMEOUT_MS = 55_000L

        /** /v1/voice/turn (native watch turn: STT+brain+TTS) timeout: below the watch's 75s channel await, above the server's 50s brain cap. */
        private const val VOICE_TURN_TIMEOUT_MS = 65_000L
    }
}
