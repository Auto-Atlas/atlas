package app.eve.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Controller tests via the [VoiceClient] seam (the blessed FakeRepo pattern — NOT a mock of the
 * SUT; it's a synthetic event source so the controller's reducer-folding, timeouts and reconnect
 * backoff are exercised on the JVM without the native .so). The controller is given a standalone
 * TestScope and driven with runCurrent()/advanceTimeBy(); the scope is cancelled at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {

    private class FakeVoiceClient : VoiceClient {
        val emitted = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
        override val events: Flow<VoiceEvent> = emitted
        var connectCalls = 0
        var hangUpCalls = 0
        var interruptCalls = 0
        var lastMuted: Boolean? = null
        var lastSpeakerphone: Boolean? = null
        var muteCalls = 0
        var speakerphoneCalls = 0
        override suspend fun connect() { connectCalls++ }
        override fun hangUp() { hangUpCalls++ }
        override fun interrupt() { interruptCalls++ }
        override fun setMicMuted(muted: Boolean) { muteCalls++; lastMuted = muted }
        override fun setSpeakerphone(on: Boolean) { speakerphoneCalls++; lastSpeakerphone = on }
        fun push(e: VoiceEvent) = emitted.tryEmit(e)
    }

    @Test
    fun toggleMute_flipsControls_andDrivesClient() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope)
        scope.runCurrent()

        assertEquals(VoiceControls(), c.controls.value) // default: not muted, speaker on

        c.toggleMute()
        scope.runCurrent()
        assertTrue(c.controls.value.micMuted)
        assertEquals(true, client.lastMuted)

        c.toggleMute()
        scope.runCurrent()
        assertTrue(!c.controls.value.micMuted)
        assertEquals(false, client.lastMuted)
        scope.cancel()
    }

    @Test
    fun toggleSpeakerphone_flipsControls_andDrivesClient() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope)
        scope.runCurrent()

        c.toggleSpeakerphone() // default on → off
        scope.runCurrent()
        assertTrue(!c.controls.value.speakerphoneOn)
        assertEquals(false, client.lastSpeakerphone)
        scope.cancel()
    }

    @Test
    fun controls_reassertedOnConnect_soMuteSurvivesReconnect() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope)
        scope.runCurrent()

        c.toggleMute()           // user mutes before/around connect
        scope.runCurrent()
        val callsBefore = client.muteCalls

        c.start()
        scope.runCurrent()
        client.push(VoiceEvent.IceConnected) // Connecting → YourTurn triggers re-assert
        scope.runCurrent()

        assertEquals(VoiceState.YourTurn, c.state.value)
        assertTrue(client.muteCalls > callsBefore) // controls re-applied to the freshly-built track
        assertEquals(true, client.lastMuted)
        scope.cancel()
    }

    @Test
    fun start_then_ice_drives_connecting_to_your_turn() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope)
        scope.runCurrent()

        c.start()
        scope.runCurrent()
        assertEquals(VoiceState.Connecting, c.state.value)
        assertEquals(1, client.connectCalls)

        client.push(VoiceEvent.IceConnected)
        scope.runCurrent()
        assertEquals(VoiceState.YourTurn, c.state.value)
        scope.cancel()
    }

    @Test
    fun connect_timeout_errors_after_threshold() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope, connectTimeoutMs = 15_000)
        scope.runCurrent()

        c.start()
        scope.runCurrent()
        assertEquals(VoiceState.Connecting, c.state.value)

        // Just before the threshold: still connecting (must tolerate multi-second handshakes).
        scope.advanceTimeBy(14_000)
        scope.runCurrent()
        assertEquals(VoiceState.Connecting, c.state.value)

        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertIs<VoiceState.Error>(c.state.value)
        scope.cancel()
    }

    @Test
    fun drop_reconnects_with_backoff_then_recovers() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope, reconnectBaseDelayMs = 1_000)
        scope.runCurrent()

        c.start(); scope.runCurrent()
        client.push(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, c.state.value)
        val connectsBeforeDrop = client.connectCalls

        client.push(VoiceEvent.Dropped); scope.runCurrent()
        assertEquals(VoiceState.Reconnecting, c.state.value)
        // Backoff hasn't elapsed yet → no fresh connect.
        assertEquals(connectsBeforeDrop, client.connectCalls)

        scope.advanceTimeBy(1_100); scope.runCurrent()
        assertEquals(connectsBeforeDrop + 1, client.connectCalls, "backoff fires a fresh offer")

        client.push(VoiceEvent.IceConnected); scope.runCurrent()
        assertEquals(VoiceState.YourTurn, c.state.value)
        scope.cancel()
    }

    @Test
    fun think_timeout_surfaces_not_responding() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope, thinkTimeoutMs = 20_000)
        scope.runCurrent()

        c.start(); scope.runCurrent()
        client.push(VoiceEvent.IceConnected); scope.runCurrent()
        client.push(VoiceEvent.VadUserStart); scope.runCurrent()
        client.push(VoiceEvent.VadUserEnd); scope.runCurrent()
        assertEquals(VoiceState.Thinking, c.state.value)

        scope.advanceTimeBy(21_000); scope.runCurrent()
        val err = assertIs<VoiceState.Error>(c.state.value)
        assertTrue(err.message.contains("isn't responding"), err.message)
        scope.cancel()
    }

    @Test
    fun hangup_returns_to_idle_and_tears_down_client() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope)
        scope.runCurrent()

        c.start(); scope.runCurrent()
        client.push(VoiceEvent.IceConnected); scope.runCurrent()
        c.hangUp(); scope.runCurrent()
        assertEquals(VoiceState.Idle, c.state.value)
        assertEquals(1, client.hangUpCalls)
        scope.cancel()
    }

    @Test
    fun interrupt_only_acts_while_speaking() {
        val scope = TestScope()
        val client = FakeVoiceClient()
        val c = VoiceController(client, scope)
        scope.runCurrent()

        c.start(); scope.runCurrent()
        client.push(VoiceEvent.IceConnected); scope.runCurrent()
        // YourTurn → interrupt is a no-op.
        c.interrupt(); scope.runCurrent()
        assertEquals(0, client.interruptCalls)

        client.push(VoiceEvent.BotSpeaking); scope.runCurrent()
        assertEquals(VoiceState.Speaking, c.state.value)
        c.interrupt(); scope.runCurrent()
        assertEquals(1, client.interruptCalls)
        assertEquals(VoiceState.YourTurn, c.state.value)
        scope.cancel()
    }
}
