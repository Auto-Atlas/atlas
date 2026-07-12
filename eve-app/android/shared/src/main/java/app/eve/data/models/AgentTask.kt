package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * GET /v1/agent-tasks — the live delegation activity surface (live-delegation-approvals).
 * `active` = tasks currently in an agent's hands (or waiting on the owner / being
 * cancelled); `recent` = the terminal tail (Done / Failed / Cancelled cards). The server
 * scrubs capability tokens before anything reaches this shape.
 */
@Serializable
data class AgentTasksResponse(
    val active: List<AgentTaskDto> = emptyList(),
    val recent: List<AgentTaskDto> = emptyList(),
)

@Serializable
data class AgentTaskDto(
    val id: String,
    val agent: String,
    val task: String? = null,
    val summary: String? = null,
    val status: String,
    @SerialName("effective_status") val effectiveStatus: String? = null,
    val delivery: String? = null,
    val requester: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("ttl_s") val ttlS: Long? = null,
    @SerialName("seconds_left") val secondsLeft: Double? = null,
    @SerialName("resolved_at") val resolvedAt: Double? = null,
    @SerialName("delivered_at") val deliveredAt: Double? = null,
    val result: JsonObject? = null,
    val question: AgentTaskQuestion? = null,
    val answer: JsonObject? = null,
    /** Which controls are REAL for this task — drives honest button enable/disable. */
    val capabilities: AgentTaskCapabilities? = null,
    /** An owner steer is staged but hasn't reached the agent yet. */
    @SerialName("redirect_pending") val redirectPending: Boolean = false,
)

@Serializable
data class AgentTaskQuestion(
    val qid: String? = null,
    val question: String? = null,
    @SerialName("approval_id") val approvalId: String? = null,
    @SerialName("asked_at") val askedAt: Double? = null,
)

@Serializable
data class AgentTaskCapabilities(
    val cancel: Boolean = false,
    val redirect: Boolean = false,
    /** Human-readable reason when redirect is false — shown next to the disabled button. */
    @SerialName("redirect_reason") val redirectReason: String? = null,
)

/** POST /v1/agent-tasks/{id}/cancel|redirect -> {ok, status, detail}. */
@Serializable
data class AgentTaskActionResult(
    val ok: Boolean,
    val status: String,
    val detail: String = "",
)
