package app.eve.ui.components

import androidx.compose.ui.graphics.Color
import app.eve.voice.VoiceState

/**
 * Per-state speaking colors for the Talk orb, ported verbatim from the EVE desktop "stage" so the
 * native app reads the conversation the same way: each state has its own hue instead of one teal.
 * Kept as a pure function (no Compose runtime) so the mapping is unit-tested directly.
 */
object VoiceOrbPalette {
    val Idle = Color(0xFF2DD4BF) // teal — calm standby
    val Connecting = Color(0xFF6B7280) // slate — connecting / reconnecting
    val Listening = Color(0xFF38BDF8) // sky blue — your turn / hearing you
    val Thinking = Color(0xFFC084FC) // purple — composing a reply
    val Speaking = Color(0xFFF59E0B) // amber — EVE talking
    val Working = Color(0xFF34D399) // green — running a tool / delegating (matches desktop "working")
    val NoAudio = Color(0xFFFBBF24) // warning amber — connected but silent
    val Error = Color(0xFFF87171) // red — failure
}

/** Pure VoiceState → orb core color. */
fun orbStateColor(state: VoiceState): Color = when (state) {
    VoiceState.Idle -> VoiceOrbPalette.Idle
    VoiceState.Connecting, VoiceState.Reconnecting -> VoiceOrbPalette.Connecting
    VoiceState.YourTurn, is VoiceState.Hearing -> VoiceOrbPalette.Listening
    VoiceState.Thinking -> VoiceOrbPalette.Thinking
    VoiceState.Speaking -> VoiceOrbPalette.Speaking
    VoiceState.NoAudio -> VoiceOrbPalette.NoAudio
    is VoiceState.Error -> VoiceOrbPalette.Error
}
