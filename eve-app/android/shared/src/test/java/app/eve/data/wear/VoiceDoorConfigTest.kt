package app.eve.data.wear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trips the live-voice [VoiceDoorConfig] over the SAME EveWireJson bytes both sides use, proves
 * the "blank URL = not configured" honesty signal survives the wire, and that garbage bytes fail
 * LOUDLY (never a fake decode into a bogus door).
 */
class VoiceDoorConfigTest {

    @Test
    fun voice_door_config_roundtrips() {
        val original = VoiceDoorConfig(wsUrl = "wss://eve-voice.example.ts.net/v1/watch/voice", token = "sekret-bearer")
        val back = VoiceDoorConfig.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals("wss://eve-voice.example.ts.net/v1/watch/voice", back.wsUrl)
        assertEquals("sekret-bearer", back.token)
        assertTrue(back.isConfigured)
    }

    @Test
    fun blank_url_roundtrips_as_not_configured() {
        // The honest "not set yet" signal: a written config whose URL is blank. The token may still be
        // present (the phone always has one) — configured is decided by the URL alone.
        val original = VoiceDoorConfig(wsUrl = "", token = "tok")
        val back = VoiceDoorConfig.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertFalse(back.isConfigured)
    }

    @Test
    fun wire_carries_both_fields() {
        val text = String(VoiceDoorConfig("wss://d/x", "t").toBytes(), Charsets.UTF_8)
        assertTrue(text.contains("wsUrl"), "wsUrl must be on the wire: $text")
        assertTrue(text.contains("token"), "token must be on the wire: $text")
    }

    @Test
    fun garbage_bytes_fail_loudly_not_a_fake_door() {
        assertFailsWith<Exception> { VoiceDoorConfig.fromBytes("nope".toByteArray()) }
        assertFailsWith<Exception> { VoiceDoorConfig.fromBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03)) }
    }
}
