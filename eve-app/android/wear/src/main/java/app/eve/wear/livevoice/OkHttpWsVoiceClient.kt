package app.eve.wear.livevoice

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Real [WsVoiceClient] over OkHttp's WebSocket — the thin transport edge only. Streams wrist-mic PCM16
 * (16 kHz mono) up as binary frames and plays Atlas's PCM down; server text frames become [VoiceEvent]s
 * via the pure [LiveVoiceCodec]. All conversation logic (timeouts, backoff, tap contract) lives in the
 * JVM-tested [LiveVoiceController] / [WearLiveVoiceViewModel]; this class only opens a socket and moves
 * bytes.
 *
 * House rule — no silent fallback: offline is a NAMED failure BEFORE dialing (ACCESS_NETWORK_STATE); a
 * 401 is a NAMED unauthorized failure; a mid-session drop is a [VoiceEvent.Dropped] the controller
 * reconnects with bounded backoff; a mic that won't open is a NAMED failure. Nothing hangs on silence.
 *
 * OkHttp expects an http/https request URL for a WebSocket upgrade, so a `wss://`/`ws://` door URL is
 * normalized to `https://`/`http://` before dialing (the Upgrade handshake is identical either way).
 */
class OkHttpWsVoiceClient(
    context: Context,
    private val mic: StreamingMicSource = AudioRecordStreamingMicSource(),
    private val player: StreamingPcmPlayer = AudioTrackStreamingPcmPlayer(),
) : WsVoiceClient {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = OkHttpClient.Builder()
        // No read timeout — a live socket is idle between turns; pings keep it alive instead.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    // Atlas's real output level: smoothed RMS of every downlink PCM frame (pure math in PcmLevel;
    // this edge only publishes). Reset on every teardown so no session inherits a stale glow.
    private val speakingEnvelope = SpeakingEnvelope()
    private val _botLevel = MutableStateFlow(0f)
    override val botLevel: StateFlow<Float> = _botLevel.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closedByUser = false
    @Volatile private var pendingToken: String = ""

    override suspend fun connect(wsUrl: String, token: String) {
        // Offline check (justifies ACCESS_NETWORK_STATE). On the wrist this is routinely a
        // TRANSIENT condition — the watch flips Wi-Fi <-> phone-Bluetooth with a gap in between
        // (hardware-found 2026-07-10: a mid-call flip ended as a hard "No network" verdict). So a
        // missing network is a retryable Dropped for the controller's bounded backoff; exhaustion
        // still lands in the named CONNECTION_LOST error, never a silent hang.
        if (!hasNetwork()) {
            Log.w(TAG, "no active network at dial time — retryable (watch network handoff?)")
            _events.tryEmit(VoiceEvent.Dropped)
            return
        }
        closePrevious()
        closedByUser = false
        pendingToken = token
        val httpUrl = wsUrl.trim()
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        val request = try {
            Request.Builder().url(httpUrl).build()
        } catch (t: Throwable) {
            Log.e(TAG, "Bad voice door URL '$wsUrl': ${t.message}", t)
            _events.tryEmit(VoiceEvent.Failed(WearLiveVoiceCopy.socketError("bad door URL")))
            return
        }
        webSocket = http.newWebSocket(request, Listener())
    }

    override fun setMicMuted(muted: Boolean) = mic.setMuted(muted)

    override fun interrupt() {
        webSocket?.send(LiveVoiceCodec.interruptFrame())
    }

    override fun hangUp() {
        closedByUser = true
        val ws = webSocket
        webSocket = null
        runCatching { ws?.send(LiveVoiceCodec.byeFrame()) }
        runCatching { ws?.close(NORMAL_CLOSURE, "bye") }
        mic.stop()
        player.stop()
        resetLevel()
    }

    private fun closePrevious() {
        val ws = webSocket
        webSocket = null
        runCatching { ws?.cancel() }
        mic.stop()
        player.stop()
        resetLevel()
    }

    private fun resetLevel() {
        speakingEnvelope.reset()
        _botLevel.value = 0f
    }

    private fun hasNetwork(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.activeNetwork != null
    } catch (t: Throwable) {
        Log.e(TAG, "Network check failed — assuming online", t)
        true // never block a call on a flaky check; the socket's own failure is still named
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // FIRST frame: authenticate. Then open the mic and start streaming up. The server drives
            // the state machine (it emits {"type":"state","state":"connected"} once authed).
            webSocket.send(LiveVoiceCodec.authFrame(pendingToken))
            val opened = mic.start { chunk -> webSocket.send(chunk.toByteString(0, chunk.size)) }
            if (!opened) {
                _events.tryEmit(VoiceEvent.Failed(WearLiveVoiceCopy.MIC_UNAVAILABLE))
                runCatching { webSocket.close(NORMAL_CLOSURE, "mic") }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            LiveVoiceCodec.decode(text)?.let { _events.tryEmit(it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            player.start(SAMPLE_RATE)
            val pcm = bytes.toByteArray()
            _botLevel.value = speakingEnvelope.onFrame(
                rms = PcmLevel.rms01(pcm),
                frameMs = PcmLevel.frameMs(pcm, SAMPLE_RATE),
            )
            player.write(pcm)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // The throwable IS the diagnosis (DNS vs TLS vs refused vs timeout) — hardware
            // debugging on 2026-07-10 went blind because it was discarded. Always log it.
            Log.e(TAG, "voice socket failure (http=${response?.code})", t)
            mic.stop()
            player.stop()
            resetLevel()
            if (closedByUser) return
            when {
                response?.code == 401 || response?.code == 403 ->
                    _events.tryEmit(VoiceEvent.Failed(WearLiveVoiceCopy.UNAUTHORIZED))
                // TLS rejection can't be fixed by retrying — NAMED immediately. DNS failure is
                // deliberately RETRYABLE: on a watch it usually means a network handoff in
                // progress, not a bad name (the logged throwable tells the two apart), and
                // exhaustion still ends in the named CONNECTION_LOST.
                t is javax.net.ssl.SSLException ->
                    _events.tryEmit(VoiceEvent.Failed(WearLiveVoiceCopy.socketError("TLS: ${t.message ?: "handshake failed"}")))
                else -> _events.tryEmit(VoiceEvent.Dropped)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            mic.stop()
            player.stop()
            resetLevel()
            // A server-initiated close mid-session is a drop the controller reconnects; a user close is
            // silent (we already tore down in hangUp).
            if (!closedByUser) _events.tryEmit(VoiceEvent.Dropped)
        }
    }

    private companion object {
        const val TAG = "OkHttpWsVoiceClient"
        const val NORMAL_CLOSURE = 1000
        const val SAMPLE_RATE = 16_000
    }
}
