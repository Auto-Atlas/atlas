package app.eve.wear.talk

import kotlinx.coroutines.flow.StateFlow

/**
 * Speaks EVE's reply on the wrist. Seam over the on-watch TextToSpeech so the talk screen depends on
 * an interface (fakeable, manual DI — no mocking library) rather than the Android engine directly.
 *
 * Contract — voice failure NEVER hides the text: [speak] is fire-and-forget; the reply text is always
 * rendered by the screen regardless of [state]. [state] surfaces the honest voice condition
 * (warming/speaking/failed) as a SMALL secondary note only.
 */
interface ReplySpeaker {
    /** The honest voice-output condition, mapped to copy in [WearTalkCopy]. */
    val state: StateFlow<VoiceState>

    /** Warm the engine on talk-screen entry (Wear cold-boot TTS can take ~10s). Idempotent. */
    fun prewarm()

    /** Speak one reply. If the engine is still warming, the text is queued and spoken once ready. */
    fun speak(text: String)

    /** Release the engine when the talk screen is disposed. */
    fun shutdown()
}

/** The honest voice-output states. Text always carries the reply; these drive a small note only. */
sealed interface VoiceState {
    /** Nothing spoken yet / finished cleanly. */
    data object Idle : VoiceState

    /** The engine is initializing; a requested reply is queued ("warming up voice…"). */
    data object WarmingUp : VoiceState

    /** Actively speaking a reply. */
    data object Speaking : VoiceState

    /** Voice output failed — [message] is the honest note (the reply text is still shown above). */
    data class Failed(val message: String) : VoiceState
}
