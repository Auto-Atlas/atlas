package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /v1/activity?day=today -> {date, ...digest}. The digest is either a full review
 * (transcript_review.review_day success branch) OR the empty-day shape
 * {ok:false, error, available_days}. Every digest field is optional so a single tolerant
 * model decodes both. `ok` defaults to true for the success branch (which omits nothing but
 * does include ok:true); the empty branch sets ok:false explicitly.
 */
@Serializable
data class ActivityDigest(
    val date: String,
    val ok: Boolean = true,
    // empty-day branch
    val error: String? = null,
    @SerialName("available_days") val availableDays: List<String> = emptyList(),
    // success branch
    @SerialName("first_activity") val firstActivity: String? = null,
    @SerialName("last_activity") val lastActivity: String? = null,
    val exchanges: Int? = null,
    @SerialName("bot_sentences") val botSentences: Int? = null,
    val tools: Map<String, ToolStat> = emptyMap(),
    @SerialName("tool_failures") val toolFailures: Int? = null,
    val failures: List<ActivityFailure> = emptyList(),
    @SerialName("sample_user_requests") val sampleUserRequests: List<String> = emptyList(),
    val latency: Latency? = null,
    @SerialName("malformed_lines_skipped") val malformedLinesSkipped: Int? = null,
) {
    val isEmptyDay: Boolean get() = !ok
}

@Serializable
data class ToolStat(
    val calls: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class ActivityFailure(
    val time: String = "",
    val tool: String = "",
    val timeout: Boolean = false,
    val detail: String = "",
)

@Serializable
data class Latency(
    @SerialName("avg_llm_ttfb_s") val avgLlmTtfbS: Double,
    @SerialName("worst_llm_ttfb_s") val worstLlmTtfbS: Double,
    @SerialName("turns_measured") val turnsMeasured: Int,
)
