package app.eve.wear.livevoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the watch LIVE-VOICE experience: the real call over one secure WebSocket, the orb morphing
 * through Atlas's REAL server states, tap-to-interrupt, and the running transcript. Thin over
 * [LiveVoiceController] (the state machine + timeouts/backoff) — it adds the config feed, the transcript
 * surface, and the tap contract.
 *
 * The [scope], [controller] and seams are injected so the JVM gate drives virtual time with fakes (no
 * GMS, no OkHttp, no audio engines). House rule — reply/state TEXT renders regardless of audio: the
 * transcript is fed from control frames, never from playback, so Atlas's words reach the wrist even if
 * her voice can't.
 *
 * Tap contract (exactly the phone's): idle/not-configured/error → connect (re-checks the door);
 * speaking / no-audio → interrupt (barge-in); anything else live → hang up.
 */
class WearLiveVoiceViewModel(
    private val client: WsVoiceClient,
    private val configSource: VoiceDoorSource,
    private val scope: CoroutineScope,
    private val controller: LiveVoiceController = LiveVoiceController(client, scope),
) {
    val state: StateFlow<VoiceState> = controller.state
    val controls: StateFlow<VoiceControls> = controller.controls

    /** Atlas's REAL output level (0..1, smoothed downlink-PCM RMS) — drives the ring's Speaking pulse. */
    val botLevel: StateFlow<Float> = client.botLevel

    private val _transcript = MutableStateFlow<List<LiveTranscriptLine>>(emptyList())
    val transcript: StateFlow<List<LiveTranscriptLine>> = _transcript.asStateFlow()

    init {
        // Feed the door config from the Data Layer into the machine (blank/absent → NotConfigured).
        scope.launch { configSource.configs().collect { controller.updateConfig(it) } }
        // Lift transcript frames onto the transcript surface (they never move the conversation machine).
        scope.launch {
            client.events.collect { event ->
                when (event) {
                    is VoiceEvent.UserTranscript -> addLine(LiveTranscriptLine.Speaker.You, event.text)
                    is VoiceEvent.BotTranscript -> addLine(LiveTranscriptLine.Speaker.Eve, event.text)
                    else -> {} // state events flow through the controller
                }
            }
        }
    }

    /**
     * The orb tap — the whole-screen talk/mute control (2026-07-11 wrist UX). The orb IS the screen,
     * so the tap never hangs the always-on call up:
     *  - not yet live (Idle / NotConfigured / Error) → start (re-checks the door; a missing one stays
     *    NotConfigured, never a fake dial);
     *  - Connecting → ignored: warm-up runs several seconds and a stray tap must not kill the call
     *    being built (hardware-found 2026-07-10);
     *  - Speaking / NoAudio → interrupt her AND leave the mic live so the owner can talk;
     *  - otherwise live (YourTurn / Hearing / Thinking / Reconnecting) → toggle the mic mute. Muting is
     *    truthful — it gates the client's outbound audio, not just the orb's look.
     */
    fun onOrbTap() {
        when (state.value) {
            VoiceState.Idle, VoiceState.NotConfigured, is VoiceState.Error -> controller.start()
            VoiceState.Connecting -> {} // ignore: see doc above
            VoiceState.Speaking, VoiceState.NoAudio -> controller.interruptAndUnmute()
            else -> controller.toggleMute()
        }
    }

    /** App-open auto-start: dial the configured door with no tap. Idempotent (see the controller). */
    fun onAutoStart() = controller.autoStartIfReady()

    /** Fresh screen entry: re-arm the one-shot auto-start (never from a hang-up — see controller). */
    fun onScreenEntry() = controller.rearmAutoStart()

    /**
     * Long-press on the orb = the deliberate END CALL (the orb-only UX carries no End button). Works
     * in EVERY live state — Connecting included, making it the deliberate warm-up abort the stray-tap
     * protection intentionally blocks. At rest (Idle / NotConfigured / Error) there is nothing to end:
     * a no-op, never a surprise dial or a faked action.
     */
    fun onOrbLongPress() {
        when (state.value) {
            VoiceState.Idle, VoiceState.NotConfigured, is VoiceState.Error -> {}
            else -> controller.hangUp()
        }
    }

    fun hangUp() = controller.hangUp()

    fun toggleMute() = controller.toggleMute()

    /** The screen denied RECORD_AUDIO — a named, visible failure (never a silent dead mic). */
    fun onMicPermissionDenied() = controller.fail(WearLiveVoiceCopy.MIC_UNAVAILABLE)

    private fun addLine(speaker: LiveTranscriptLine.Speaker, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return // an empty transcript frame is never a visible bubble
        _transcript.update { it + LiveTranscriptLine(speaker, trimmed) }
    }
}

/** One line of the live session transcript. No persistence — cleared with the ViewModel. */
data class LiveTranscriptLine(val speaker: Speaker, val text: String) {
    enum class Speaker { You, Eve }
}
