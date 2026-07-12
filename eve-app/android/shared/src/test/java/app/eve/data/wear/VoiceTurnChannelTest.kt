package app.eve.data.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive round-trip + garbage guard on the voice-turn envelope framing ([VoiceEnvelope]) and its
 * DTOs. Proves the length prefix lets a reader consume EXACTLY the JSON and leave the trailing audio
 * bytes untouched (both directions), and that every corrupt frame fails LOUDLY (never a fake decode).
 */
class VoiceTurnChannelTest {

    @Test
    fun request_envelope_roundtrips() {
        val original = VoiceTurnRequest(requestId = "vt-1")
        assertEquals(original, VoiceTurnRequest.fromBytes(original.toBytes()))
    }

    @Test
    fun reply_envelope_roundtrips_ok_leg_with_audio() {
        val original = VoiceTurnReply(
            requestId = "vt-1",
            transcript = "what's on today?",
            reply = "You have a 3pm with Jamie.",
            voiceError = null,
            outcome = Outcome.OK,
            sampleRate = 16_000,
            pcmByteCount = 32_000,
        )
        val back = VoiceTurnReply.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals(Outcome.OK, back.outcome)
        assertEquals(32_000, back.pcmByteCount)
        assertNull(back.detail)
    }

    @Test
    fun reply_envelope_roundtrips_voice_error_leg_text_without_audio() {
        // TTS failed: reply text still present, no audio, voiceError names the leg.
        val original = VoiceTurnReply(
            requestId = "vt-2",
            transcript = "remind me at five",
            reply = "Reminder set for 5pm.",
            voiceError = "tts unavailable",
            outcome = Outcome.OK,
            pcmByteCount = 0,
        )
        val back = VoiceTurnReply.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals("tts unavailable", back.voiceError)
        assertEquals(0, back.pcmByteCount)
    }

    @Test
    fun reply_envelope_roundtrips_failure_leg_with_null_reply() {
        val original = VoiceTurnReply(
            requestId = "vt-3",
            outcome = Outcome.SERVER_UNREACHABLE,
            detail = "connection refused",
        )
        val back = VoiceTurnReply.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertNull(back.reply)
        assertNull(back.transcript)
        assertEquals("connection refused", back.detail)
    }

    @Test
    fun framed_request_then_wav_reads_envelope_and_leaves_audio_untouched() {
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 9, 8, 7, 6) // arbitrary trailing bytes
        val out = ByteArrayOutputStream()
        VoiceEnvelope.write(out, VoiceTurnRequest.serializer(), VoiceTurnRequest("vt-9"))
        out.write(wav)

        val input = ByteArrayInputStream(out.toByteArray())
        val req = VoiceEnvelope.read(input, VoiceTurnRequest.serializer())
        assertEquals("vt-9", req.requestId)
        // The stream is positioned exactly at the first audio byte — the rest is the WAV, verbatim.
        val trailing = input.readBytes()
        assertTrue(wav.contentEquals(trailing), "audio payload must survive framing untouched")
    }

    @Test
    fun framed_reply_then_pcm_reads_both_directions() {
        val pcm = ByteArray(64) { it.toByte() }
        val reply = VoiceTurnReply("vt-4", transcript = "hi", reply = "hello", outcome = Outcome.OK, pcmByteCount = pcm.size)
        val out = ByteArrayOutputStream()
        VoiceEnvelope.write(out, VoiceTurnReply.serializer(), reply)
        out.write(pcm)

        val input = ByteArrayInputStream(out.toByteArray())
        val decoded = VoiceEnvelope.read(input, VoiceTurnReply.serializer())
        assertEquals(reply, decoded)
        assertTrue(pcm.contentEquals(input.readBytes()))
    }

    @Test
    fun two_frames_back_to_back_read_independently() {
        val out = ByteArrayOutputStream()
        VoiceEnvelope.write(out, VoiceTurnRequest.serializer(), VoiceTurnRequest("a"))
        VoiceEnvelope.write(out, VoiceTurnRequest.serializer(), VoiceTurnRequest("b"))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("a", VoiceEnvelope.read(input, VoiceTurnRequest.serializer()).requestId)
        assertEquals("b", VoiceEnvelope.read(input, VoiceTurnRequest.serializer()).requestId)
    }

    // ---- garbage / corruption fails loudly ----------------------------------

    @Test
    fun truncated_length_prefix_throws() {
        val input = ByteArrayInputStream(byteArrayOf(0x00, 0x00)) // only 2 of 4 prefix bytes
        assertFailsWith<EOFException> { VoiceEnvelope.read(input, VoiceTurnRequest.serializer()) }
    }

    @Test
    fun truncated_body_throws() {
        // Prefix claims 100 bytes but only 3 follow.
        val input = ByteArrayInputStream(byteArrayOf(0, 0, 0, 100, 1, 2, 3))
        assertFailsWith<EOFException> { VoiceEnvelope.read(input, VoiceTurnRequest.serializer()) }
    }

    @Test
    fun oversized_length_prefix_is_refused() {
        // Prefix well past MAX_ENVELOPE_BYTES — corruption, refused before allocating.
        val input = ByteArrayInputStream(byteArrayOf(0x7F, 0x00, 0x00, 0x00))
        assertFailsWith<Exception> { VoiceEnvelope.read(input, VoiceTurnRequest.serializer()) }
    }

    @Test
    fun garbage_json_body_throws() {
        val garbage = "not json at all".toByteArray()
        val out = ByteArrayOutputStream()
        // Hand-write a valid length prefix over garbage JSON bytes.
        out.write(byteArrayOf(0, 0, 0, garbage.size.toByte()))
        out.write(garbage)
        val input = ByteArrayInputStream(out.toByteArray())
        assertFailsWith<Exception> { VoiceEnvelope.read(input, VoiceTurnRequest.serializer()) }
    }

    @Test
    fun dtos_fail_loudly_on_garbage_bytes() {
        assertFailsWith<Exception> { VoiceTurnRequest.fromBytes("nope".toByteArray()) }
        assertFailsWith<Exception> { VoiceTurnReply.fromBytes(byteArrayOf(0x00, 0x01, 0x02)) }
    }

    @Test
    fun every_outcome_survives_the_reply_envelope() {
        for (outcome in Outcome.entries) {
            val original = VoiceTurnReply(requestId = "r-$outcome", outcome = outcome, detail = "d-$outcome")
            assertEquals(original, VoiceTurnReply.fromBytes(original.toBytes()), "outcome $outcome must survive")
        }
    }
}
