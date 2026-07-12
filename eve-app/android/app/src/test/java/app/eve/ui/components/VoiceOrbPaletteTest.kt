package app.eve.ui.components

import androidx.compose.ui.graphics.Color
import app.eve.voice.VoiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The Talk orb must read its conversation state through COLOR — the per-state speaking palette
 * ported from the Atlas desktop stage (idle teal, listening sky-blue, thinking purple, speaking
 * amber, problems red), not a single teal for everything. Pure mapping, JVM-testable.
 */
class VoiceOrbPaletteTest {

    @Test
    fun each_state_maps_to_its_speaking_color() {
        assertEquals(Color(0xFF2DD4BF), orbStateColor(VoiceState.Idle))
        assertEquals(Color(0xFF6B7280), orbStateColor(VoiceState.Connecting))
        assertEquals(Color(0xFF6B7280), orbStateColor(VoiceState.Reconnecting))
        assertEquals(Color(0xFF38BDF8), orbStateColor(VoiceState.YourTurn))
        assertEquals(Color(0xFF38BDF8), orbStateColor(VoiceState.Hearing(0.5f)))
        assertEquals(Color(0xFFC084FC), orbStateColor(VoiceState.Thinking))
        assertEquals(Color(0xFFF59E0B), orbStateColor(VoiceState.Speaking))
        assertEquals(Color(0xFFFBBF24), orbStateColor(VoiceState.NoAudio))
        assertEquals(Color(0xFFF87171), orbStateColor(VoiceState.Error("boom")))
    }

    @Test
    fun conversation_states_are_visually_distinct() {
        // The whole point of the change: listening / thinking / speaking are NOT the same color.
        val listening = orbStateColor(VoiceState.YourTurn)
        val thinking = orbStateColor(VoiceState.Thinking)
        val speaking = orbStateColor(VoiceState.Speaking)
        assertNotEquals(listening, thinking)
        assertNotEquals(thinking, speaking)
        assertNotEquals(listening, speaking)
    }
}
