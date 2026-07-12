package app.eve.data

import app.eve.data.models.StreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

/**
 * WS /v1/stream client. Sends the app token in the `Sec-WebSocket-Protocol` header as
 * "bearer, <token>" (NOT a URL query param — a token in the URL leaks into access logs and
 * proxies; the backend validates it BEFORE accepting). Parses each pushed JSON frame into a
 * [StreamEvent] and emits them as a cold Flow. Decode failures on a single frame are skipped
 * (one malformed event must not kill the live connection); the Flow completes when the socket
 * closes.
 */
class StreamClient(
    // OkHttp engine for WSS over Android's platform TLS stack (see ApiClient for rationale).
    engine: HttpClientEngine = OkHttp.create(),
    private val connection: suspend () -> EveConnection,
    private val json: Json = ApiClient.DEFAULT_JSON,
) {
    private val http = HttpClient(engine) { install(WebSockets) }

    fun events(): Flow<StreamEvent> = callbackFlow {
        val conn = connection()
        if (!conn.isConfigured) {
            close(IllegalStateException("not configured"))
            return@callbackFlow
        }
        // A malformed saved base URL (double scheme, host-less, garbage) makes `takeFrom`/`build`
        // THROW. That throw is what crash-loops StreamService's reconnect loop. Validate first and
        // build defensively so a bad URL becomes a normal flow completion, never an uncaught crash.
        val normalized = BaseUrl.normalize(conn.baseUrl)
        if (normalized == null) {
            close(IllegalStateException("invalid base URL"))
            return@callbackFlow
        }
        val target = try {
            URLBuilder().apply {
                takeFrom(normalized)
                // https -> wss, http -> ws.
                protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
                encodedPath = "/v1/stream"
                // token goes in the Sec-WebSocket-Protocol header below, never the URL.
            }.build()
        } catch (e: Throwable) {
            close(e)
            return@callbackFlow
        }

        try {
            http.webSocket(
                urlString = target.toString(),
                request = { header(HttpHeaders.SecWebSocketProtocol, "bearer, ${conn.token}") },
            ) {
                while (isActive) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val event = runCatching { json.decodeFromString<StreamEvent>(frame.readText()) }.getOrNull()
                        if (event != null) trySend(event)
                    }
                }
            }
        } catch (e: Throwable) {
            // Connection dropped / refused — surface as flow completion; the ViewModel decides
            // whether to flip to Offline and retry.
            close(e)
        }
        awaitClose { /* webSocket block returns and closes the session on cancellation */ }
    }

    fun close() = http.close()
}
