package app.eve.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for pipecat's SmallWebRTC signaling (`/api/offer`), matching the server's
 * `SmallWebRTCRequest` / `SmallWebRTCPatchRequest` dataclasses VERBATIM (pipecat
 * `request_handler.py`). These types have ZERO `org.webrtc` imports so they stay
 * JVM-unit-testable.
 *
 * CRITICAL (verified against the live phone_bot fixture + request_handler.py:61-63 / 252-253):
 * the ICE candidate sub-fields are **snake_case** — `sdp_mid` / `sdp_mline_index`. The server
 * does `IceCandidate(**c)` against snake_case dataclass fields, so camelCase keys would 422 on
 * every trickle and ICE would never complete (a silent no-audio bug). Pinned via @SerialName.
 *
 * Nullable fields default to null and are dropped on the wire by the Json config used at the
 * boundary (`explicitNulls = false`, mirroring [app.eve.data.ApiClient.DEFAULT_JSON]) — so an
 * absent `pc_id` is omitted rather than serialized as `null`, exactly like the JS client.
 */
@Serializable
data class SdpRequest(
    val sdp: String,
    val type: String,
    @SerialName("pc_id") val pcId: String? = null,
    @SerialName("restart_pc") val restartPc: Boolean? = null,
)

@Serializable
data class SdpAnswer(
    val sdp: String,
    val type: String,
    @SerialName("pc_id") val pcId: String,
)

@Serializable
data class IceCandidatePatch(
    val candidate: String,
    @SerialName("sdp_mid") val sdpMid: String,
    @SerialName("sdp_mline_index") val sdpMlineIndex: Int,
)

@Serializable
data class IcePatch(
    @SerialName("pc_id") val pcId: String,
    val candidates: List<IceCandidatePatch>,
)
