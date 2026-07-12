package app.eve.data.models

import kotlinx.serialization.Serializable

/**
 * POST /v1/push/register -> {"ok": true, "wake": "05:00 …"}.
 *
 * The server records this device's FCM token + the wake time it should fire the morning ritual at,
 * then echoes back the resolved wake string. [wake] is optional so an older/leaner server reply
 * still decodes.
 */
@Serializable
data class PushRegisterResult(
    val ok: Boolean,
    val wake: String? = null,
)
