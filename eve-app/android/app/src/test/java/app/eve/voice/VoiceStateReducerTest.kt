package app.eve.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceStateReducerTest {
    @Test
    fun start_then_ice_to_your_turn() {
        assertEquals(VoiceState.Connecting, reduce(VoiceState.Idle, VoiceEvent.StartRequested))
        assertEquals(VoiceState.YourTurn, reduce(VoiceState.Connecting, VoiceEvent.IceConnected))
    }

    @Test
    fun hearing_vad_end_thinking_speaking_back_to_your_turn() {
        assertTrue(reduce(VoiceState.YourTurn, VoiceEvent.VadUserStart) is VoiceState.Hearing)
        assertEquals(VoiceState.Thinking, reduce(VoiceState.Hearing(0.4f), VoiceEvent.VadUserEnd))
        assertEquals(VoiceState.Speaking, reduce(VoiceState.Thinking, VoiceEvent.BotSpeaking))
        assertEquals(VoiceState.YourTurn, reduce(VoiceState.Speaking, VoiceEvent.BotDone)) // loop closed
    }

    @Test
    fun interrupt_during_speaking_returns_floor() =
        assertEquals(VoiceState.YourTurn, reduce(VoiceState.Speaking, VoiceEvent.Interrupt))

    @Test
    fun media_stalled_is_honest_no_audio() =
        assertEquals(VoiceState.NoAudio, reduce(VoiceState.Speaking, VoiceEvent.MediaStalled))

    @Test
    fun no_audio_recovers_when_media_resumes() = // BMAD: Winston — NoAudio not a sink
        assertEquals(VoiceState.Speaking, reduce(VoiceState.NoAudio, VoiceEvent.MediaFlowing))

    @Test
    fun reconnecting_recovers_or_errors() { // BMAD: Winston — Reconnecting exits
        assertEquals(VoiceState.YourTurn, reduce(VoiceState.Reconnecting, VoiceEvent.IceConnected))
        assertTrue(reduce(VoiceState.Reconnecting, VoiceEvent.Failed("x")) is VoiceState.Error)
    }

    @Test
    fun drop_goes_reconnecting() =
        assertEquals(VoiceState.Reconnecting, reduce(VoiceState.Speaking, VoiceEvent.Dropped))

    @Test
    fun hangup_from_any_goes_idle() =
        assertEquals(VoiceState.Idle, reduce(VoiceState.Speaking, VoiceEvent.HangUp))
}
