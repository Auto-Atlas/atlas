package app.eve.wearbridge

import app.eve.ASSISTANT_NAME
import android.util.Log
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.EveWireJson
import app.eve.data.audio.Wav
import app.eve.data.models.VoiceTurnResult
import app.eve.data.wear.Outcome
import app.eve.data.wear.VoiceEnvelope
import app.eve.data.wear.VoiceTurnReply
import app.eve.data.wear.VoiceTurnRequest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * The phone's v2 NATIVE voice leg — the pure, testable core (no GMS, no Android audio types). Given
 * one opened bidirectional channel's [InputStream]/[OutputStream], it:
 *
 *   1. reads the [VoiceTurnRequest] envelope + the recorded WAV bytes (the watch's half),
 *   2. base64-encodes the WAV and POSTs it to `/v1/voice/turn` via [voiceTurn] (Atlas's own STT ->
 *      brain -> its voice — no Google in the path),
 *   3. maps the result to a [VoiceTurnReply] envelope, STRIPS the WAV header from the returned audio
 *      so the watch never parses RIFF, and writes `envelope + raw PCM` back on the same channel.
 *
 * House rules honored here:
 *  - No silent fallback: every [ApiError] maps to a named [Outcome]/detail (reusing the talk
 *    vocabulary); a malformed inbound envelope whose [VoiceTurnRequest.requestId] is recoverable gets
 *    an honest ERROR reply (mirror of [WearBridge]'s recovery); an unrecoverable one is logged loudly.
 *  - Reply TEXT reaches the wrist even when the voice can't: a 200 with `audio_b64 == null` +
 *    `voice_error` still delivers [VoiceTurnReply.reply] with `pcmByteCount = 0` and the named
 *    [VoiceTurnReply.voiceError] note — never a swap to a different voice.
 *  - An empty/undecodable recording is a named failure, never sent onward to the brain.
 *
 * @param voiceTurn the HTTP leg (usually [app.eve.data.ApiClient.voiceTurn]) — a lambda so the core
 *   stays pure and testable with a fake, no ApiClient in the unit tests. It receives the base64 WAV
 *   AND the watch's correlation id (forwarded to the server per the HTTP body contract).
 */
class VoiceTurnRelay(
    private val voiceTurn: suspend (audioB64: String, requestId: String) -> ApiResult<VoiceTurnResult>,
) {

    /**
     * Handle ONE voice turn end-to-end on an already-open channel. Never throws into the caller: a
     * transport/read failure with no recoverable id is logged loudly and the channel simply closes
     * (the watch's own await-timeout then names the leg). [output] is flushed after the reply so the
     * bytes actually leave before the channel is closed by the edge.
     */
    suspend fun handleTurn(input: InputStream, output: OutputStream) {
        // ---- read the watch's half: envelope frame + WAV bytes ----
        val frame: ByteArray = try {
            VoiceEnvelope.readFrame(input)
        } catch (t: Throwable) {
            // The frame itself is unreadable (truncated/oversized) — no id to answer to. Loud, not silent.
            Log.e(TAG, "Voice turn: unreadable request envelope: ${t.message}", t)
            return
        }
        val request = try {
            VoiceTurnRequest.fromBytes(frame)
        } catch (t: Throwable) {
            val recovered = tryRecoverRequestId(frame)
            if (recovered != null) {
                writeReply(output, errorReply(recovered, "malformed voice request envelope"))
            } else {
                Log.e(TAG, "Voice turn: malformed request envelope, no recoverable id: ${t.message}", t)
            }
            return
        }

        val wav: ByteArray = try {
            input.readBytes()
        } catch (t: Throwable) {
            writeReply(output, errorReply(request.requestId, "could not read recorded audio: ${t.message}"))
            return
        }
        if (wav.isEmpty()) {
            // An empty recording is a named failure — never sent to the brain.
            writeReply(output, errorReply(request.requestId, "empty recording — nothing to send"))
            return
        }

        // ---- HTTP leg to Atlas's own speech stack ----
        val audioB64 = Base64.getEncoder().encodeToString(wav)
        val result = try {
            voiceTurn(audioB64, request.requestId)
        } catch (t: Throwable) {
            // voiceTurn returns an ApiResult, it doesn't throw — an escaped exception becomes honest ERROR.
            writeReply(output, errorReply(request.requestId, t.message ?: t::class.simpleName ?: "voice turn failed"))
            return
        }

        val reply = when (result) {
            is ApiResult.Ok -> okReply(request.requestId, result.value)
            is ApiResult.Err -> apiErrorToReply(request.requestId, result.error)
        }
        writeReply(output, reply)
    }

    /**
     * Build the OK reply from the server result. The reply audio (a 16k mono PCM16 WAV) has its RIFF header
     * stripped here so the watch reads only raw PCM; if she has no audio (TTS leg failed) the reply
     * TEXT is still delivered with the [VoiceTurnResult.voiceError] note and pcmByteCount = 0. If the
     * audio is present but undecodable, that is surfaced as a voiceError too (text never blocked).
     */
    private fun okReply(requestId: String, result: VoiceTurnResult): VoiceTurnReplyWithPcm {
        val audioB64 = result.audioB64
        if (audioB64.isNullOrBlank()) {
            return VoiceTurnReplyWithPcm(
                VoiceTurnReply(
                    requestId = requestId,
                    transcript = result.transcript,
                    reply = result.reply,
                    voiceError = result.voiceError ?: NO_AUDIO_NOTE,
                    outcome = Outcome.OK,
                    sampleRate = result.sampleRate,
                    pcmByteCount = 0,
                ),
                pcm = ByteArray(0),
            )
        }
        val pcm = try {
            Wav.pcmFrom(Base64.getDecoder().decode(audioB64)).bytes
        } catch (t: Throwable) {
            // The reply audio didn't decode phone-side. Text still wins; name the voice leg.
            Log.e(TAG, "Voice turn: could not strip WAV from reply audio: ${t.message}", t)
            return VoiceTurnReplyWithPcm(
                VoiceTurnReply(
                    requestId = requestId,
                    transcript = result.transcript,
                    reply = result.reply,
                    voiceError = "reply audio undecodable phone-side",
                    outcome = Outcome.OK,
                    sampleRate = result.sampleRate,
                    pcmByteCount = 0,
                ),
                pcm = ByteArray(0),
            )
        }
        return VoiceTurnReplyWithPcm(
            VoiceTurnReply(
                requestId = requestId,
                transcript = result.transcript,
                reply = result.reply,
                voiceError = result.voiceError,
                outcome = Outcome.OK,
                sampleRate = result.sampleRate,
                pcmByteCount = pcm.size,
            ),
            pcm = pcm,
        )
    }

    /**
     * Map an [ApiError] to its honest, named-leg reply. HTTP 422 (server recognized no speech) is the
     * "Didn't catch that" case: it carries an EMPTY transcript (a structural signal the watch renders
     * as the 422 copy) rather than a generic error string.
     */
    private fun apiErrorToReply(requestId: String, error: ApiError): VoiceTurnReplyWithPcm {
        val reply = when (error) {
            is ApiError.Offline -> failed(requestId, Outcome.SERVER_UNREACHABLE, error.cause)
            ApiError.NotConfigured -> failed(requestId, Outcome.SERVER_UNREACHABLE, "phone not connected to $ASSISTANT_NAME")
            ApiError.Unauthorized -> failed(requestId, Outcome.UNAUTHORIZED, "unauthorized (401) — reconnect the phone")
            ApiError.NotFound -> failed(requestId, Outcome.ERROR, "$ASSISTANT_NAME has no /v1/voice/turn endpoint (404)")
            ApiError.AlreadyResolved -> failed(requestId, Outcome.ERROR, "unexpected 409 from $ASSISTANT_NAME")
            is ApiError.Http ->
                if (error.status == HTTP_NO_SPEECH) {
                    // No speech recognized: blank transcript is the watch's DIDNT_CATCH signal.
                    VoiceTurnReplyWithPcm(
                        VoiceTurnReply(requestId, transcript = "", reply = null, outcome = Outcome.ERROR, detail = error.detail.ifBlank { "no speech recognized" }),
                        ByteArray(0),
                    )
                } else {
                    failed(requestId, Outcome.ERROR, "HTTP ${error.status}: ${error.detail}")
                }
            is ApiError.Decode -> failed(requestId, Outcome.ERROR, "decode error: ${error.message}")
            is ApiError.Unknown -> failed(requestId, Outcome.ERROR, error.message)
        }
        return reply
    }

    private fun failed(requestId: String, outcome: Outcome, detail: String?): VoiceTurnReplyWithPcm =
        VoiceTurnReplyWithPcm(VoiceTurnReply(requestId, outcome = outcome, detail = detail), ByteArray(0))

    private fun errorReply(requestId: String, detail: String): VoiceTurnReplyWithPcm =
        VoiceTurnReplyWithPcm(VoiceTurnReply(requestId, outcome = Outcome.ERROR, detail = detail), ByteArray(0))

    private fun writeReply(output: OutputStream, reply: VoiceTurnReplyWithPcm) {
        VoiceEnvelope.write(output, VoiceTurnReply.serializer(), reply.envelope)
        if (reply.pcm.isNotEmpty()) output.write(reply.pcm)
        output.flush()
    }

    private fun tryRecoverRequestId(frame: ByteArray): String? = try {
        EveWireJson.parseToJsonElement(String(frame, Charsets.UTF_8))
            .jsonObject["requestId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /** A reply envelope paired with the raw PCM that follows it on the channel. */
    private data class VoiceTurnReplyWithPcm(val envelope: VoiceTurnReply, val pcm: ByteArray)

    companion object {
        const val TAG = "VoiceTurnRelay"

        /** HTTP 422 from `/v1/voice/turn` = "no speech recognized" (blank transcript). */
        const val HTTP_NO_SPEECH = 422

        /** Voice-note when the server delivered text but no audio and named no specific leg. */
        const val NO_AUDIO_NOTE = "no audio returned"
    }
}
