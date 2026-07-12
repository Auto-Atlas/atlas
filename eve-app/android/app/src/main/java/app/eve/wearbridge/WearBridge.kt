package app.eve.wearbridge

import android.util.Log
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.ApprovalRepository
import app.eve.data.ApproveOutcome
import app.eve.data.DenyOutcome
import app.eve.data.EveWireJson
import app.eve.data.models.Approval
import app.eve.data.models.SystemStatus
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.Outcome
import app.eve.data.wear.StatusSnapshot
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.data.wear.WearLink
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Writes the retained phone->watch snapshots to the Data Layer (the watch reads them). */
interface SnapshotWriter {
    suspend fun writeApprovals(bytes: ByteArray)
    suspend fun writeStatus(bytes: ByteArray)

    /**
     * Writes the retained live-voice door config (URL + token) the watch's live client dials. Same
     * retained-DataItem mechanics as the two snapshots above; a blank URL is written honestly so the
     * watch shows "not configured" rather than a stale door.
     */
    suspend fun writeVoiceDoor(bytes: ByteArray)
}

/** Sends the per-action result message back to the watch node that requested it. */
interface ResultSender {
    suspend fun sendResult(nodeId: String, bytes: ByteArray)

    /**
     * Sends EVE's talk reply back on its OWN path ([WearLink.PATH_TALK_REPLY]) — deliberately NOT
     * [sendResult] / [WearLink.PATH_ACTION_RESULT], so the watch's approvals listener never sees
     * [TalkReply] bytes and the talk listener never sees a [WearActionResult].
     */
    suspend fun sendTalkReply(nodeId: String, bytes: ByteArray)
}

/**
 * The phone's gateway to approval_api over the Wearable Data Layer — the pure, testable core (no
 * GMS types). The watch never talks HTTP; it sends [WearAction] messages and reads snapshots this
 * bridge writes. approval_api is the SINGLE source of truth: after acting, the bridge always
 * re-fetches (never mutates a cached list locally), so the watch's next snapshot reflects the server.
 *
 * House rule — no silent fallbacks: every action maps to an honest [WearActionResult] naming WHICH
 * leg broke ([Outcome.SERVER_UNREACHABLE] for network/IO, [Outcome.UNAUTHORIZED] for 401, ...), and
 * a fetch failure still writes a snapshot with `serverReachable=false` + the real detail so the
 * watch can show "phone<->server leg down" instead of a stale-looking-current list.
 *
 * @param fetchPending fresh pending-approvals GET (usually [ApprovalRepository.pending]) — passed as
 *   a lambda so the refresh path is provably a FRESH fetch, not a cached list.
 */
class WearBridge(
    private val approvalRepository: ApprovalRepository,
    private val fetchPending: suspend () -> ApiResult<List<Approval>>,
    private val fetchStatus: suspend () -> ApiResult<SystemStatus>,
    private val snapshotWriter: SnapshotWriter,
    private val resultSender: ResultSender,
    // The watch push-to-talk leg: runs one transcribed utterance through EVE's full brain (usually
    // TalkRepository.ask). A lambda for the same reason as fetchPending/fetchStatus — the bridge core
    // stays pure and testable with a fake, no ApiClient in the unit tests.
    private val askEve: suspend (String) -> ApiResult<String>,
    // The live-voice door config the watch dials, resolved fresh (URL from Settings, token = the
    // existing connection bearer) — a lambda for the same reason as the fetch lambdas: the bridge core
    // stays pure/testable with a fake, no Settings/DataStore in the unit tests. Null when this bridge
    // isn't wired for live voice (older callers/tests) — then the door is simply never written.
    private val fetchVoiceDoor: suspend () -> app.eve.data.wear.VoiceDoorConfig? = { null },
    // Health v2: forwards one watch heart-rate alert to the sidecar (usually ApiClient.healthEvent).
    // Null = this bridge isn't wired for health alerts (older callers/tests) — an arriving alert is
    // then logged LOUDLY as undeliverable, never dropped silently.
    private val postHealthEvent: (suspend (app.eve.data.wear.HealthAlert) -> ApiResult<Unit>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Handles one inbound watch message. REFRESH pulls fresh snapshots; APPROVE/DENY decode a
     * [WearAction], act via the repo, send the honest result to the SOURCE node, then re-fetch both
     * snapshots. A malformed action payload never crashes: if the [WearAction.requestId] is
     * recoverable it returns an [Outcome.ERROR] result so the watch's pending button resolves; if
     * not, it logs loudly (never silent).
     */
    suspend fun handleAction(path: String, payload: ByteArray, sourceNodeId: String) {
        when (path) {
            WearLink.PATH_ACTION_REFRESH -> refreshSnapshots()

            WearLink.PATH_ACTION_APPROVE, WearLink.PATH_ACTION_DENY -> {
                val action = try {
                    WearAction.fromBytes(payload)
                } catch (t: Throwable) {
                    // Malformed payload: never silent. If the requestId is still recoverable, send an
                    // honest ERROR so the watch's pending button resolves instead of hanging; if not,
                    // log loudly (there is no correlation id to answer to).
                    val recoveredRequestId = tryRecoverRequestId(payload)
                    if (recoveredRequestId != null) {
                        val err = WearActionResult(recoveredRequestId, approvalId = "", Outcome.ERROR, "malformed action payload")
                        resultSender.sendResult(sourceNodeId, err.toBytes())
                    } else {
                        Log.e(TAG, "Malformed WearAction on $path from $sourceNodeId (no recoverable requestId): ${t.message}", t)
                    }
                    return
                }
                val result =
                    if (path == WearLink.PATH_ACTION_APPROVE) approveToResult(action)
                    else denyToResult(action)
                // Honest result to the node that asked...
                resultSender.sendResult(sourceNodeId, result.toBytes())
                // ...then refresh from a FRESH GET — the resolved item must vanish from (or the new
                // one appear in) the watch's next snapshot; the bridge never edits a cached list.
                refreshSnapshots()
            }

            WearLink.PATH_ACTION_TALK -> handleTalk(payload, sourceNodeId)

            WearLink.PATH_ACTION_HEALTH_EVENT -> handleHealthAlert(payload, sourceNodeId)

            else -> Log.e(TAG, "Unknown wear action path: $path (from $sourceNodeId)")
        }
    }

    /**
     * The watch push-to-talk leg. Decode the [TalkRequest], run the text through EVE's brain via
     * [askEve], and send an honest [TalkReply] to the SOURCE node on [WearLink.PATH_TALK_REPLY].
     *
     * Same malformed-payload recovery as approve/deny: a garbage payload never leaves the watch
     * hanging — if the requestId is recoverable an [Outcome.ERROR] reply goes back; if not, it is
     * logged loudly. A successful reply is truncated to [MAX_REPLY_CHARS] (Message payloads cap near
     * 100 KB) with a VISIBLE "… [truncated]" marker — never a silent cut.
     */
    private suspend fun handleTalk(payload: ByteArray, sourceNodeId: String) {
        val request = try {
            TalkRequest.fromBytes(payload)
        } catch (t: Throwable) {
            val recoveredRequestId = tryRecoverRequestId(payload)
            if (recoveredRequestId != null) {
                val err = TalkReply(recoveredRequestId, reply = null, outcome = Outcome.ERROR, detail = "malformed talk payload")
                resultSender.sendTalkReply(sourceNodeId, err.toBytes())
            } else {
                Log.e(TAG, "Malformed TalkRequest from $sourceNodeId (no recoverable requestId): ${t.message}", t)
            }
            return
        }
        val reply = askToReply(request)
        resultSender.sendTalkReply(sourceNodeId, reply.toBytes())
    }

    /**
     * Health v2: one watch heart-rate alert -> POST /v1/health/event. Fire-and-forget from the
     * watch's perspective (the watch's passive stream keeps re-evaluating; Samsung Health's own
     * high/low HR alert remains the OS-level safety net), so no result message — but every failure
     * leg is LOUD: malformed payload, unwired bridge, and a failed POST all Log.e with the cause.
     */
    private suspend fun handleHealthAlert(payload: ByteArray, sourceNodeId: String) {
        val alert = try {
            app.eve.data.wear.HealthAlert.fromBytes(payload)
        } catch (t: Throwable) {
            Log.e(TAG, "Malformed HealthAlert from $sourceNodeId: ${t.message}", t)
            return
        }
        val post = postHealthEvent
        if (post == null) {
            Log.e(TAG, "HealthAlert ${alert.requestId} arrived but this bridge has no postHealthEvent wired — alert NOT delivered")
            return
        }
        val result = try {
            post(alert)
        } catch (t: Throwable) {
            Log.e(TAG, "HealthAlert ${alert.requestId} POST threw — alert NOT delivered", t)
            return
        }
        when (result) {
            is ApiResult.Ok -> Log.i(TAG, "HealthAlert ${alert.requestId} (${alert.type} ${alert.bpm ?: "?"} bpm) delivered to EVE")
            is ApiResult.Err -> Log.e(TAG, "HealthAlert ${alert.requestId} POST failed: ${describe(result.error)} — alert NOT delivered")
        }
    }

    /** Run one utterance through EVE and map the result to an honest [TalkReply] (never a fake OK). */
    private suspend fun askToReply(request: TalkRequest): TalkReply {
        val result = try {
            askEve(request.text)
        } catch (t: Throwable) {
            // askEve returns an ApiResult, it doesn't throw — but an escaped exception must become an
            // honest ERROR, never a fake success.
            return TalkReply(request.requestId, reply = null, outcome = Outcome.ERROR, detail = t.message ?: t::class.simpleName)
        }
        return when (result) {
            is ApiResult.Ok -> TalkReply(request.requestId, reply = truncateReply(result.value), outcome = Outcome.OK)
            is ApiResult.Err -> apiErrorToTalkReply(request, result.error)
        }
    }

    /** Cap a reply to [MAX_REPLY_CHARS] with a VISIBLE marker — the truncation is never silent. */
    private fun truncateReply(reply: String): String =
        if (reply.length <= MAX_REPLY_CHARS) reply
        else reply.take(MAX_REPLY_CHARS) + "… [truncated]"

    /** Maps a transport/HTTP [ApiError] to its honest, named-leg talk [Outcome] — mirrors [apiErrorToResult]. */
    private fun apiErrorToTalkReply(request: TalkRequest, error: ApiError): TalkReply = when (error) {
        is ApiError.Offline -> talkReply(request, Outcome.SERVER_UNREACHABLE, error.cause)
        ApiError.NotConfigured -> talkReply(request, Outcome.SERVER_UNREACHABLE, "phone not connected to EVE")
        ApiError.Unauthorized -> talkReply(request, Outcome.UNAUTHORIZED, "unauthorized (401) — reconnect the phone")
        ApiError.NotFound -> talkReply(request, Outcome.ERROR, "EVE has no /v1/ask endpoint (404)")
        ApiError.AlreadyResolved -> talkReply(request, Outcome.ERROR, "unexpected 409 from EVE")
        is ApiError.Http -> talkReply(request, Outcome.ERROR, "HTTP ${error.status}: ${error.detail}")
        is ApiError.Decode -> talkReply(request, Outcome.ERROR, "decode error: ${error.message}")
        is ApiError.Unknown -> talkReply(request, Outcome.ERROR, error.message)
    }

    private fun talkReply(request: TalkRequest, outcome: Outcome, detail: String?) =
        TalkReply(request.requestId, reply = null, outcome = outcome, detail = detail)

    /**
     * Fetches pending approvals + status and writes BOTH snapshots. On success → `serverReachable=true`.
     * On an [ApiResult] failure → `serverReachable=false` + the real error detail: this IS the honest
     * "phone<->server leg down" signal to the watch. Both writes happen even when a fetch fails.
     */
    suspend fun refreshSnapshots() {
        writeApprovalsSnapshot()
        writeStatusSnapshot()
        writeVoiceDoorSnapshot()
    }

    /**
     * Writes the live-voice door config DataItem (URL + token) so the watch's live client always has
     * the current door. Resolved fresh via [fetchVoiceDoor]; when unwired (null) the door is simply
     * not written (never a fake/blank overwrite). A blank URL from Settings IS written — that is the
     * honest "not configured yet" signal the watch renders, never a guessed default.
     */
    private suspend fun writeVoiceDoorSnapshot() {
        val config = fetchVoiceDoor() ?: return
        snapshotWriter.writeVoiceDoor(config.toBytes())
    }

    private suspend fun writeApprovalsSnapshot() {
        val now = clock()
        val snapshot = when (val r = fetchPending()) {
            is ApiResult.Ok -> ApprovalsSnapshot(r.value, now, serverReachable = true)
            is ApiResult.Err -> ApprovalsSnapshot(emptyList(), now, serverReachable = false, errorDetail = describe(r.error))
        }
        snapshotWriter.writeApprovals(snapshot.toBytes())
    }

    private suspend fun writeStatusSnapshot() {
        val now = clock()
        val snapshot = when (val r = fetchStatus()) {
            is ApiResult.Ok -> StatusSnapshot(r.value, now, serverReachable = true)
            is ApiResult.Err -> StatusSnapshot(null, now, serverReachable = false, errorDetail = describe(r.error))
        }
        snapshotWriter.writeStatus(snapshot.toBytes())
    }

    private suspend fun approveToResult(action: WearAction): WearActionResult {
        val outcome = try {
            approvalRepository.approve(action.approvalId)
        } catch (t: Throwable) {
            // The repo returns outcomes, it doesn't throw — but if it ever did, an escaped exception
            // must become an honest ERROR, never a fake success.
            return result(action, Outcome.ERROR, t.message ?: t::class.simpleName)
        }
        return when (outcome) {
            ApproveOutcome.Sent -> result(action, Outcome.APPROVED)
            // Approved, but release() returned ok:false — the tool did NOT fire. This is a failure,
            // so it maps to ERROR with detail, NEVER to APPROVED (would swallow a failure).
            ApproveOutcome.SendFailed ->
                result(action, Outcome.ERROR, "approved but the tool could not complete (release ok:false)")
            ApproveOutcome.AlreadyResolved -> result(action, Outcome.ALREADY_RESOLVED)
            is ApproveOutcome.Failed -> apiErrorToResult(action, outcome.error)
        }
    }

    private suspend fun denyToResult(action: WearAction): WearActionResult {
        val outcome = try {
            approvalRepository.deny(action.approvalId)
        } catch (t: Throwable) {
            return result(action, Outcome.ERROR, t.message ?: t::class.simpleName)
        }
        return when (outcome) {
            DenyOutcome.Denied -> result(action, Outcome.DENIED)
            DenyOutcome.AlreadyResolved -> result(action, Outcome.ALREADY_RESOLVED)
            is DenyOutcome.Failed -> apiErrorToResult(action, outcome.error)
        }
    }

    /** Maps a transport/HTTP [ApiError] to its honest, named-leg [Outcome]. */
    private fun apiErrorToResult(action: WearAction, error: ApiError): WearActionResult = when (error) {
        // Network/IO failure — the phone<->server leg is down. Surface the real detail.
        is ApiError.Offline -> result(action, Outcome.SERVER_UNREACHABLE, error.cause)
        // No base URL/token yet — the phone can't reach the server at all. Same honest signal.
        ApiError.NotConfigured -> result(action, Outcome.SERVER_UNREACHABLE, "phone not connected to EVE")
        ApiError.Unauthorized -> result(action, Outcome.UNAUTHORIZED, "unauthorized (401) — reconnect the phone")
        ApiError.NotFound -> result(action, Outcome.NOT_FOUND, "approval no longer exists (404)")
        ApiError.AlreadyResolved -> result(action, Outcome.ALREADY_RESOLVED, "already handled (409)")
        is ApiError.Http -> result(action, Outcome.ERROR, "HTTP ${error.status}: ${error.detail}")
        is ApiError.Decode -> result(action, Outcome.ERROR, "decode error: ${error.message}")
        is ApiError.Unknown -> result(action, Outcome.ERROR, error.message)
    }

    private fun tryRecoverRequestId(payload: ByteArray): String? = try {
        EveWireJson.parseToJsonElement(String(payload, Charsets.UTF_8))
            .jsonObject["requestId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    private fun result(action: WearAction, outcome: Outcome, detail: String? = null) =
        WearActionResult(action.requestId, action.approvalId, outcome, detail)

    /** Human, honest one-liner for a fetch failure — the "which leg" detail the watch shows. */
    private fun describe(error: ApiError): String = when (error) {
        ApiError.NotConfigured -> "phone not connected to EVE"
        is ApiError.Offline -> "cannot reach EVE: ${error.cause}"
        ApiError.Unauthorized -> "unauthorized (401) — reconnect the phone"
        ApiError.NotFound -> "not found (404)"
        ApiError.AlreadyResolved -> "already handled (409)"
        is ApiError.Http -> "HTTP ${error.status}: ${error.detail}"
        is ApiError.Decode -> "decode error: ${error.message}"
        is ApiError.Unknown -> error.message
    }

    companion object {
        const val TAG = "WearBridge"

        /** Reply cap before a Message send (payloads cap near 100 KB); overflow gets a visible marker. */
        const val MAX_REPLY_CHARS = 20_000
    }
}
