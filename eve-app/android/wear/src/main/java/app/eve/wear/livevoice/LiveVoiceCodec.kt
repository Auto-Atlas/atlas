package app.eve.wear.livevoice

import app.eve.data.EveWireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The PURE live-voice control-frame codec: JSON text frames (server->watch) → [VoiceEvent], and the
 * three client->server control frames (auth / interrupt / bye) → JSON text. No OkHttp, no Android — a
 * plain object with exhaustive JVM tests. Binary frames (PCM16, both directions) never pass through
 * here; only the JSON control channel does.
 *
 * The wire contract (mirrors the server's watch_bot.py, documented in the v3 spec):
 *  server->watch text = {"type": "state"|"user_transcript"|"bot_transcript"|"error", ...}
 *    state ∈ connected | listening | thinking | speaking | idle
 *  watch->server text = {"type":"auth","token":...}  (FIRST frame) | {"type":"interrupt"} | {"type":"bye"}
 *
 * House rule — no silent fallback: a server "error" frame becomes a NAMED fatal [VoiceEvent.Failed]
 * (never dropped), and a MALFORMED frame is surfaced loudly as [VoiceEvent.Failed] too. A frame that
 * is well-formed but of an UNKNOWN type / unknown state is forward-compatibly IGNORED (returns null) —
 * a newer server adding a control type must never crash an older watch.
 */
object LiveVoiceCodec {

    /**
     * Decode one server text frame to a [VoiceEvent], or null when the frame is well-formed but not
     * something this watch acts on (unknown type / unknown state). Malformed JSON or a server error
     * frame both yield a NAMED [VoiceEvent.Failed] (loud, never silent).
     */
    fun decode(text: String): VoiceEvent? {
        val obj: JsonObject = try {
            EveWireJson.parseToJsonElement(text) as? JsonObject
                ?: return VoiceEvent.Failed(WearLiveVoiceCopy.BAD_CONTROL_FRAME)
        } catch (t: Throwable) {
            return VoiceEvent.Failed(WearLiveVoiceCopy.BAD_CONTROL_FRAME)
        }
        return when (obj.str("type")) {
            "state" -> stateEvent(obj.str("state"))
            "user_transcript" -> VoiceEvent.UserTranscript(obj.str("text").orEmpty())
            "bot_transcript" -> VoiceEvent.BotTranscript(obj.str("text").orEmpty())
            "error" -> VoiceEvent.Failed(
                WearLiveVoiceCopy.serverError(obj.str("message")?.takeIf { it.isNotBlank() } ?: "unknown error"),
            )
            else -> null // unknown / missing type — forward-compatibly ignored
        }
    }

    /** Map a server `state` value to the conversation event. Unknown/missing → null (ignored). */
    private fun stateEvent(state: String?): VoiceEvent? = when (state) {
        "connected" -> VoiceEvent.IceConnected
        "listening" -> VoiceEvent.VadUserStart
        "thinking" -> VoiceEvent.BotThinking
        "speaking" -> VoiceEvent.BotSpeaking
        "idle" -> VoiceEvent.BotDone
        else -> null
    }

    /** The FIRST client frame: authenticate with the bearer token (fail-closed on the server). */
    fun authFrame(token: String): String = frame { put("type", "auth"); put("token", token) }

    /** Client barge-in: stop EVE's current utterance and hand the floor back. */
    fun interruptFrame(): String = frame { put("type", "interrupt") }

    /** Client graceful close: end the session cleanly before the socket tears down. */
    fun byeFrame(): String = frame { put("type", "bye") }

    private inline fun frame(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        EveWireJson.encodeToString(JsonObject.serializer(), buildJsonObject(build))

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
