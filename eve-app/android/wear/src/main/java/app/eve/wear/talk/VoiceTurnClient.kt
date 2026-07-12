package app.eve.wear.talk

import app.eve.data.wear.VoiceTurnReply
import app.eve.data.wear.VoiceTurnRequest

/**
 * Seam over the ONE bidirectional voice-turn channel (ChannelClient). The watch opens a channel to
 * the phone gateway node ([app.eve.data.wear.WearLink.PATH_VOICE_TURN]), writes the len-prefixed
 * [VoiceTurnRequest] envelope + the recorded WAV, then reads the phone's [VoiceTurnReply] envelope +
 * raw PCM back on the same channel. Fakeable in tests (manual DI, no mocking library); the GMS impl
 * ([GmsVoiceTurnClient]) finds the gateway node via CapabilityClient and does the raw stream I/O.
 *
 * House rule — no silent fallback: [runTurn] returns a [VoiceTurnOutcome] naming exactly what
 * happened — [VoiceTurnOutcome.NoGatewayNode] (the Data-Layer-down leg), [VoiceTurnOutcome.SendFailed]
 * (channel open / write broke), [VoiceTurnOutcome.NoReply] (opened + sent, but no usable reply came
 * back), or [VoiceTurnOutcome.Replied] with the reply + raw PCM. Never a fake reply.
 */
interface VoiceTurnClient {
    /**
     * Run one full turn on a fresh channel. [onSent] is invoked AFTER the request envelope + WAV have
     * been written and BEFORE the reply is awaited, so the VM can move to "EVE is thinking…" at the
     * honest moment. The overall channel-await timeout is owned by the VM (it wraps this call).
     */
    suspend fun runTurn(request: VoiceTurnRequest, wav: ByteArray, onSent: () -> Unit): VoiceTurnOutcome
}

/** The honest outcome of one channel turn — never a fake reply when a leg is down. */
sealed interface VoiceTurnOutcome {
    /** The phone replied. [reply] is the envelope; [pcm] is her raw 16 kHz mono PCM16 (may be empty on a voice_error/failure leg). */
    data class Replied(val reply: VoiceTurnReply, val pcm: ByteArray) : VoiceTurnOutcome {
        override fun equals(other: Any?): Boolean = other is Replied && reply == other.reply && pcm.contentEquals(other.pcm)
        override fun hashCode(): Int = reply.hashCode() * 31 + pcm.contentHashCode()
    }

    /** No reachable node advertises the gateway capability — the watch<->phone Data Layer leg is down. */
    data object NoGatewayNode : VoiceTurnOutcome

    /** The gateway node was found but opening/writing the channel failed. [reason] is the real detail. */
    data class SendFailed(val reason: String) : VoiceTurnOutcome

    /** The request left the watch but no usable reply came back (channel closed early / decode failed). */
    data class NoReply(val reason: String) : VoiceTurnOutcome
}
