package app.eve.voice

import app.eve.data.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The pipecat SmallWebRTC signaling client: POST/PATCH `{base}/api/offer` over Ktor. This is the
 * Kotlin equivalent of `@pipecat-ai/small-webrtc-transport`'s HTTP signaling. Phone_bot is
 * tailnet-gated only — no app token on this endpoint (spec §6).
 *
 * Honest [Result] mapping: a non-2xx (e.g. 422 on a bad body, 404 on an unknown pc_id) or a
 * transport failure becomes [Result.failure]; never an invented success. The connect timeout is
 * generous (≥15s) so phone_bot's ≤10s single-session teardown doesn't spuriously fail a legit
 * eviction-reconnect; [VoiceController] owns the higher-level reconnect policy.
 */
class SmallWebRtcSignaling(
    private val baseUrl: String,
    engine: HttpClientEngine = OkHttp.create(),
    private val json: Json = ApiClient.DEFAULT_JSON,
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(this@SmallWebRtcSignaling.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    }

    /** POST /api/offer — sends the local SDP offer, returns the SDP answer + minted pc_id. */
    suspend fun offer(request: SdpRequest): Result<SdpAnswer> = call(HttpMethod.Post, request) { resp ->
        json.decodeFromString(SdpAnswer.serializer(), resp.bodyAsText())
    }

    /** PATCH /api/offer — trickles buffered/late ICE candidates (snake_case sub-fields). */
    suspend fun patchIce(patch: IcePatch): Result<Unit> = call(HttpMethod.Patch, patch) { }

    private suspend inline fun <reified B, R> call(
        method: HttpMethod,
        body: B,
        crossinline parse: suspend (HttpResponse) -> R,
    ): Result<R> {
        val response: HttpResponse = try {
            http.request {
                this.method = method
                url {
                    takeFrom(baseUrl.trimEnd('/'))
                    encodedPath = "/api/offer"
                }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Throwable) {
            return Result.failure(e)
        }

        if (response.status.value !in 200..299) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            return Result.failure(SignalingException(response.status.value, detail))
        }
        return runCatching { parse(response) }
    }

    fun close() = http.close()
}

/** A non-2xx signaling response (e.g. 422 on a malformed body, 404 on an unknown pc_id). */
class SignalingException(val status: Int, val detail: String) :
    Exception("signaling HTTP $status: $detail")
