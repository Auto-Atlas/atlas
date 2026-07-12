package app.eve.data.wear

import app.eve.data.EveWireJson
import app.eve.data.models.Approval
import app.eve.data.models.SystemStatus
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable

/**
 * THE phone<->watch Wearable Data Layer contract. Shared VERBATIM by the phone (bridge, :app) and
 * the watch (:wear) so the two can never drift on paths or payload shapes. The phone is the ONLY
 * node that talks HTTP to approval_api; the watch reads the retained snapshots below and sends the
 * action messages below. Every payload is encoded with [EveWireJson] (the one canonical wire Json)
 * to UTF-8 bytes, so a DTO written by the phone decodes identically on the watch.
 */
object WearLink {
    // ---- Capability (how the watch DISCOVERS the phone gateway node) ----
    /**
     * Data-Layer capability the PHONE advertises (via app/res/values/wear.xml
     * `android_wear_capabilities`) and the WATCH resolves through CapabilityClient to find WHICH
     * connected node is the gateway. Zero reachable nodes with this capability == the honest
     * "watch<->phone Data Layer leg is down" signal (never a fake "sent"). Must match the phone's
     * declared capability string VERBATIM.
     */
    const val CAPABILITY_EVE_GATEWAY = "eve_gateway"

    // ---- Notification bridging (who OWNS the approval notification on the wrist) ----
    /**
     * Wear notification bridge tag carried ONLY by the phone's approval notification
     * (NotificationCompat.WearableExtender.setBridgeTag). The watch registers this tag as EXCLUDED
     * from auto-bridging (BridgingManager) so the phone's approval notification never mirrors to the
     * wrist — the watch posts its OWN native approval notification instead (hold-to-approve lives
     * there). Every OTHER phone notification (morning ritual, reminders, stream) keeps default
     * bridging, so the owner still gets those on the wrist automatically. The two sides MUST use this
     * exact string or the exclusion silently no-ops and the owner gets a double notification.
     */
    const val BRIDGE_TAG_APPROVAL = "eve_approval"

    // ---- DataClient item paths (retained state the watch READS) ----
    /** Latest pending-approvals snapshot (retained; the watch reads the newest). */
    const val PATH_APPROVALS_SNAPSHOT = "/eve/approvals"

    /** Latest engine/cost status snapshot (retained). */
    const val PATH_STATUS_SNAPSHOT = "/eve/status"

    /**
     * Latest LIVE-VOICE door config (retained) the phone writes so the watch's live-voice client
     * knows WHERE to dial and with WHICH bearer. Payload = [VoiceDoorConfig]. Nothing is hardcoded on
     * the watch: a blank [VoiceDoorConfig.wsUrl] is the honest "not configured yet" signal, and an
     * absent DataItem means the phone has never written one. Retained + setUrgent, exactly like the
     * approvals/status snapshots.
     */
    const val PATH_VOICE_DOOR = "/eve/voice_door"

    // ---- MessageClient paths (transient actions watch->phone, result phone->watch) ----
    /** Watch->phone: approve one pending approval. Payload = [WearAction]. */
    const val PATH_ACTION_APPROVE = "/eve/action/approve"

    /** Watch->phone: deny one pending approval. Payload = [WearAction]. */
    const val PATH_ACTION_DENY = "/eve/action/deny"

    /** Watch->phone: "pull fresh snapshots now" (e.g. on watch app open). Empty payload. */
    const val PATH_ACTION_REFRESH = "/eve/action/refresh"

    /** Phone->watch: the honest per-action outcome. Payload = [WearActionResult]. */
    const val PATH_ACTION_RESULT = "/eve/action/result"

    /**
     * Watch->phone: one push-to-talk utterance (already transcribed on the watch) to run through
     * Atlas's full brain. Payload = [TalkRequest]. MUST stay under `/eve/action/` — [WearBridgeService]
     * drops any path that isn't in that namespace in code (its own guard), even though the manifest
     * intent-filter is only scoped to `/eve`.
     */
    const val PATH_ACTION_TALK = "/eve/action/talk"

    /**
     * Watch->phone: one passive heart-rate threshold alert (Health v2 — "if my heart rate jumps
     * she can warn me"). Payload = [HealthAlert]. The phone POSTs it to approval_api's
     * /v1/health/event; the sidecar's initiative engine turns it into Atlas's spoken warning.
     * Under `/eve/action/` for the same [WearBridgeService] namespace guard as talk.
     */
    const val PATH_ACTION_HEALTH_EVENT = "/eve/action/health_event"

    /**
     * Phone->watch: Atlas's reply to a talk request. Payload = [TalkReply]. DELIBERATELY separate from
     * [PATH_ACTION_RESULT]: the approvals listener filters on that path and must never decode a
     * [TalkReply] as a [WearActionResult] (and vice-versa). Reply bytes ride their own channel.
     */
    const val PATH_TALK_REPLY = "/eve/talk/reply"

    // ---- ChannelClient path (v2 native voice turn — raw audio both directions) ----
    /**
     * THE single bidirectional ChannelClient path for one native voice turn (v2). The watch OPENS a
     * channel to the phone gateway node on this path and writes a len-prefixed [VoiceTurnRequest]
     * envelope followed by the recorded WAV bytes (see [VoiceEnvelope]); the phone runs it through
     * Atlas's own speech stack and writes back a [VoiceTurnReply] envelope + raw PCM on the SAME
     * channel. A ChannelClient (not a Message) because the audio payloads exceed the ~100 KB Message
     * cap. Deliberately under `/eve/` so it rides the same manifest path-prefix scoping as every other
     * Data-Layer surface, but its OWN path so no Message listener ever sees channel bytes.
     */
    const val PATH_VOICE_TURN = "/eve/voice/turn"

    /**
     * One place that turns a @Serializable payload into Data-Layer bytes and back, so every DTO
     * encodes identically. [EveWireJson] tolerates unknown keys (server schema drift never crashes a
     * client decode), but a hard decode failure (corrupt/garbage bytes) THROWS — a wear action
     * result must fail loudly, never decode to a fake success.
     */
    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        EveWireJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        EveWireJson.decodeFromString(deserializer, String(bytes, Charsets.UTF_8))
}

/**
 * Retained snapshot of the pending approvals the watch mirrors. Reuses the phone's [Approval] model
 * — single source of truth. When the phone CANNOT reach approval_api it STILL writes a snapshot with
 * [serverReachable] = false and the real [errorDetail], so the watch can render "phone<->server leg
 * down" honestly instead of showing a stale or empty list as if it were current.
 */
@Serializable
data class ApprovalsSnapshot(
    val approvals: List<Approval>,
    val fetchedAtEpochMs: Long,
    val serverReachable: Boolean,
    val errorDetail: String? = null,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): ApprovalsSnapshot = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Retained snapshot of the engine/cost status. [status] is null exactly when the phone could not
 * reach the server ([serverReachable] = false), carrying the real [errorDetail] — the same honest
 * "leg down" signal as [ApprovalsSnapshot].
 */
@Serializable
data class StatusSnapshot(
    // Defaulted so explicitNulls=false (EveWireJson) can drop a null status on the wire and it still
    // decodes back to null instead of failing on a missing field.
    val status: SystemStatus? = null,
    val fetchedAtEpochMs: Long,
    val serverReachable: Boolean,
    val errorDetail: String? = null,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): StatusSnapshot = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Watch->phone action payload for approve/deny. [requestId] is a watch-generated correlation id so
 * the watch can match the [WearActionResult] back to the button it tapped; [approvalId] is the
 * approval row id. (The refresh action carries no payload.)
 */
@Serializable
data class WearAction(
    val requestId: String,
    val approvalId: String,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): WearAction = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Phone->watch result of one approve/deny. Every [outcome] maps 1:1 from the phone's existing
 * ApproveOutcome/DenyOutcome/ApiError semantics — the named-leg honesty contract. No outcome ever
 * swallows a failure into success; a partial "approved but the tool didn't fire" is [Outcome.ERROR]
 * with detail, NOT [Outcome.APPROVED].
 */
@Serializable
data class WearActionResult(
    val requestId: String,
    val approvalId: String,
    val outcome: Outcome,
    val detail: String? = null,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): WearActionResult = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Watch->phone push-to-talk request. [requestId] is a watch-generated correlation id so the watch
 * can match the [TalkReply] back to the pending utterance; [text] is the on-watch STT transcript
 * (never blank — an empty transcript is rejected on the watch as a failure, never sent).
 */
@Serializable
data class TalkRequest(
    val requestId: String,
    val text: String,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): TalkRequest = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Watch->phone passive heart-rate alert (Health v2). Raised by the watch's Health Services
 * passive monitor when a threshold goal fires; the phone forwards it to the sidecar, where the
 * initiative engine judges context and Atlas warns in her own voice. [observedAtEpochMs] is the
 * watch's observation time — the SERVER stamps its own receive time too (device clocks are not
 * trusted for staleness math, matching the snapshot rule).
 */
@Serializable
data class HealthAlert(
    val requestId: String,
    val type: String,               // "hr_high" | "hr_low" | future kinds
    val bpm: Int? = null,
    val thresholdBpm: Int? = null,
    val observedAtEpochMs: Long,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): HealthAlert = WearLink.decode(serializer(), bytes)
    }
}

/**
 * Phone->watch reply to one [TalkRequest]. [reply] is Atlas's answer text (present exactly when
 * [outcome] is [Outcome.OK]); on any failure leg it is null and [detail] carries the real reason.
 * The same named-leg honesty as [WearActionResult]: a leg that broke is [Outcome.SERVER_UNREACHABLE]
 * / [Outcome.UNAUTHORIZED] / [Outcome.ERROR] with detail, never a fake OK with empty text.
 */
@Serializable
data class TalkReply(
    val requestId: String,
    val reply: String? = null,
    val outcome: Outcome,
    val detail: String? = null,
) {
    fun toBytes(): ByteArray = WearLink.encode(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): TalkReply = WearLink.decode(serializer(), bytes)
    }
}

/** The honest, exhaustive set of action outcomes the watch can render. Lowercase on the wire. */
@Serializable
enum class Outcome {
    @SerialName("approved")
    APPROVED,

    /** Talk leg succeeded — Atlas answered. [TalkReply.reply] carries the answer text. */
    @SerialName("ok")
    OK,

    @SerialName("denied")
    DENIED,

    @SerialName("already_resolved")
    ALREADY_RESOLVED,

    @SerialName("unauthorized")
    UNAUTHORIZED,

    @SerialName("not_found")
    NOT_FOUND,

    @SerialName("server_unreachable")
    SERVER_UNREACHABLE,

    @SerialName("error")
    ERROR,
}
