package app.eve.wearbridge

import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.audio.Wav
import app.eve.data.models.VoiceTurnResult
import app.eve.data.wear.Outcome
import app.eve.data.wear.VoiceEnvelope
import app.eve.data.wear.VoiceTurnReply
import app.eve.data.wear.VoiceTurnRequest
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on [VoiceTurnRelay] with fake channel streams (no GMS, no mocking library). Builds
 * the watch's half (envelope frame + WAV) as a [ByteArrayInputStream], captures the phone's reply
 * (envelope + raw PCM) from a [ByteArrayOutputStream], and asserts the exact honest mapping — every
 * ApiError -> named outcome, the WAV header stripped correctly, and text delivered even when audio isn't.
 */
class VoiceTurnRelayTest {

    /** The watch's channel bytes: framed [VoiceTurnRequest] + the recorded WAV. */
    private fun watchInput(requestId: String, wav: ByteArray): InputStream {
        val out = ByteArrayOutputStream()
        VoiceEnvelope.write(out, VoiceTurnRequest.serializer(), VoiceTurnRequest(requestId))
        out.write(wav)
        return ByteArrayInputStream(out.toByteArray())
    }

    /** Decode the phone's reply half: the envelope, then the trailing raw PCM. */
    private data class Captured(val reply: VoiceTurnReply, val pcm: ByteArray)

    private fun capture(out: ByteArrayOutputStream): Captured {
        val input = ByteArrayInputStream(out.toByteArray())
        val reply = VoiceEnvelope.read(input, VoiceTurnReply.serializer())
        return Captured(reply, input.readBytes())
    }

    private fun sampleWav(): ByteArray = Wav.pcm16ToWav(ByteArray(320) { it.toByte() }, 16_000)

    // ---- happy paths --------------------------------------------------------

    @Test
    fun happy_path_returns_transcript_reply_and_stripped_pcm() = runTest {
        val replyWav = Wav.pcm16ToWav(ByteArray(64) { (it + 1).toByte() }, 16_000)
        val relay = VoiceTurnRelay { audioB64, requestId ->
            // The phone must have base64-encoded the recorded WAV and forwarded the watch's id.
            assertTrue(Base64.getDecoder().decode(audioB64).isNotEmpty())
            assertEquals("vt-1", requestId, "the watch's correlation id must reach the HTTP leg")
            ApiResult.Ok(
                VoiceTurnResult(
                    transcript = "what's on today?",
                    reply = "You have a 3pm with Jamie.",
                    audioB64 = Base64.getEncoder().encodeToString(replyWav),
                    sampleRate = 16_000,
                    voiceError = null,
                ),
            )
        }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-1", sampleWav()), out)

        val (reply, pcm) = capture(out)
        assertEquals("vt-1", reply.requestId)
        assertEquals(Outcome.OK, reply.outcome)
        assertEquals("what's on today?", reply.transcript)
        assertEquals("You have a 3pm with Jamie.", reply.reply)
        assertNull(reply.voiceError)
        // The RIFF header is stripped: the raw PCM equals the reply WAV's data chunk.
        val expectedPcm = Wav.pcmFrom(replyWav).bytes
        assertTrue(expectedPcm.contentEquals(pcm), "watch must receive raw PCM, never RIFF")
        assertEquals(expectedPcm.size, reply.pcmByteCount)
    }

    @Test
    fun tts_failed_still_delivers_text_with_a_named_voice_error_and_no_audio() = runTest {
        val relay = VoiceTurnRelay { _, _ ->
            ApiResult.Ok(
                VoiceTurnResult(
                    transcript = "remind me at five",
                    reply = "Reminder set for 5pm.",
                    audioB64 = null,
                    voiceError = "chatterbox unreachable",
                ),
            )
        }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-2", sampleWav()), out)

        val (reply, pcm) = capture(out)
        assertEquals(Outcome.OK, reply.outcome)
        assertEquals("Reminder set for 5pm.", reply.reply, "her answer must reach the wrist even when her voice can't")
        assertEquals("chatterbox unreachable", reply.voiceError)
        assertEquals(0, reply.pcmByteCount)
        assertTrue(pcm.isEmpty())
    }

    @Test
    fun undecodable_reply_audio_names_the_voice_leg_but_keeps_text() = runTest {
        val relay = VoiceTurnRelay { _, _ ->
            ApiResult.Ok(
                VoiceTurnResult(
                    transcript = "hi",
                    reply = "Hello.",
                    audioB64 = Base64.getEncoder().encodeToString("not a wav".toByteArray()),
                ),
            )
        }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-3", sampleWav()), out)

        val (reply, pcm) = capture(out)
        assertEquals(Outcome.OK, reply.outcome)
        assertEquals("Hello.", reply.reply)
        assertEquals("reply audio undecodable phone-side", reply.voiceError)
        assertEquals(0, reply.pcmByteCount)
        assertTrue(pcm.isEmpty())
    }

    // ---- named failure legs -------------------------------------------------

    @Test
    fun offline_maps_SERVER_UNREACHABLE_with_real_detail() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Err(ApiError.Offline("connection refused")) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-4", sampleWav()), out)

        val reply = capture(out).reply
        assertEquals(Outcome.SERVER_UNREACHABLE, reply.outcome)
        assertEquals("connection refused", reply.detail)
        assertNull(reply.reply)
    }

    @Test
    fun unauthorized_maps_UNAUTHORIZED() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Err(ApiError.Unauthorized) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-5", sampleWav()), out)
        assertEquals(Outcome.UNAUTHORIZED, capture(out).reply.outcome)
    }

    @Test
    fun not_found_maps_ERROR_naming_the_missing_endpoint() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Err(ApiError.NotFound) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-6", sampleWav()), out)
        val reply = capture(out).reply
        assertEquals(Outcome.ERROR, reply.outcome)
        assertEquals("EVE has no /v1/voice/turn endpoint (404)", reply.detail)
    }

    @Test
    fun http_422_no_speech_is_a_blank_transcript_signal() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Err(ApiError.Http(422, "no speech recognized")) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-7", sampleWav()), out)

        val reply = capture(out).reply
        assertEquals(Outcome.ERROR, reply.outcome)
        // A blank (empty, non-null) transcript is the watch's "Didn't catch that" signal.
        assertEquals("", reply.transcript)
        assertEquals("no speech recognized", reply.detail)
    }

    @Test
    fun http_502_brain_maps_ERROR_with_status_detail() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Err(ApiError.Http(502, "bad gateway")) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-8", sampleWav()), out)
        assertEquals("HTTP 502: bad gateway", capture(out).reply.detail)
    }

    @Test
    fun not_configured_maps_SERVER_UNREACHABLE() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Err(ApiError.NotConfigured) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-9", sampleWav()), out)
        assertEquals(Outcome.SERVER_UNREACHABLE, capture(out).reply.outcome)
    }

    // ---- guards: empty recording never sent, corruption never crashes -------

    @Test
    fun empty_recording_is_a_named_failure_never_sent_to_the_brain() = runTest {
        var called = false
        val relay = VoiceTurnRelay { _, _ -> called = true; ApiResult.Ok(VoiceTurnResult("x", "y")) }
        val out = ByteArrayOutputStream()
        relay.handleTurn(watchInput("vt-10", ByteArray(0)), out)

        val reply = capture(out).reply
        assertEquals(Outcome.ERROR, reply.outcome)
        assertEquals("empty recording — nothing to send", reply.detail)
        assertTrue(!called, "an empty recording must never reach the brain")
    }

    @Test
    fun unrecoverable_garbage_frame_writes_nothing_and_does_not_crash() = runTest {
        var called = false
        val relay = VoiceTurnRelay { _, _ -> called = true; ApiResult.Ok(VoiceTurnResult("x", "y")) }
        // A framed JSON object with NO requestId -> decode fails, nothing to answer to.
        val json = """{"nope":"x"}""".toByteArray()
        val garbage = ByteArrayOutputStream()
        garbage.write(byteArrayOf(0, 0, 0, json.size.toByte()))
        garbage.write(json)
        val out = ByteArrayOutputStream()
        relay.handleTurn(ByteArrayInputStream(garbage.toByteArray()), out)

        assertTrue(out.toByteArray().isEmpty(), "no recoverable id -> no reply written")
        assertTrue(!called)
    }

    @Test
    fun truncated_frame_writes_nothing_and_does_not_crash() = runTest {
        val relay = VoiceTurnRelay { _, _ -> ApiResult.Ok(VoiceTurnResult("x", "y")) }
        // Length prefix claims 100 bytes; only 3 follow -> unreadable frame, no id.
        val out = ByteArrayOutputStream()
        relay.handleTurn(ByteArrayInputStream(byteArrayOf(0, 0, 0, 100, 1, 2, 3)), out)
        assertTrue(out.toByteArray().isEmpty())
    }
}
