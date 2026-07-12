package app.eve.wear.livevoice

import app.eve.data.wear.VoiceDoorConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The thin seams the live-voice feature hides its Android/OkHttp engines behind, so ALL logic
 * (controller, ViewModel, codec) stays JVM-unit-testable with hand fakes (no mocking library). The
 * real impls ([OkHttpWsVoiceClient], [AudioRecordStreamingMicSource], [AudioTrackStreamingPcmPlayer],
 * [GmsVoiceDoorSource]) are the only classes that touch a live engine, and each stays deliberately thin.
 */

/**
 * The live-voice transport: ONE secure WebSocket to the owner's public voice door. Streams mic PCM16
 * (16 kHz mono) up, plays EVE's PCM down, and surfaces server control frames as [VoiceEvent]s on
 * [events] (a SharedFlow so BOTH the controller and the ViewModel can collect it — state and
 * transcript). The FIRST frame it sends on open is the auth frame; a rejected token / socket error /
 * server error frame all become NAMED events, never silence.
 */
interface WsVoiceClient {
    /** Control events mapped from the socket (open/auth/server frames/failure). Multi-collector. */
    val events: SharedFlow<VoiceEvent>

    /**
     * EVE's REAL output level (0..1): smoothed RMS of each downlink PCM frame ([PcmLevel] +
     * [SpeakingEnvelope]), reset to 0 on every teardown. The ring's Speaking pulse reads this —
     * the honest replacement for the synthetic sine it launched with.
     */
    val botLevel: StateFlow<Float>

    /** Dial [wsUrl] and authenticate with [token]. Called on start AND on each reconnect attempt. */
    suspend fun connect(wsUrl: String, token: String)

    /** Gate the outbound mic locally (EVE stops hearing you). Never an interrupt/barge-in. */
    fun setMicMuted(muted: Boolean)

    /** Send the interrupt control frame (barge-in) — stop EVE mid-utterance and reclaim the floor. */
    fun interrupt()

    /** Send bye (best-effort) and tear the socket + mic + player down. User-initiated: emits no Dropped. */
    fun hangUp()
}

/**
 * Continuous wrist-mic capture for the LIVE path (16 kHz mono PCM16), distinct from the turn-based
 * [app.eve.wear.talk.WristRecorder]: it does not buffer a whole utterance, it hands each small chunk to
 * [onChunk] as it is captured so the client can stream it up immediately.
 */
interface StreamingMicSource {
    /**
     * Open the mic and begin streaming chunks to [onChunk] (called off a capture thread). Returns true
     * if capture started, false if the mic could not be opened (permission/busy) — a false is a NAMED
     * failure at the caller, never a silent empty stream.
     */
    fun start(onChunk: (ByteArray) -> Unit): Boolean

    /** Locally gate the stream (muted = stop handing chunks up) without closing the mic. */
    fun setMuted(muted: Boolean)

    /** Stop capturing and release the mic. */
    fun stop()
}

/**
 * Continuous PCM16 playback for the LIVE path, distinct from the one-shot
 * [app.eve.wear.talk.PcmPlayer] (which stops+restarts AudioTrack per reply and would gap a live
 * stream). [start] opens a streaming AudioTrack once; [write] appends frames as they arrive.
 */
interface StreamingPcmPlayer {
    /** Open a streaming track at [sampleRate] Hz (mono, 16-bit). Idempotent while already open. */
    fun start(sampleRate: Int)

    /** Append one PCM16 frame to the open track. No-op if not started. */
    fun write(pcm: ByteArray)

    /** Stop and release the track. */
    fun stop()
}

/**
 * Seam over the Wearable Data Layer's retained [VoiceDoorConfig] the phone writes at
 * [app.eve.data.wear.WearLink.PATH_VOICE_DOOR]. Fakeable in tests; the GMS impl reads the DataItem the
 * phone put and pushes updates as they change (nothing hardcoded — a blank/absent door is honest).
 */
interface VoiceDoorSource {
    /** Current retained door FIRST (if any), then a new emission each time the phone writes one. */
    fun configs(): Flow<VoiceDoorConfig>

    /** One-shot read of the current retained door, or null if the phone has never written one. */
    suspend fun current(): VoiceDoorConfig?
}
