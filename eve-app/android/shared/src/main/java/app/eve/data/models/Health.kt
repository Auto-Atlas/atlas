package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /v1/health -> {ok, service, pending, releasing_orphans, remote_approval_enabled, thinking_enabled}. */
@Serializable
data class Health(
    val ok: Boolean,
    val service: String,
    val pending: Int,
    @SerialName("releasing_orphans") val releasingOrphans: Int,
    @SerialName("remote_approval_enabled") val remoteApprovalEnabled: Boolean,
    // Default false so an older backend that omits the field still decodes (Epic T).
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean = false,
    // "Let me interrupt EVE" toggle; default false so an older backend still decodes.
    @SerialName("barge_in_enabled") val bargeInEnabled: Boolean = false,
)
