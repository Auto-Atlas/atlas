package app.eve.wear.livevoice

import app.eve.data.wear.VoiceDoorConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on [WearLiveVoiceViewModel] + [LiveVoiceController] with hand fakes (no OkHttp, no
 * GMS, no audio engines). Mirrors the approvals/talk VM convention: a standalone [TestScope] whose
 * infinite init collectors are driven with runCurrent()/advanceTimeBy() (NEVER advanceUntilIdle) and
 * cancelled at the end. Every failure leg is asserted with its EXACT [WearLiveVoiceCopy] string.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearLiveVoiceViewModelTest {

    private class FakeWsVoiceClient : WsVoiceClient {
        private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()
        private val _botLevel = kotlinx.coroutines.flow.MutableStateFlow(0f)
        override val botLevel: kotlinx.coroutines.flow.StateFlow<Float> = _botLevel
        val connects = mutableListOf<Pair<String, String>>()
        var interruptCount = 0
        var hangUpCount = 0
        var lastMuted: Boolean? = null
        override suspend fun connect(wsUrl: String, token: String) { connects += wsUrl to token }
        override fun setMicMuted(muted: Boolean) { lastMuted = muted }
        override fun interrupt() { interruptCount++ }
        override fun hangUp() { hangUpCount++ }
        fun emit(event: VoiceEvent) { check(_events.tryEmit(event)) { "event buffer overflow" } }
        fun setLevel(level: Float) { _botLevel.value = level }
    }

    private class FakeVoiceDoorSource(initial: VoiceDoorConfig?) : VoiceDoorSource {
        private val flow = MutableSharedFlow<VoiceDoorConfig>(replay = 1, extraBufferCapacity = 8)
        init { if (initial != null) check(flow.tryEmit(initial)) }
        override fun configs(): Flow<VoiceDoorConfig> = flow
        override suspend fun current(): VoiceDoorConfig? = flow.replayCache.lastOrNull()
        fun emit(config: VoiceDoorConfig) { check(flow.tryEmit(config)) }
    }

    private val configured = VoiceDoorConfig("wss://door/v1/watch/voice", "tok-1")

    private fun vmWith(
        scope: TestScope,
        client: FakeWsVoiceClient,
        door: FakeVoiceDoorSource,
        controller: LiveVoiceController = LiveVoiceController(client, scope),
    ): WearLiveVoiceViewModel {
        val vm = WearLiveVoiceViewModel(client, door, scope, controller)
        scope.runCurrent()
        return vm
    }

    // ---- config → NotConfigured / Idle ----

    @Test
    fun blank_door_config_sits_the_orb_in_not_configured() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(VoiceDoorConfig("", "tok")))
        assertEquals(VoiceState.NotConfigured, vm.state.value)
        // Tapping cannot dial a missing door — stays NotConfigured, never a fake connect.
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(VoiceState.NotConfigured, vm.state.value)
        assertTrue(client.connects.isEmpty())
        scope.cancel()
    }

    @Test
    fun configured_door_makes_the_orb_idle_and_ready() {
        val scope = TestScope()
        val vm = vmWith(scope, FakeWsVoiceClient(), FakeVoiceDoorSource(configured))
        assertEquals(VoiceState.Idle, vm.state.value)
        scope.cancel()
    }

    @Test
    fun a_door_arriving_later_flips_not_configured_to_idle() {
        val scope = TestScope()
        val door = FakeVoiceDoorSource(VoiceDoorConfig("", ""))
        val vm = vmWith(scope, FakeWsVoiceClient(), door)
        assertEquals(VoiceState.NotConfigured, vm.state.value)
        door.emit(configured); scope.runCurrent()
        assertEquals(VoiceState.Idle, vm.state.value)
        scope.cancel()
    }

    // ---- tap contract: idle->connect, speaking->interrupt, else->hangup ----

    @Test
    fun tap_from_idle_dials_the_configured_door() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(VoiceState.Connecting, vm.state.value)
        assertEquals("wss://door/v1/watch/voice" to "tok-1", client.connects.single())
        // Server "connected" → the floor is yours.
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, vm.state.value)
        scope.cancel()
    }

    @Test
    fun tap_while_speaking_interrupts_and_reclaims_the_floor_with_a_live_mic() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); client.emit(VoiceEvent.BotSpeaking); scope.runCurrent()
        assertEquals(VoiceState.Speaking, vm.state.value)
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(1, client.interruptCount)
        assertEquals(VoiceState.YourTurn, vm.state.value)
        // The floor is handed back with a LIVE mic — never a muted, deaf-looking EVE.
        assertEquals(false, vm.controls.value.micMuted)
        scope.cancel()
    }

    @Test
    fun tap_while_speaking_after_a_mute_interrupts_and_unmutes() {
        // The owner muted himself, then taps the orb while EVE talks: the tap stops her AND
        // reopens his mic so he can speak — the tap is never a silent no-op mid-utterance.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        vm.onOrbTap(); scope.runCurrent() // YourTurn tap → mute the mic (talk/mute toggle)
        assertEquals(true, vm.controls.value.micMuted)
        client.emit(VoiceEvent.BotSpeaking); scope.runCurrent()
        assertEquals(VoiceState.Speaking, vm.state.value)
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(1, client.interruptCount)
        assertEquals(VoiceState.YourTurn, vm.state.value)
        assertEquals(false, vm.controls.value.micMuted)
        assertEquals(false, client.lastMuted)
        scope.cancel()
    }

    @Test
    fun tap_while_connecting_is_ignored_never_a_silent_hangup() {
        // Hardware-found defect (2026-07-10): session warm-up takes seconds, users double-tap,
        // and else->hangUp silently killed the call being built. Connecting taps are no-ops.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(VoiceState.Connecting, vm.state.value)
        vm.onOrbTap(); vm.onOrbTap(); scope.runCurrent()
        assertEquals(0, client.hangUpCount)
        assertEquals(VoiceState.Connecting, vm.state.value)
        // The connect still completes normally afterwards.
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, vm.state.value)
        scope.cancel()
    }

    @Test
    fun tap_while_your_turn_toggles_the_mic_mute_never_hangs_up() {
        // New wrist UX (2026-07-11): the orb is the whole screen and a tap is the talk/mute
        // toggle — it must NEVER hang the call up (that killed always-on voice). Muting is
        // truthful: it gates the client's outbound mic, not just the UI.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, vm.state.value)
        vm.onOrbTap(); scope.runCurrent() // mute
        assertEquals(0, client.hangUpCount)
        assertEquals(VoiceState.YourTurn, vm.state.value)
        assertEquals(true, vm.controls.value.micMuted)
        assertEquals(true, client.lastMuted)
        vm.onOrbTap(); scope.runCurrent() // unmute
        assertEquals(false, vm.controls.value.micMuted)
        assertEquals(false, client.lastMuted)
        assertEquals(0, client.hangUpCount)
        scope.cancel()
    }

    // ---- auto-start: app opens -> the call dials itself, exactly once ----

    @Test
    fun auto_start_dials_the_configured_door_without_a_tap() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        assertEquals(VoiceState.Idle, vm.state.value)
        vm.onAutoStart(); scope.runCurrent()
        assertEquals(VoiceState.Connecting, vm.state.value)
        assertEquals("wss://door/v1/watch/voice" to "tok-1", client.connects.single())
        scope.cancel()
    }

    @Test
    fun auto_start_is_single_shot_against_repeats_and_config_redelivery() {
        // The double-dial fix (2026-07-11): the door source re-emits the SAME retained config
        // (initial read + listener redelivery ~1-3s later), and the screen effect can retrigger.
        // Auto-start must open exactly ONE socket — the second dial preempted and killed the first.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val door = FakeVoiceDoorSource(configured)
        val vm = vmWith(scope, client, door)
        vm.onAutoStart(); scope.runCurrent()
        door.emit(configured) // identical redelivery
        vm.onAutoStart(); vm.onAutoStart(); scope.runCurrent()
        assertEquals(1, client.connects.size, "auto-start dials the door exactly once")
        assertEquals(VoiceState.Connecting, vm.state.value)
        scope.cancel()
    }

    @Test
    fun auto_start_does_nothing_without_a_configured_door() {
        // No door written yet: auto-start must NOT fake a dial — it honestly stays NotConfigured.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(VoiceDoorConfig("", "tok")))
        assertEquals(VoiceState.NotConfigured, vm.state.value)
        vm.onAutoStart(); scope.runCurrent()
        assertEquals(VoiceState.NotConfigured, vm.state.value)
        assertTrue(client.connects.isEmpty())
        scope.cancel()
    }

    // ---- long-press = the deliberate END CALL (orb-only UX keeps no End button) ----

    @Test
    fun long_press_in_a_live_call_hangs_up_rests_idle_and_never_auto_redials() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onAutoStart(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, vm.state.value)
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(1, client.hangUpCount)
        assertEquals(VoiceState.Idle, vm.state.value)
        // The screen's auto-start effect REFIRES when state returns to Idle — a deliberate end
        // must not become an instant redial.
        vm.onAutoStart(); scope.runCurrent()
        assertEquals(1, client.connects.size, "ending a call must never auto-redial it")
        assertEquals(VoiceState.Idle, vm.state.value)
        // A quick tap dials again — the deliberate way back in.
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(2, client.connects.size)
        assertEquals(VoiceState.Connecting, vm.state.value)
        scope.cancel()
    }

    @Test
    fun long_press_while_connecting_aborts_the_warmup() {
        // The stray-tap protection keeps a tap from killing a warm-up; the LONG-press is the
        // deliberate abort that must still work there.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onAutoStart(); scope.runCurrent()
        assertEquals(VoiceState.Connecting, vm.state.value)
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(1, client.hangUpCount)
        assertEquals(VoiceState.Idle, vm.state.value)
        vm.onAutoStart(); scope.runCurrent()
        assertEquals(1, client.connects.size, "an aborted warm-up must never auto-redial")
        scope.cancel()
    }

    @Test
    fun long_press_at_rest_is_a_no_op() {
        // Idle / Error: nothing to end — a long-press must fire nothing.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(0, client.hangUpCount)
        assertEquals(VoiceState.Idle, vm.state.value)
        vm.onMicPermissionDenied(); scope.runCurrent() // fail() tears the client down internally
        val teardownHangUps = client.hangUpCount
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(teardownHangUps, client.hangUpCount, "long-press on an error must fire nothing")
        assertIs<VoiceState.Error>(vm.state.value)
        scope.cancel()
    }

    @Test
    fun long_press_at_not_configured_is_a_no_op() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(VoiceDoorConfig("", "tok")))
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(0, client.hangUpCount)
        assertEquals(VoiceState.NotConfigured, vm.state.value)
        scope.cancel()
    }

    @Test
    fun fresh_screen_entry_rearms_auto_start_after_a_deliberate_end() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onScreenEntry(); vm.onAutoStart(); scope.runCurrent() // the app-open sequence
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(VoiceState.Idle, vm.state.value)
        assertEquals(1, client.connects.size)
        // Leaving the screen and coming back re-arms — a FRESH entry auto-dials again.
        vm.onScreenEntry(); vm.onAutoStart(); scope.runCurrent()
        assertEquals(2, client.connects.size, "a fresh screen entry re-arms auto-start")
        scope.cancel()
    }

    @Test
    fun reentry_during_a_live_call_then_ending_it_still_never_auto_redials() {
        // Navigate away mid-call and back (re-arm fires while the call is live), THEN end it:
        // hangUp must disarm regardless of the re-arm order, or the end becomes a redial.
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onScreenEntry(); vm.onAutoStart(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        vm.onScreenEntry() // back onto the screen while the call is up
        vm.onOrbLongPress(); scope.runCurrent()
        assertEquals(VoiceState.Idle, vm.state.value)
        vm.onAutoStart(); scope.runCurrent()
        assertEquals(1, client.connects.size, "re-entry mid-call must not turn a later end into a redial")
        scope.cancel()
    }

    @Test
    fun a_repeated_identical_door_never_disturbs_a_live_session() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val door = FakeVoiceDoorSource(configured)
        val vm = vmWith(scope, client, door)
        vm.onAutoStart(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, vm.state.value)
        door.emit(configured); door.emit(configured); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, vm.state.value)
        assertEquals(1, client.connects.size)
        scope.cancel()
    }

    // ---- transcript surface (text renders regardless of audio) ----

    @Test
    fun transcript_frames_accumulate_you_then_eve() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.UserTranscript("what's on today?"))
        client.emit(VoiceEvent.BotTranscript("You have a 3pm with Jamie."))
        client.emit(VoiceEvent.UserTranscript("   ")) // blank frame is never a bubble
        scope.runCurrent()
        assertEquals(
            listOf(
                LiveTranscriptLine(LiveTranscriptLine.Speaker.You, "what's on today?"),
                LiveTranscriptLine(LiveTranscriptLine.Speaker.Eve, "You have a 3pm with Jamie."),
            ),
            vm.transcript.value,
        )
        scope.cancel()
    }

    // ---- honest, named failure legs ----

    @Test
    fun connect_timeout_is_a_named_error() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val controller = LiveVoiceController(client, scope, connectTimeoutMs = 1_000)
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured), controller)
        vm.onOrbTap(); scope.runCurrent()
        assertEquals(VoiceState.Connecting, vm.state.value)
        scope.advanceTimeBy(1_001); scope.runCurrent()
        assertEquals(VoiceState.Error(WearLiveVoiceCopy.CONNECT_TIMED_OUT), vm.state.value)
        scope.cancel()
    }

    @Test
    fun think_timeout_is_a_named_error() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val controller = LiveVoiceController(client, scope, thinkTimeoutMs = 2_000)
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured), controller)
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); client.emit(VoiceEvent.BotThinking); scope.runCurrent()
        assertEquals(VoiceState.Thinking, vm.state.value)
        scope.advanceTimeBy(2_001); scope.runCurrent()
        assertEquals(VoiceState.Error(WearLiveVoiceCopy.THINK_TIMED_OUT), vm.state.value)
        scope.cancel()
    }

    @Test
    fun a_mid_session_drop_reconnects_with_backoff() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val controller = LiveVoiceController(client, scope, reconnectBaseDelayMs = 1_000)
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured), controller)
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(1, client.connects.size)
        client.emit(VoiceEvent.Dropped); scope.runCurrent()
        assertEquals(VoiceState.Reconnecting, vm.state.value)
        // The reconnect fires only after the backoff — not before (the storm-preventer).
        scope.advanceTimeBy(999); scope.runCurrent()
        assertEquals(1, client.connects.size)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(2, client.connects.size, "a bounded backoff reconnect re-dials the same door")
        scope.cancel()
    }

    @Test
    fun server_error_frame_is_a_named_error() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onOrbTap(); scope.runCurrent()
        client.emit(VoiceEvent.Failed(WearLiveVoiceCopy.serverError("STT is down"))); scope.runCurrent()
        val s = vm.state.value
        assertIs<VoiceState.Error>(s)
        assertTrue(s.message.contains("STT is down"))
        scope.cancel()
    }

    @Test
    fun mic_permission_denied_is_a_named_error() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.onMicPermissionDenied(); scope.runCurrent()
        assertEquals(VoiceState.Error(WearLiveVoiceCopy.MIC_UNAVAILABLE), vm.state.value)
        scope.cancel()
    }

    @Test
    fun mute_toggles_the_client_and_controls() {
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        vm.toggleMute(); scope.runCurrent()
        assertEquals(true, client.lastMuted)
        assertEquals(true, vm.controls.value.micMuted)
        scope.cancel()
    }

    @Test
    fun bot_level_surfaces_the_clients_real_speaking_amplitude() {
        // The ring's Speaking pulse is REAL now: the client publishes a smoothed RMS of EVE's
        // downlink PCM and the ViewModel exposes it verbatim (no copy, no synthetic stand-in).
        val scope = TestScope()
        val client = FakeWsVoiceClient()
        val vm = vmWith(scope, client, FakeVoiceDoorSource(configured))
        assertEquals(0f, vm.botLevel.value)
        client.setLevel(0.62f)
        assertEquals(0.62f, vm.botLevel.value)
        scope.cancel()
    }
}
