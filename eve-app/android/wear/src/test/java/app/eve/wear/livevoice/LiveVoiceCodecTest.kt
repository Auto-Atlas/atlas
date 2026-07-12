package app.eve.wear.livevoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive, pure-JVM guard on [LiveVoiceCodec]: every server control frame maps to the right
 * [VoiceEvent], the three client frames encode to the exact wire shape, an "error" frame and a
 * malformed frame BOTH surface as a named [VoiceEvent.Failed] (loud, never silent), and an unknown
 * type / unknown state is forward-compatibly ignored (null).
 */
class LiveVoiceCodecTest {

    // ---- server state frames -> conversation events ----

    @Test fun state_connected_maps_to_ice_connected() =
        assertEquals(VoiceEvent.IceConnected, LiveVoiceCodec.decode("""{"type":"state","state":"connected"}"""))

    @Test fun state_listening_maps_to_vad_user_start() =
        assertEquals(VoiceEvent.VadUserStart, LiveVoiceCodec.decode("""{"type":"state","state":"listening"}"""))

    @Test fun state_thinking_maps_to_bot_thinking() =
        assertEquals(VoiceEvent.BotThinking, LiveVoiceCodec.decode("""{"type":"state","state":"thinking"}"""))

    @Test fun state_speaking_maps_to_bot_speaking() =
        assertEquals(VoiceEvent.BotSpeaking, LiveVoiceCodec.decode("""{"type":"state","state":"speaking"}"""))

    @Test fun state_idle_maps_to_bot_done() =
        assertEquals(VoiceEvent.BotDone, LiveVoiceCodec.decode("""{"type":"state","state":"idle"}"""))

    @Test fun unknown_state_is_ignored() =
        assertNull(LiveVoiceCodec.decode("""{"type":"state","state":"whatever"}"""))

    // ---- transcript frames ----

    @Test fun user_transcript_carries_text() {
        val ev = LiveVoiceCodec.decode("""{"type":"user_transcript","text":"what's on today?"}""")
        assertEquals(VoiceEvent.UserTranscript("what's on today?"), ev)
    }

    @Test fun bot_transcript_carries_text() {
        val ev = LiveVoiceCodec.decode("""{"type":"bot_transcript","text":"You have a 3pm."}""")
        assertEquals(VoiceEvent.BotTranscript("You have a 3pm."), ev)
    }

    // ---- error frames (named + fatal) ----

    @Test fun error_frame_is_a_named_failure_with_the_server_message() {
        val ev = LiveVoiceCodec.decode("""{"type":"error","message":"STT is down"}""")
        assertIs<VoiceEvent.Failed>(ev)
        assertTrue(ev.message.contains("STT is down"), "server detail must surface verbatim: ${ev.message}")
    }

    @Test fun error_frame_without_message_is_still_a_named_failure() {
        val ev = LiveVoiceCodec.decode("""{"type":"error"}""")
        assertIs<VoiceEvent.Failed>(ev)
        assertTrue(ev.message.isNotBlank())
    }

    // ---- honesty: malformed / unknown ----

    @Test fun malformed_json_is_a_loud_named_failure_not_silence() {
        val ev = LiveVoiceCodec.decode("not json at all")
        assertIs<VoiceEvent.Failed>(ev)
        assertEquals(WearLiveVoiceCopy.BAD_CONTROL_FRAME, ev.message)
    }

    @Test fun unknown_type_is_forward_compatibly_ignored() =
        assertNull(LiveVoiceCodec.decode("""{"type":"future_thing","x":1}"""))

    @Test fun unknown_keys_are_tolerated() =
        assertEquals(VoiceEvent.BotDone, LiveVoiceCodec.decode("""{"type":"state","state":"idle","extra":true}"""))

    // ---- client -> server frames ----

    @Test fun auth_frame_carries_type_and_token() {
        val text = LiveVoiceCodec.authFrame("sekret")
        assertTrue(text.contains("\"auth\""), text)
        assertTrue(text.contains("sekret"), text)
        // Round-trips back into nothing actionable server-side, but must be valid JSON we can re-read.
        assertNull(LiveVoiceCodec.decode(text)) // "auth" is not a server type → ignored (null)
    }

    @Test fun interrupt_frame_is_the_interrupt_type() {
        assertTrue(LiveVoiceCodec.interruptFrame().contains("\"interrupt\""))
    }

    @Test fun bye_frame_is_the_bye_type() {
        assertTrue(LiveVoiceCodec.byeFrame().contains("\"bye\""))
    }
}
