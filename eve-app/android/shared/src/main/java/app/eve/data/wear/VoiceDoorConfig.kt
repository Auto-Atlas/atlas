package app.eve.data.wear

import kotlinx.serialization.Serializable

/**
 * The phone->watch LIVE-VOICE door config, retained on the Data Layer at [WearLink.PATH_VOICE_DOOR]
 * and shared VERBATIM by the phone (:app bridge) and the watch (:wear live client) so the two can
 * never drift on the shape. The watch's live-voice client dials [wsUrl] (the owner's public TLS voice
 * door, e.g. `wss://eve-voice.<domain>/v1/watch/voice`) and authenticates with [token] (the existing
 * EVE_APP_TOKEN — the SAME bearer the phone uses; nothing owner-specific is baked into the app).
 *
 * Honesty: NOTHING is hardcoded on the watch. A blank [wsUrl] is the explicit "not configured yet"
 * signal (the watch renders "No voice door configured — set it in phone Settings."), never a guessed
 * default. Encoded/decoded with [WearLink.encode]/[EveWireJson], identical bytes both sides; garbage
 * bytes THROW (fail loudly, never a fake decode).
 */
@Serializable
data class VoiceDoorConfig(
    val wsUrl: String,
    val token: String,
) {
    /** True when there is a real door to dial — a non-blank URL. Token blankness is a server concern. */
    val isConfigured: Boolean get() = wsUrl.isNotBlank()

    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): VoiceDoorConfig = WearLink.decode(serializer(), bytes)
    }
}
