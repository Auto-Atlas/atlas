package app.eve.wear.talk

import app.eve.data.wear.Outcome
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.VoiceTurnReply
import app.eve.data.wear.VoiceTurnRequest
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns the watch push-to-talk experience. In v2 the PRIMARY path is native: the wrist mic records raw
 * PCM ([WristRecorder]), streams it to the phone over ONE bidirectional channel ([VoiceTurnClient]),
 * and plays Atlas's own synthesized voice back ([PcmPlayer]) — no Google in the path. The v1
 * RecognizerIntent → `/v1/ask` text rail stays as the tested fallback ([ask]) and is spoken via the
 * on-watch TTS by the screen (only when [WearTalkPhase.Replied.spokenOnWatch] is false).
 *
 * The [scope], clock ([nowMs]) and id generator ([newRequestId]) are injected so coroutines-test drives
 * virtual time and correlates a reply by a known requestId (same convention as the approvals VM).
 *
 * House rule — no silent fallback: every leg failure becomes a named [WearTalkPhase.TalkFailure] with
 * exact copy from [WearTalkCopy]. A blank/zero-length recording is a named failure, NEVER sent onward.
 * Reply TEXT always renders even when audio/playback fails ([voiceState] carries the small voice note).
 */
class WearTalkViewModel(
    private val gateway: GatewayClient,
    private val recorder: WristRecorder,
    private val voiceClient: VoiceTurnClient,
    private val pcmPlayer: PcmPlayer,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _phase = MutableStateFlow<WearTalkPhase>(WearTalkPhase.Idle)
    val phase: StateFlow<WearTalkPhase> = _phase.asStateFlow()

    /** The session transcript (You + Atlas lines), newest last. No persistence in v1/v2. */
    private val _transcript = MutableStateFlow<List<TalkTurn>>(emptyList())
    val transcript: StateFlow<List<TalkTurn>> = _transcript.asStateFlow()

    /**
     * The honest voice-output note for the NATIVE path: mirrors [PcmPlayer.state] EXCEPT that a
     * server-side voice_error (Atlas answered but her TTS leg failed) is a sticky text-only note until
     * the next turn. Text always carries the reply; this drives a small secondary note only.
     */
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    /** Sticky server voice_error note (text-only) that outranks the player's Idle until the next turn. */
    private var serverVoiceNote: String? = null

    /** In-flight talk correlation (FALLBACK path): requestId -> awaiter completed when its reply arrives. */
    private val pendingReplies = mutableMapOf<String, CompletableDeferred<TalkReply>>()

    /** The recording ticker (elapsed + hard-cap auto-stop); null when not recording. */
    private var recordingJob: Job? = null
    private var recordingStartMs: Long = 0L
    /** Guards against a double finish if tap-stop and the cap fire together. */
    private var finishing: Boolean = false

    init {
        scope.launch { collectReplies() }
        // Mirror the player's honest state into the voice note, letting a sticky server voice_error win.
        scope.launch {
            pcmPlayer.state.collect { s ->
                _voiceState.value = serverVoiceNote?.let { VoiceState.Failed(it) } ?: s
            }
        }
    }

    // ---- v2 NATIVE path: record -> channel -> her voice ---------------------

    /**
     * Begin capturing on the wrist mic. The screen must have confirmed RECORD_AUDIO first (a denial
     * routes to [onMicPermissionDenied]); a mic that still won't open is a named failure. Starts the
     * elapsed/countdown ticker and the 15s hard cap that auto-stops and sends.
     */
    fun startRecording() {
        if (_phase.value is WearTalkPhase.Recording) return // already recording — ignore a repeat tap
        when (val start = recorder.start()) {
            is RecordStart.Failed -> _phase.value = WearTalkPhase.TalkFailure(start.message)
            RecordStart.Started -> {
                finishing = false
                recordingStartMs = nowMs()
                _phase.value = WearTalkPhase.Recording(elapsedMs = 0L, capMs = RECORD_CAP_MS)
                recordingJob = scope.launch {
                    while (true) {
                        val elapsed = nowMs() - recordingStartMs
                        if (elapsed >= RECORD_CAP_MS) {
                            // Hard cap: stop the ticker and send whatever we captured (never a silent cut).
                            recordingJob = null
                            scope.launch { finishRecordingAndSend() }
                            return@launch
                        }
                        _phase.value = WearTalkPhase.Recording(elapsedMs = elapsed, capMs = RECORD_CAP_MS)
                        delay(RECORD_TICK_MS)
                    }
                }
            }
        }
    }

    /** User tapped stop before the cap. Ends the ticker and sends the capture. No-op if not recording. */
    fun stopRecording() {
        if (_phase.value !is WearTalkPhase.Recording) return
        recordingJob?.cancel()
        recordingJob = null
        scope.launch { finishRecordingAndSend() }
    }

    /** RECORD_AUDIO denied at the screen — a named, visible failure (never silently no-op). */
    fun onMicPermissionDenied() {
        _phase.value = WearTalkPhase.TalkFailure(WearTalkCopy.MIC_PERMISSION)
    }

    private suspend fun finishRecordingAndSend() {
        if (finishing) return // tap-stop + cap fired together — only send once
        finishing = true
        _phase.value = WearTalkPhase.Sending
        try {
            when (val stop = recorder.stop()) {
                // Zero-length / encode failure: named, never sent onward as an empty WAV.
                is RecordStop.Failed -> _phase.value = WearTalkPhase.TalkFailure(stop.message)
                is RecordStop.Wav -> sendVoiceTurn(stop.bytes)
            }
        } finally {
            finishing = false
        }
    }

    /**
     * Run one native turn over the channel. The requestId is generated BEFORE the send and verified on
     * the reply (correlation kept from v1); a bidirectional channel returns the reply on the same call,
     * so there is no separate awaiter to race. The whole channel exchange is capped at [TURN_TIMEOUT_MS]
     * (above the phone's 65s HTTP leg) — silence past it is an honest failure, never a fake success.
     */
    private suspend fun sendVoiceTurn(wav: ByteArray) {
        val requestId = newRequestId()
        // Reset the voice note for the new turn; interrupt any lingering playback.
        serverVoiceNote = null
        pcmPlayer.stop()
        _voiceState.value = VoiceState.Idle

        val outcome = withTimeoutOrNull(TURN_TIMEOUT_MS) {
            voiceClient.runTurn(VoiceTurnRequest(requestId), wav) {
                // The request + audio have left the watch; Atlas's brain is now working.
                _phase.value = WearTalkPhase.ThinkingAwaitingReply
            }
        }
        _phase.value = when (outcome) {
            null -> WearTalkPhase.TalkFailure(WearTalkCopy.NO_REPLY)
            VoiceTurnOutcome.NoGatewayNode -> WearTalkPhase.TalkFailure(WearTalkCopy.DATA_LAYER_DOWN)
            is VoiceTurnOutcome.SendFailed -> WearTalkPhase.TalkFailure(WearTalkCopy.sendFailed(outcome.reason))
            is VoiceTurnOutcome.NoReply -> WearTalkPhase.TalkFailure(WearTalkCopy.channelNoReply(outcome.reason))
            is VoiceTurnOutcome.Replied ->
                if (outcome.reply.requestId != requestId) {
                    // A reply for a different turn is a broken contract — surfaced, never rendered as mine.
                    WearTalkPhase.TalkFailure(WearTalkCopy.channelNoReply("reply id mismatch"))
                } else {
                    phaseForVoiceReply(outcome.reply, outcome.pcm)
                }
        }
    }

    /**
     * Map one native [VoiceTurnReply] to a phase. On OK the server-side transcript (what Atlas heard)
     * becomes the You turn and her answer the Atlas turn; her PCM plays on the wrist. A blank transcript
     * on a non-OK outcome is the server's 422 "no speech" — the "Didn't catch that" copy. A voice_error
     * or a playback failure is a small note only; the reply TEXT always renders.
     */
    private fun phaseForVoiceReply(reply: VoiceTurnReply, pcm: ByteArray): WearTalkPhase {
        // Server recognized no speech (HTTP 422): blank (empty, non-null) transcript, non-OK outcome.
        if (reply.outcome != Outcome.OK && reply.transcript != null && reply.transcript!!.isBlank()) {
            return WearTalkPhase.TalkFailure(WearTalkCopy.DIDNT_CATCH)
        }
        val failure = WearTalkCopy.failureForVoice(reply)
        if (failure != null) return WearTalkPhase.TalkFailure(failure)

        // OK: the phone guarantees non-blank reply text. An empty reply on OK is a broken contract.
        val text = reply.reply
        if (text.isNullOrBlank()) return WearTalkPhase.TalkFailure(WearTalkCopy.EMPTY_REPLY)

        val heard = reply.transcript
        if (!heard.isNullOrBlank()) addTurn(TalkTurn.Speaker.You, heard)
        addTurn(TalkTurn.Speaker.Eve, text)

        // Audio: her voice_error is a text-only note; else play her PCM (playback failure is its own note).
        if (reply.voiceError != null) {
            serverVoiceNote = WearTalkCopy.VOICE_UNAVAILABLE
            _voiceState.value = VoiceState.Failed(WearTalkCopy.VOICE_UNAVAILABLE)
        } else if (pcm.isNotEmpty()) {
            pcmPlayer.play(pcm, reply.sampleRate)
        }
        return WearTalkPhase.Replied(text, spokenOnWatch = true)
    }

    // ---- v1 FALLBACK path: RecognizerIntent transcript -> /v1/ask -----------

    /**
     * Submit one STT transcript (the Google fallback rail). A blank transcript is rejected as a named
     * failure and NEVER sent downstream. The reply is text-only and the screen speaks it via the
     * on-watch TTS ([WearTalkPhase.Replied.spokenOnWatch] stays false).
     */
    fun ask(transcript: String) {
        val text = transcript.trim()
        if (text.isEmpty()) {
            _phase.value = WearTalkPhase.TalkFailure(WearTalkCopy.DIDNT_CATCH)
            return
        }
        val requestId = newRequestId()
        addTurn(TalkTurn.Speaker.You, text)
        _phase.value = WearTalkPhase.Sending

        val deferred = CompletableDeferred<TalkReply>()
        // Register the awaiter BEFORE sending so a fast phone reply can never be missed (same guard as v1).
        pendingReplies[requestId] = deferred

        scope.launch {
            try {
                when (val send = gateway.sendTalk(TalkRequest(requestId, text))) {
                    SendOutcome.NoGatewayNode ->
                        _phase.value = WearTalkPhase.TalkFailure(WearTalkCopy.DATA_LAYER_DOWN)
                    is SendOutcome.SendFailed ->
                        _phase.value = WearTalkPhase.TalkFailure(WearTalkCopy.sendFailed(send.reason))
                    SendOutcome.Sent -> {
                        _phase.value = WearTalkPhase.ThinkingAwaitingReply
                        val reply = withTimeoutOrNull(TALK_TIMEOUT_MS) { deferred.await() }
                        _phase.value =
                            if (reply == null) WearTalkPhase.TalkFailure(WearTalkCopy.NO_REPLY)
                            else phaseForReply(reply)
                    }
                }
            } finally {
                pendingReplies.remove(requestId)
            }
        }
    }

    /** The talk-screen reports an unavailable on-watch recognizer — a named failure, never silent. */
    fun reportSpeechUnavailable() {
        _phase.value = WearTalkPhase.TalkFailure(WearTalkCopy.NO_SPEECH_SERVICE)
    }

    private suspend fun collectReplies() {
        gateway.talkReplies().collect { reply ->
            // Complete the matching awaiter. An unmatched reply (already timed out / unknown id) is a no-op.
            pendingReplies[reply.requestId]?.complete(reply)
        }
    }

    /** Map one phone [TalkReply] (fallback path) to a phase: OK -> Replied (spoken via TTS), else TalkFailure. */
    private fun phaseForReply(reply: TalkReply): WearTalkPhase {
        val failure = WearTalkCopy.failureFor(reply)
        if (failure != null) return WearTalkPhase.TalkFailure(failure)
        val text = reply.reply
        if (text.isNullOrBlank()) return WearTalkPhase.TalkFailure(WearTalkCopy.EMPTY_REPLY)
        addTurn(TalkTurn.Speaker.Eve, text)
        return WearTalkPhase.Replied(text, spokenOnWatch = false)
    }

    private fun addTurn(speaker: TalkTurn.Speaker, text: String) {
        _transcript.update { it + TalkTurn(speaker, text, nowMs()) }
    }

    companion object {
        /**
         * No reply from the phone within this window is an honest failure (fallback path). Sits ABOVE
         * the phone's 55s HTTP leg and the server's 50s brain cap (each leg shorter than the one above).
         */
        internal const val TALK_TIMEOUT_MS = 60_000L

        /**
         * Native channel await window. Sits ABOVE the phone's 65s `/v1/voice/turn` HTTP leg (STT +
         * brain + TTS) and the server's 50s brain cap — the watch only gives up after the phone
         * genuinely could not answer.
         */
        internal const val TURN_TIMEOUT_MS = 75_000L

        /** Hard cap on one wrist recording. A visible countdown shows in the final 5s. */
        internal const val RECORD_CAP_MS = 15_000L

        /** Elapsed/countdown tick while recording. */
        internal const val RECORD_TICK_MS = 250L
    }
}
