package app.eve.wear.talk

import kotlinx.coroutines.flow.StateFlow

/**
 * Plays EVE's reply audio — raw 16 kHz mono PCM16 from the native voice turn — on the wrist speaker.
 * Seam over AudioTrack so the talk VM depends on an interface (fakeable, manual DI, no mocking
 * library) rather than the Android engine; the real impl ([AudioTrackPcmPlayer]) stays thin.
 *
 * Contract — voice failure NEVER hides the text: [play] is fire-and-forget; the reply text is always
 * rendered by the screen regardless of [state]. [state] surfaces the honest playback condition
 * (speaking / failed) as a SMALL secondary note only. A playback failure is a NAMED
 * [VoiceState.Failed] (never a silent swallow).
 */
interface PcmPlayer {
    /** The honest playback condition, mapped to copy in [WearTalkCopy]. Reuses the TTS [VoiceState]. */
    val state: StateFlow<VoiceState>

    /** Play one reply's raw PCM at [sampleRate] Hz (mono, 16-bit). Interrupts any current playback. */
    fun play(pcm: ByteArray, sampleRate: Int)

    /** Stop any current playback (e.g. a new turn started). Not a failure — resets to Idle. */
    fun stop()

    /** Release the engine when the talk screen is disposed. */
    fun release()
}
