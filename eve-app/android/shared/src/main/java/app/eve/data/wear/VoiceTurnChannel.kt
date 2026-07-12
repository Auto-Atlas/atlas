package app.eve.data.wear

import app.eve.data.EveWireJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The v2 NATIVE voice-turn channel contract, shared VERBATIM by the phone (:app relay) and the watch
 * (:wear client) so the two can never drift on the envelope shape or the framing. One
 * bidirectional ChannelClient stream ([WearLink.PATH_VOICE_TURN]) carries, in order:
 *
 *   [len-prefixed [VoiceTurnRequest] envelope] [raw WAV bytes]           (watch -> phone)
 *   [len-prefixed [VoiceTurnReply]   envelope] [raw PCM16 bytes]         (phone -> watch)
 *
 * The length prefix ([VoiceEnvelope]) is what lets the reader consume EXACTLY the JSON envelope and
 * then read the remaining stream as the raw audio payload — the audio is never parsed as JSON and the
 * JSON is never guessed at by scanning for a brace.
 */

/**
 * Watch->phone: the opening envelope of one voice turn. Only the watch-generated correlation
 * [requestId] rides in JSON; the recorded audio follows as raw WAV bytes on the same channel (never
 * base64 on the wire — the phone base64-encodes it only for the HTTP leg to approval_api).
 */
@Serializable
data class VoiceTurnRequest(
    val requestId: String,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): VoiceTurnRequest = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Phone->watch: the reply envelope of one voice turn, written back on the SAME channel and followed
 * by [pcmByteCount] raw PCM16 bytes (the WAV header is stripped phone-side so the watch never parses
 * RIFF). The honest, named-leg contract mirrors [TalkReply]:
 *  - [outcome] == [Outcome.OK]: [transcript] (what Atlas heard) and [reply] (the answer text) are both
 *    present; audio MAY still be absent ([pcmByteCount] == 0) when TTS failed — then [voiceError]
 *    names the leg and the reply TEXT is still delivered (the answer must reach the wrist even when
 *    her voice can't).
 *  - a blank [transcript] with a non-OK outcome is the server's "no speech recognized" (HTTP 422)
 *    signal — the watch renders the "Didn't catch that" copy.
 *  - any other leg: [outcome] is the named failure and [detail] carries the real reason; [reply] and
 *    [transcript] are null and no audio follows.
 *
 * [voiceError] is a VISIBLE note, NEVER a swap to a different voice: the one canonical voice or an
 * honest "text only".
 */
@Serializable
data class VoiceTurnReply(
    val requestId: String,
    val transcript: String? = null,
    val reply: String? = null,
    val voiceError: String? = null,
    val outcome: Outcome,
    val detail: String? = null,
    val sampleRate: Int = VoiceEnvelope.DEFAULT_SAMPLE_RATE,
    val pcmByteCount: Int = 0,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): VoiceTurnReply = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Length-prefixed JSON envelope framing for the voice-turn channel. Every envelope is written as a
 * 4-byte big-endian unsigned length followed by exactly that many UTF-8 JSON bytes ([EveWireJson],
 * the one canonical wire Json). The raw audio payload then follows on the same stream — the caller
 * reads it directly once the envelope has been consumed.
 *
 * House rule — fail LOUDLY, never a fake decode: a truncated stream, a negative/oversized length, or
 * garbage JSON all THROW (an [EOFException]/[IOException] the caller turns into a named failure); a
 * frame is never partially accepted and audio bytes are never mistaken for an envelope.
 */
object VoiceEnvelope {

    /** The canonical 16 kHz mono PCM16 rate both engines use for the turn. */
    const val DEFAULT_SAMPLE_RATE = 16_000

    /** Big-endian length prefix width, in bytes. */
    const val LENGTH_PREFIX_BYTES = 4

    /**
     * Hard cap on a single envelope's JSON size. Envelopes are tiny (ids + a reply string capped
     * elsewhere); a length prefix claiming more than this is corruption, refused loudly rather than
     * used to allocate an enormous buffer.
     */
    const val MAX_ENVELOPE_BYTES = 1 shl 20 // 1 MiB

    /** Frame one value into `[4-byte BE length][utf8 json]` bytes (no audio). */
    fun <T> frame(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val json = EveWireJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        val out = ByteArray(LENGTH_PREFIX_BYTES + json.size)
        writeLengthPrefix(out, json.size)
        json.copyInto(out, LENGTH_PREFIX_BYTES)
        return out
    }

    /** Write one framed envelope to [out] (does NOT flush — the caller writes audio next, then flushes). */
    fun <T> write(out: OutputStream, serializer: SerializationStrategy<T>, value: T) {
        out.write(frame(serializer, value))
    }

    /**
     * Read exactly one framed envelope from [input], leaving the stream positioned at the first audio
     * byte. Throws on a truncated stream or a length that is negative or exceeds [MAX_ENVELOPE_BYTES].
     */
    fun <T> read(input: InputStream, deserializer: DeserializationStrategy<T>): T =
        EveWireJson.decodeFromString(deserializer, String(readFrame(input), Charsets.UTF_8))

    /**
     * Read one framed envelope's RAW JSON bytes (length prefix consumed, stream left at the first
     * audio byte) WITHOUT decoding. The relay uses this so a JSON that frames cleanly but decodes to
     * the wrong shape can still have its correlation id recovered for an honest error reply — the same
     * malformed-payload recovery the Message bridge does. A truncated/oversized frame still throws.
     */
    fun readFrame(input: InputStream): ByteArray {
        val len = readLengthPrefix(input)
        if (len < 0) throw IOException("voice envelope length is negative ($len)")
        if (len > MAX_ENVELOPE_BYTES) throw IOException("voice envelope length $len exceeds cap $MAX_ENVELOPE_BYTES")
        return readExactly(input, len)
    }

    private fun writeLengthPrefix(dst: ByteArray, len: Int) {
        dst[0] = ((len ushr 24) and 0xFF).toByte()
        dst[1] = ((len ushr 16) and 0xFF).toByte()
        dst[2] = ((len ushr 8) and 0xFF).toByte()
        dst[3] = (len and 0xFF).toByte()
    }

    private fun readLengthPrefix(input: InputStream): Int {
        val p = readExactly(input, LENGTH_PREFIX_BYTES)
        return ((p[0].toInt() and 0xFF) shl 24) or
            ((p[1].toInt() and 0xFF) shl 16) or
            ((p[2].toInt() and 0xFF) shl 8) or
            (p[3].toInt() and 0xFF)
    }

    /** Read exactly [n] bytes or throw [EOFException] — a short read is a broken frame, never accepted. */
    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException("voice envelope truncated: wanted $n bytes, got $read")
            read += r
        }
        return buf
    }
}
