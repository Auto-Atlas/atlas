package app.eve.wear.data

import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import kotlinx.coroutines.flow.Flow

/**
 * Seam over the watch->phone action channel (MessageClient). The watch never talks HTTP; it sends a
 * [WearAction] to the phone gateway node and awaits the phone's honest [WearActionResult]. Fakeable
 * in tests (manual DI, no mocking library). The GMS impl finds the gateway node via CapabilityClient
 * ([app.eve.data.wear.WearLink.CAPABILITY_EVE_GATEWAY]).
 */
interface GatewayClient {
    /**
     * Send one approve/deny action to the phone gateway. Returns a [SendOutcome] naming exactly what
     * happened to the SEND (not the approval): [SendOutcome.NoGatewayNode] is the honest
     * "watch<->phone Data Layer down" leg, [SendOutcome.SendFailed] carries the real transport
     * reason, [SendOutcome.Sent] means the message left the watch (the actual approval result then
     * arrives on [results]).
     */
    suspend fun sendAction(path: String, action: WearAction): SendOutcome

    /**
     * Incoming per-action results the phone pushes back
     * ([app.eve.data.wear.WearLink.PATH_ACTION_RESULT]). Push-based (MessageClient listener) — no
     * polling; the listener lives only while collected.
     */
    fun results(): Flow<WearActionResult>

    /**
     * Ask the phone to write fresh snapshots NOW (empty-payload refresh message). Called ONCE when
     * the watch app comes to the foreground — never on a timer/loop. Returns the honest
     * [SendOutcome] of the refresh SEND: [SendOutcome.NoGatewayNode] is how the watch learns "a
     * phone is connected but Atlas isn't reachable on it" (a node without the gateway capability) —
     * the ViewModel turns that into a named state instead of spinning forever (gap found on the
     * Wear OS 5 emulator, 2026-07-10).
     */
    suspend fun requestRefresh(): SendOutcome

    /**
     * Send one push-to-talk utterance ([TalkRequest]) to the phone gateway. Same honest [SendOutcome]
     * semantics as [sendAction] — [SendOutcome.NoGatewayNode] is the Data-Layer-down leg,
     * [SendOutcome.SendFailed] carries the real reason, [SendOutcome.Sent] means the message left the
     * watch (Atlas's answer then arrives on [talkReplies]).
     */
    suspend fun sendTalk(request: TalkRequest): SendOutcome

    /**
     * Incoming talk replies the phone pushes back on [app.eve.data.wear.WearLink.PATH_TALK_REPLY] —
     * a SEPARATE channel from [results], so an approvals result and a talk reply can never
     * cross-decode. Push-based (MessageClient listener); the listener lives only while collected.
     */
    fun talkReplies(): Flow<TalkReply>

    /**
     * Health v2: send one passive heart-rate alert ([app.eve.data.wear.HealthAlert]) to the phone
     * gateway ([app.eve.data.wear.WearLink.PATH_ACTION_HEALTH_EVENT]); the phone POSTs it to the
     * sidecar and Atlas warns in her voice. Same honest [SendOutcome] semantics as [sendAction] —
     * a failed send is logged by the caller, never swallowed (Samsung Health's own HR alert stays
     * the OS-level safety net).
     */
    suspend fun sendHealthAlert(alert: app.eve.data.wear.HealthAlert): SendOutcome
}

/** The honest outcome of a single message SEND — never a fake "sent" when the leg is down. */
sealed interface SendOutcome {
    /** The message left the watch for the gateway node. The approval result arrives on [GatewayClient.results]. */
    data object Sent : SendOutcome

    /** No reachable node advertises the gateway capability — the watch<->phone Data Layer leg is down. */
    data object NoGatewayNode : SendOutcome

    /** The gateway node was found but the MessageClient send failed. [reason] is the real detail. */
    data class SendFailed(val reason: String) : SendOutcome
}
