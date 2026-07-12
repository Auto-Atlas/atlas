package app.eve.wear.talk

import app.eve.data.wear.Outcome
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.VoiceTurnReply
import app.eve.data.wear.VoiceTurnRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on [WearTalkViewModel] with hand-written fakes (no GMS, no mocking library). Mirrors
 * the approvals VM test convention: a standalone [TestScope] whose infinite init collectors are driven
 * with runCurrent()/advanceTimeBy() (NEVER advanceUntilIdle) and cancelled at the end. Every phase
 * transition + failure leg is asserted with its EXACT user copy from [WearTalkCopy]. Covers BOTH the
 * v2 native path (record -> channel -> her voice) and the v1 fallback (Google) rail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearTalkViewModelTest {

    // ---- fakes --------------------------------------------------------------

    private class FakeTalkGateway : GatewayClient {
        var sendOutcome: SendOutcome = SendOutcome.Sent
        val sent = mutableListOf<TalkRequest>()
        private val repliesFlow = MutableSharedFlow<TalkReply>(extraBufferCapacity = 8)
        override suspend fun sendTalk(request: TalkRequest): SendOutcome {
            sent += request
            return sendOutcome
        }
        override fun talkReplies(): Flow<TalkReply> = repliesFlow
        fun emitReply(r: TalkReply) {
            check(repliesFlow.tryEmit(r)) { "buffer overflow in FakeTalkGateway" }
        }
        override suspend fun sendAction(path: String, action: WearAction): SendOutcome = SendOutcome.Sent
        override fun results(): Flow<WearActionResult> = emptyFlow()
        override suspend fun requestRefresh(): SendOutcome = SendOutcome.Sent
        override suspend fun sendHealthAlert(alert: app.eve.data.wear.HealthAlert): SendOutcome = SendOutcome.Sent
    }

    private class FakeRecorder : WristRecorder {
        var startResult: RecordStart = RecordStart.Started
        var stopResult: RecordStop = RecordStop.Wav(byteArrayOf(1, 2, 3, 4))
        var startCount = 0
        var stopCount = 0
        override fun start(): RecordStart { startCount++; return startResult }
        override suspend fun stop(): RecordStop { stopCount++; return stopResult }
        override fun cancel() {}
    }

    private class FakeVoiceClient : VoiceTurnClient {
        var outcome: VoiceTurnOutcome = VoiceTurnOutcome.Replied(
            VoiceTurnReply("t-1", transcript = "what's on today?", reply = "You have a 3pm with Jamie.", outcome = Outcome.OK, sampleRate = 16_000, pcmByteCount = 4),
            pcm = byteArrayOf(9, 9, 9, 9),
        )
        var hang = false
        var invokeOnSent = true
        val requests = mutableListOf<VoiceTurnRequest>()
        override suspend fun runTurn(request: VoiceTurnRequest, wav: ByteArray, onSent: () -> Unit): VoiceTurnOutcome {
            requests += request
            if (invokeOnSent) onSent()
            if (hang) CompletableDeferred<Unit>().await() // never completes -> exercises the VM timeout
            return outcome
        }
    }

    private class FakePcmPlayer : PcmPlayer {
        private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
        override val state: StateFlow<VoiceState> = _state
        val played = mutableListOf<Pair<ByteArray, Int>>()
        var stopCount = 0
        override fun play(pcm: ByteArray, sampleRate: Int) { played += pcm to sampleRate; _state.value = VoiceState.Speaking }
        override fun stop() { stopCount++; _state.value = VoiceState.Idle }
        override fun release() {}
        fun emitState(s: VoiceState) { _state.value = s }
    }

    private class Fakes {
        val gateway = FakeTalkGateway()
        val recorder = FakeRecorder()
        val voiceClient = FakeVoiceClient()
        val pcmPlayer = FakePcmPlayer()
    }

    /** Build a started VM whose init collectors are already subscribed; clock tracks virtual time. */
    private fun startedVm(scope: TestScope, fakes: Fakes, requestId: String = "t-1"): WearTalkViewModel {
        val vm = WearTalkViewModel(
            gateway = fakes.gateway,
            recorder = fakes.recorder,
            voiceClient = fakes.voiceClient,
            pcmPlayer = fakes.pcmPlayer,
            scope = scope,
            nowMs = { scope.testScheduler.currentTime },
            newRequestId = { requestId },
        )
        scope.runCurrent()
        return vm
    }

    /** Record then stop, draining the launched send coroutine so the reply phase has settled. */
    private fun recordAndStop(scope: TestScope, vm: WearTalkViewModel) {
        vm.startRecording()
        scope.runCurrent()
        vm.stopRecording()
        scope.runCurrent()
    }

    // ==== v2 NATIVE path =====================================================

    @Test
    fun start_recording_enters_recording_phase_with_zero_elapsed() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.startRecording()

        val rec = assertIs<WearTalkPhase.Recording>(vm.phase.value)
        assertEquals(0L, rec.elapsedMs)
        assertEquals(WearTalkViewModel.RECORD_CAP_MS, rec.capMs)
        assertEquals(1, fakes.recorder.startCount)
        scope.cancel()
    }

    @Test
    fun recording_elapsed_advances_and_shows_countdown_in_final_five_seconds() {
        val scope = TestScope()
        val vm = startedVm(scope, Fakes())

        vm.startRecording()
        scope.advanceTimeBy(11_000) // 4s left -> within the countdown window, still recording
        scope.runCurrent()

        val rec = assertIs<WearTalkPhase.Recording>(vm.phase.value)
        assertTrue(rec.elapsedMs >= 11_000, "elapsed must track virtual time")
        assertEquals(4, rec.remainingSeconds)
        assertEquals("4s left", WearTalkCopy.countdown(rec.remainingSeconds))
        scope.cancel()
    }

    @Test
    fun hard_cap_auto_stops_and_sends_without_a_manual_stop() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.startRecording()
        scope.advanceTimeBy(WearTalkViewModel.RECORD_CAP_MS + 1)
        scope.runCurrent()

        // The cap stopped the recorder and drove the turn to its OK reply — never a silent cut.
        assertEquals(1, fakes.recorder.stopCount, "the cap must stop the recorder")
        assertEquals(1, fakes.voiceClient.requests.size, "the capped audio must be sent")
        assertIs<WearTalkPhase.Replied>(vm.phase.value)
        scope.cancel()
    }

    @Test
    fun happy_native_turn_shows_transcript_and_reply_turns_and_plays_her_voice() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        val replied = assertIs<WearTalkPhase.Replied>(vm.phase.value)
        assertEquals("You have a 3pm with Jamie.", replied.text)
        assertTrue(replied.spokenOnWatch, "native audio is played as PCM; the screen must NOT re-TTS it")
        // Server-side STT transcript becomes the You turn; her answer the EVE turn.
        assertEquals(
            listOf(TalkTurn.Speaker.You to "what's on today?", TalkTurn.Speaker.Eve to "You have a 3pm with Jamie."),
            vm.transcript.value.map { it.speaker to it.text },
        )
        // Her PCM was played on the wrist at the reply's sample rate.
        assertEquals(16_000, fakes.pcmPlayer.played.single().second)
        scope.cancel()
    }

    @Test
    fun onSent_moves_to_thinking_before_the_reply() {
        val scope = TestScope()
        val fakes = Fakes().apply { voiceClient.hang = true } // block after onSent, before the reply
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertIs<WearTalkPhase.ThinkingAwaitingReply>(vm.phase.value)
        scope.cancel()
    }

    @Test
    fun channel_timeout_is_an_honest_no_reply_not_success() {
        val scope = TestScope()
        val fakes = Fakes().apply { voiceClient.hang = true }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)
        assertIs<WearTalkPhase.ThinkingAwaitingReply>(vm.phase.value)
        scope.advanceTimeBy(WearTalkViewModel.TURN_TIMEOUT_MS + 1)
        scope.runCurrent()

        assertEquals("No reply from phone", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun voice_error_shows_text_with_the_note_and_does_not_play_audio() {
        val scope = TestScope()
        val fakes = Fakes().apply {
            voiceClient.outcome = VoiceTurnOutcome.Replied(
                VoiceTurnReply("t-1", transcript = "remind me at five", reply = "Reminder set for 5pm.", voiceError = "chatterbox unreachable", outcome = Outcome.OK, pcmByteCount = 0),
                pcm = ByteArray(0),
            )
        }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("Reminder set for 5pm.", assertIs<WearTalkPhase.Replied>(vm.phase.value).text)
        assertEquals(
            "EVE's voice is unavailable — text only",
            assertIs<VoiceState.Failed>(vm.voiceState.value).message,
        )
        assertTrue(fakes.pcmPlayer.played.isEmpty(), "no audio should play when the voice leg failed")
        scope.cancel()
    }

    @Test
    fun blank_server_transcript_surfaces_the_422_didnt_catch_copy() {
        val scope = TestScope()
        val fakes = Fakes().apply {
            voiceClient.outcome = VoiceTurnOutcome.Replied(
                VoiceTurnReply("t-1", transcript = "", reply = null, outcome = Outcome.ERROR, detail = "no speech recognized"),
                pcm = ByteArray(0),
            )
        }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("Didn't catch that — tap to retry.", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        assertTrue(vm.transcript.value.isEmpty(), "a no-speech turn is not a transcript line")
        scope.cancel()
    }

    @Test
    fun no_gateway_node_is_data_layer_down() {
        val scope = TestScope()
        val fakes = Fakes().apply { voiceClient.outcome = VoiceTurnOutcome.NoGatewayNode }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("Phone unreachable — Data Layer down", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun send_failed_names_the_leg_with_the_real_reason() {
        val scope = TestScope()
        val fakes = Fakes().apply { voiceClient.outcome = VoiceTurnOutcome.SendFailed("channel reset") }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("Phone unreachable — Data Layer down: channel reset", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun channel_no_reply_names_the_read_failure() {
        val scope = TestScope()
        val fakes = Fakes().apply { voiceClient.outcome = VoiceTurnOutcome.NoReply("channel closed before reply") }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("No reply from phone: channel closed before reply", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun a_reply_for_a_different_request_id_is_rejected_not_rendered() {
        val scope = TestScope()
        val fakes = Fakes().apply {
            voiceClient.outcome = VoiceTurnOutcome.Replied(
                VoiceTurnReply("someone-else", reply = "not mine", outcome = Outcome.OK, pcmByteCount = 0),
                pcm = ByteArray(0),
            )
        }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("No reply from phone: reply id mismatch", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun mic_permission_denied_is_a_named_visible_failure() {
        val scope = TestScope()
        val vm = startedVm(scope, Fakes())

        vm.onMicPermissionDenied()

        assertEquals("Microphone permission needed", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun mic_that_wont_open_is_a_named_failure_and_never_records() {
        val scope = TestScope()
        val fakes = Fakes().apply { recorder.startResult = RecordStart.Failed(WearTalkCopy.MIC_BUSY) }
        val vm = startedVm(scope, fakes)

        vm.startRecording()

        assertEquals("Microphone is busy — try again", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        assertEquals(0, fakes.voiceClient.requests.size)
        scope.cancel()
    }

    @Test
    fun empty_capture_is_a_named_failure_never_sent_onward() {
        val scope = TestScope()
        val fakes = Fakes().apply { recorder.stopResult = RecordStop.Failed(WearTalkCopy.RECORDING_EMPTY) }
        val vm = startedVm(scope, fakes)

        recordAndStop(scope, vm)

        assertEquals("Didn't hear anything — tap to retry.", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        assertTrue(fakes.voiceClient.requests.isEmpty(), "a zero-length recording must never reach the phone")
        scope.cancel()
    }

    // ==== v1 FALLBACK (Google) path — must stay green ========================

    @Test
    fun fallback_happy_path_sends_thinks_shows_and_records_reply() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.ask("what's on today?")
        assertIs<WearTalkPhase.Sending>(vm.phase.value)
        assertEquals(TalkTurn.Speaker.You to "what's on today?", vm.transcript.value.single().let { it.speaker to it.text })

        scope.runCurrent()
        assertEquals("what's on today?", fakes.gateway.sent.single().text)
        assertEquals("t-1", fakes.gateway.sent.single().requestId)
        assertIs<WearTalkPhase.ThinkingAwaitingReply>(vm.phase.value)

        fakes.gateway.emitReply(TalkReply("t-1", reply = "You have a 3pm with Jamie.", outcome = Outcome.OK))
        scope.runCurrent()

        val replied = assertIs<WearTalkPhase.Replied>(vm.phase.value)
        assertEquals("You have a 3pm with Jamie.", replied.text)
        assertTrue(!replied.spokenOnWatch, "the fallback reply is text-only; the screen speaks it via TTS")
        scope.cancel()
    }

    @Test
    fun fallback_blank_transcript_is_rejected_and_never_sent() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.ask("   ")
        scope.runCurrent()

        assertEquals("Didn't catch that — tap to retry.", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        assertTrue(fakes.gateway.sent.isEmpty())
        assertTrue(vm.transcript.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun fallback_speech_unavailable_names_the_missing_recognizer() {
        val scope = TestScope()
        val vm = startedVm(scope, Fakes())

        vm.reportSpeechUnavailable()

        assertEquals(
            "No speech service on this watch — enable Speech Services by Google.",
            assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message,
        )
        scope.cancel()
    }

    @Test
    fun fallback_no_reply_within_timeout_is_a_failure() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.ask("hi")
        scope.runCurrent()
        assertIs<WearTalkPhase.ThinkingAwaitingReply>(vm.phase.value)
        scope.advanceTimeBy(WearTalkViewModel.TALK_TIMEOUT_MS + 1)
        scope.runCurrent()

        assertEquals("No reply from phone", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun fallback_fast_reply_before_await_is_not_missed() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.ask("hi")
        fakes.gateway.emitReply(TalkReply("t-1", reply = "quick answer", outcome = Outcome.OK))
        scope.runCurrent()

        assertEquals("quick answer", assertIs<WearTalkPhase.Replied>(vm.phase.value).text)
        scope.cancel()
    }

    @Test
    fun fallback_server_unreachable_reply_shows_detail() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.ask("hi")
        scope.runCurrent()
        fakes.gateway.emitReply(TalkReply("t-1", outcome = Outcome.SERVER_UNREACHABLE, detail = "connection refused"))
        scope.runCurrent()

        assertEquals("Phone can't reach EVE: connection refused", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }

    @Test
    fun fallback_ok_reply_with_blank_text_is_a_loud_failure() {
        val scope = TestScope()
        val fakes = Fakes()
        val vm = startedVm(scope, fakes)

        vm.ask("hi")
        scope.runCurrent()
        fakes.gateway.emitReply(TalkReply("t-1", reply = "   ", outcome = Outcome.OK))
        scope.runCurrent()

        assertEquals("EVE returned an empty reply", assertIs<WearTalkPhase.TalkFailure>(vm.phase.value).message)
        scope.cancel()
    }
}
