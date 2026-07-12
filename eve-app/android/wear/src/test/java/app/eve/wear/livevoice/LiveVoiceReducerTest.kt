package app.eve.wear.livevoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pure guard on the ported live-voice [reduce] machine — the conversation loop, the honest NoAudio /
 * NotConfigured branches, HangUp/Failed from anywhere, and the rule that transcript frames NEVER move
 * the machine (reply/state text is orthogonal to the conversation state).
 */
class LiveVoiceReducerTest {

    @Test fun full_conversation_loop() {
        var s: VoiceState = VoiceState.Idle
        s = reduce(s, VoiceEvent.StartRequested); assertEquals(VoiceState.Connecting, s)
        s = reduce(s, VoiceEvent.IceConnected); assertEquals(VoiceState.YourTurn, s)
        s = reduce(s, VoiceEvent.VadUserStart); assertIs<VoiceState.Hearing>(s)
        s = reduce(s, VoiceEvent.VadUserEnd); assertEquals(VoiceState.Thinking, s)
        s = reduce(s, VoiceEvent.BotSpeaking); assertEquals(VoiceState.Speaking, s)
        s = reduce(s, VoiceEvent.BotDone); assertEquals(VoiceState.YourTurn, s)
    }

    @Test fun server_state_loop_via_bot_thinking_and_idle() {
        // The server drives thinking->idle directly; idle closes the loop back to YourTurn.
        var s: VoiceState = VoiceState.YourTurn
        s = reduce(s, VoiceEvent.BotThinking); assertEquals(VoiceState.Thinking, s)
        s = reduce(s, VoiceEvent.BotDone); assertEquals(VoiceState.YourTurn, s)
    }

    @Test fun hangup_from_anywhere_returns_idle() {
        assertEquals(VoiceState.Idle, reduce(VoiceState.Speaking, VoiceEvent.HangUp))
        assertEquals(VoiceState.Idle, reduce(VoiceState.Thinking, VoiceEvent.HangUp))
        assertEquals(VoiceState.Idle, reduce(VoiceState.NotConfigured, VoiceEvent.HangUp))
    }

    @Test fun failed_from_anywhere_is_terminal_error() {
        val s = reduce(VoiceState.Thinking, VoiceEvent.Failed("boom"))
        assertEquals(VoiceState.Error("boom"), s)
    }

    @Test fun interrupt_while_speaking_returns_your_turn() {
        assertEquals(VoiceState.YourTurn, reduce(VoiceState.Speaking, VoiceEvent.Interrupt))
    }

    @Test fun no_audio_is_not_a_sink() {
        assertEquals(VoiceState.Speaking, reduce(VoiceState.NoAudio, VoiceEvent.MediaFlowing))
        assertEquals(VoiceState.YourTurn, reduce(VoiceState.NoAudio, VoiceEvent.BotDone))
    }

    @Test fun not_configured_can_start_but_holds_on_other_events() {
        assertEquals(VoiceState.Connecting, reduce(VoiceState.NotConfigured, VoiceEvent.StartRequested))
        val held: VoiceState = VoiceState.NotConfigured
        assertSame(held, reduce(held, VoiceEvent.BotSpeaking))
    }

    @Test fun error_retries_on_start() {
        assertEquals(VoiceState.Connecting, reduce(VoiceState.Error("x"), VoiceEvent.StartRequested))
    }

    @Test fun transcript_frames_never_move_the_machine() {
        val s: VoiceState = VoiceState.Thinking
        assertSame(s, reduce(s, VoiceEvent.UserTranscript("hi")))
        assertSame(s, reduce(s, VoiceEvent.BotTranscript("hello")))
    }

    @Test fun dropped_mid_session_goes_reconnecting() {
        assertEquals(VoiceState.Reconnecting, reduce(VoiceState.Speaking, VoiceEvent.Dropped))
        assertEquals(VoiceState.Reconnecting, reduce(VoiceState.Connecting, VoiceEvent.Dropped))
    }

    @Test fun stray_event_holds_current_state() {
        val s: VoiceState = VoiceState.YourTurn
        assertSame(s, reduce(s, VoiceEvent.IceConnected))
    }
}
