package app.eve.data.models

import kotlinx.serialization.Serializable

/**
 * POST /v1/identity -> {ok, user, nick, whys:<count>}.
 *
 * Every field except `ok` is echoed back from what was persisted; all default so a partial
 * write (e.g. only `whys`, or only `user`/`nick`) still decodes cleanly. `whys` is a COUNT
 * (the server returns the number stored), not the list.
 */
@Serializable
data class IdentityResult(
    val ok: Boolean,
    val user: String? = null,
    val nick: String? = null,
    val whys: Int = 0,
)

/**
 * POST /v1/enroll -> {ok, name, tier, clips:<n>}.
 *
 * `clips` is the number of voiceprint clips the server accepted. Defaults so an older/sparser
 * response still decodes.
 */
@Serializable
data class EnrollResult(
    val ok: Boolean,
    val name: String? = null,
    val tier: String? = null,
    val clips: Int = 0,
)
