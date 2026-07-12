package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET/POST /v1/settings -> {remote_approval_enabled, thinking_enabled}. */
@Serializable
data class SettingsDto(
    @SerialName("remote_approval_enabled") val remoteApprovalEnabled: Boolean,
    // Default false so a thinking-only or older response still decodes (Epic T).
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean = false,
    // "Let me interrupt Atlas" toggle; default false so an older response still decodes.
    @SerialName("barge_in_enabled") val bargeInEnabled: Boolean = false,
)

/**
 * GET /v1/memory -> {speaker, facts:[...], items:[...]}. speaker is null for the owner (the real
 * memory). `facts` are the raw markdown bullets (kept for back-compat); `items` is the enriched,
 * NEWEST-FIRST view the Memory tab renders. `items` defaults to empty so an older server (facts
 * only) still decodes — and ignoreUnknownKeys keeps any future fields harmless.
 */
@Serializable
data class MemoryFacts(
    val speaker: String? = null,
    val facts: List<String> = emptyList(),
    val items: List<MemoryItem> = emptyList(),
)

/**
 * One structured fact Atlas remembers about the owner. `date` is `YYYY-MM-DD` (empty string when
 * undated) and `category` ∈ faith | health | family | business | goal | preference | general
 * (empty -> "Other"). Both default so a sparse/partial item still decodes cleanly.
 */
@Serializable
data class MemoryItem(
    val text: String,
    val date: String = "",
    val category: String = "",
)

/** POST /v1/memory body. speaker omitted -> owner page (the owner's real memory). */
@Serializable
data class MemoryAdd(
    val speaker: String? = null,
    val fact: String,
)

/**
 * POST /v1/memory -> {ok, speaker, remembered}.
 *
 * `speaker` is nullable: the server echoes back `add.speaker`, which is `null` for an OWNER write
 * (`approval_api.py:441` returns `{"ok": True, "speaker": add.speaker, "remembered": fact}` where
 * `add.speaker` is `str | None`). A non-null type here crashed the decode on every owner write.
 */
@Serializable
data class MemoryAddResult(
    val ok: Boolean,
    val speaker: String? = null,
    val remembered: String,
)

/** POST /v1/approvals/{id}/approve -> {ok, released_tool, result:{...}}. */
@Serializable
data class ApproveResult(
    val ok: Boolean,
    @SerialName("released_tool") val releasedTool: String? = null,
    val result: kotlinx.serialization.json.JsonObject? = null,
)

/** POST /v1/approvals/{id}/deny -> {ok, denied}. */
@Serializable
data class DenyResult(
    val ok: Boolean,
    val denied: Boolean,
)

/**
 * POST /v1/ask -> {"reply": "..."}. One push-to-talk utterance answered by Atlas's full brain (the
 * watch talk flow). [reply] is the assistant's answer text; it is the ONE required field — an empty
 * body or missing key is a decode failure the caller surfaces honestly (never a fake blank answer).
 */
@Serializable
data class AskResult(
    val reply: String,
)

/**
 * POST /v1/voice/turn -> the v2 NATIVE watch voice turn. The server transcribes the uploaded WAV with
 * Atlas's own STT, runs [transcript] through the same brain leg as /v1/ask, and synthesizes [reply] in
 * its canonical voice. [transcript] and [reply] are always present on a 200; [audioB64] is a 16 kHz
 * mono PCM16 WAV (base64) OR null when the TTS leg failed — in which case [voiceError] names the leg
 * and the reply TEXT is still delivered (no silent fallback to a different voice). The named failure
 * legs are HTTP statuses the [app.eve.data.ApiClient] maps to [app.eve.data.ApiError] (400 undecodable
 * audio, 422 no speech, 502/504 brain), never a fake 200.
 */
@Serializable
data class VoiceTurnResult(
    val transcript: String,
    val reply: String,
    @kotlinx.serialization.SerialName("audio_b64")
    val audioB64: String? = null,
    @kotlinx.serialization.SerialName("sample_rate")
    val sampleRate: Int = 16_000,
    @kotlinx.serialization.SerialName("voice_error")
    val voiceError: String? = null,
)

/**
 * POST /v1/vision/frame -> {"ok": true, "bytes": <n>}. The app uploads a captured JPEG (base64)
 * for the look_via_phone flow; the server spools it transiently for the local vision model.
 * [bytes] is the decoded size the server accepted, optional so a leaner reply still decodes.
 */
@Serializable
data class VisionFrameResult(
    val ok: Boolean,
    val bytes: Int? = null,
)

/**
 * POST /v1/health/snapshot -> {"ok": true, ...}. The phone uploads a compact 24h health snapshot
 * (see [app.eve.health.HealthSnapshot]); the sidecar stamps it written_at and stores it for Atlas's
 * `health_status` tool. [ok] is the one field we assert; any extra keys the server adds are ignored
 * (EveWireJson.ignoreUnknownKeys), and it defaults false so a bodyless 2xx still decodes honestly.
 */
@Serializable
data class HealthSnapshotAck(
    val ok: Boolean = false,
)
