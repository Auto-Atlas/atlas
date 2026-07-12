package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /v1/skills -> {skills:[{tool,catalog,risk,requires_confirmation}]}. */
@Serializable
data class SkillsResponse(val skills: List<SkillDto>)

@Serializable
data class SkillDto(
    val tool: String,
    val catalog: String,
    val risk: String,
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean,
)

/** GET /v1/skills/feed -> {pending:[{tool,mode,status,seconds_left}]}. */
@Serializable
data class FeedsResponse(val pending: List<FeedDto>)

@Serializable
data class FeedDto(
    val tool: String,
    val mode: String,
    val status: String,
    @SerialName("seconds_left") val secondsLeft: Double,
)

/** POST /v1/skills/{tool}/feed -> {ok,tool,mode,id}. */
@Serializable
data class FeedResult(
    val ok: Boolean,
    val tool: String,
    val mode: String,
    val id: String? = null,
)

/**
 * DELETE /v1/skills/feed/{tool} -> {ok, cleared}.
 *
 * The un-prime response carries the count of cleared pending feeds, NOT a FeedResult — the wire
 * has no `tool`/`mode` here (`approval_api.py:332` returns `{"ok": True, "cleared": cleared}` where
 * `cleared` is the int from `skill_feed.clear_pending`). Decoding it as FeedResult failed on the
 * missing required `tool`/`mode` fields.
 */
@Serializable
data class ClearResult(
    val ok: Boolean,
    val cleared: Int,
)

/** The two user-pickable delivery modes; value is the wire string. */
enum class FeedMode(val wire: String) { Live("live"), Next("next") }
