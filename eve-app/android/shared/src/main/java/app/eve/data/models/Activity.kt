package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GET /v1/activity/feed?limit=N — the canonical conversation timeline proxied from OpenJarvis.
 *
 * The phone talks only to the sidecar; the sidecar proxies the brain on :8000. When the desktop
 * brain is down the proxy answers [desktopOnline]=false with an empty list, so every screen can
 * render a clean "Desktop offline" state instead of an error. Every field tolerates omission via
 * DEFAULT_JSON (ignoreUnknownKeys, isLenient) so schema drift never crashes a decode.
 */
@Serializable
data class ActivityFeed(
    @SerialName("desktop_online") val desktopOnline: Boolean = false,
    val source: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
)

/** One row in the feed — a whole conversation collapsed to its headline + a few stats. */
@Serializable
data class ConversationSummary(
    val id: String = "",
    /** phone-voice | desktop-voice | typed-chat | … — drives the source badge. */
    val source: String = "",
    /** First line of the conversation; already truncated server-side. */
    val title: String = "",
    @SerialName("started_at") val startedAt: Long = 0L,
    @SerialName("ended_at") val endedAt: Long = 0L,
    @SerialName("msg_count") val msgCount: Int = 0,
    @SerialName("tool_count") val toolCount: Int = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0L,
    /** Opaque server blob (JSON-as-string); kept verbatim, not parsed. */
    val meta: String = "",
)

/**
 * GET /v1/activity/feed/{conv_id} — one conversation's full message + delegation/tool timeline.
 * The {conv_id} contains colons (e.g. "voice:phone:1782301594451") and MUST be URL-encoded.
 */
@Serializable
data class ConversationDetailResponse(
    @SerialName("desktop_online") val desktopOnline: Boolean = false,
    val conversation: ConversationDetail? = null,
)

@Serializable
data class ConversationDetail(
    val id: String = "",
    val source: String = "",
    val title: String = "",
    @SerialName("started_at") val startedAt: Long = 0L,
    @SerialName("ended_at") val endedAt: Long = 0L,
    @SerialName("msg_count") val msgCount: Int = 0,
    @SerialName("tool_count") val toolCount: Int = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0L,
    val meta: String = "",
    val messages: List<ConversationMessage> = emptyList(),
)

/**
 * A single timeline entry. [role] is one of user | assistant | tool | delegation. The first two
 * render as chat bubbles; `tool` and `delegation` render as "what EVE actually did" action rows.
 *
 * [meta] is a lenient [JsonObject] because its keys vary by role:
 *   - tool:        { tool, target, args, status, ok, detail }
 *   - delegation:  { tool, target, task, deleg_id, status, steps[], ok, brain, result, failures[] }
 * The typed accessors below pull the few fields the UI needs without modeling every key.
 */
@Serializable
data class ConversationMessage(
    val seq: Int = 0,
    val role: String = "",
    val ts: Long = 0L,
    val text: String = "",
    val meta: JsonObject = JsonObject(emptyMap()),
) {
    val isAction: Boolean get() = role == "tool" || role == "delegation"

    private fun metaString(key: String): String? =
        (meta[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun metaBool(key: String): Boolean? =
        (meta[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /** The tool/agent name invoked (e.g. "get_calendar", "jarvis_agent"). */
    val toolName: String? get() = metaString("tool") ?: metaString("target")

    /** Where a delegation routed (e.g. "hermes", "codex"). */
    val target: String? get() = metaString("target")

    /** The brain that ultimately answered a delegation. */
    val brain: String? get() = metaString("brain")

    /** The task text a delegation was asked to do. */
    val task: String? get() = metaString("task")

    /** Final delegation result text, if any. */
    val result: String? get() = metaString("result")

    /** Raw args passed to a tool (a JSON string), if present. */
    val args: String? get() = metaString("args")

    /** Server status string ("ok" | "error" | …). */
    val status: String? get() = metaString("status")

    /** Honest success signal — null when the server didn't say. */
    val ok: Boolean? get() = metaBool("ok")

    /** Human-readable error/detail blob for a failed step. */
    val detail: String? get() = metaString("detail")

    /** Total wall time a delegation took, in ms (from total_latency_ms), if present. */
    val totalLatencyMs: Long? get() = (meta["total_latency_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** The brains a delegation tried, in order (from steps[].brain), de-duped, for a quick trail. */
    val delegationBrains: List<String>
        get() = (meta["steps"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("brain")?.let { b -> (b as? JsonPrimitive)?.contentOrNull } }
            ?.distinct()
            ?: emptyList()
}
