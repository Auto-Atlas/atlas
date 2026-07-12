package app.eve.wear.notify

import app.eve.data.wear.Outcome
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.data.wear.WearLink
import app.eve.wear.approvals.WearActionCopy
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The terminal notification-copy a one-tap Deny resolves to. [message] is the EXACT wrist wording;
 * [autoDismiss] is true only for the safe terminal states (denied / already handled) so the
 * notification clears itself shortly after — a failure stays visible so it is never silently lost.
 */
data class DenyUpdate(val message: String, val autoDismiss: Boolean) {
    companion object {
        /** Watch<->phone Data Layer down: the send never left the watch (no gateway / send failed). */
        val DataLayerDown = DenyUpdate(WearActionCopy.DATA_LAYER_DOWN, autoDismiss = false)

        /** Sent, but no correlated reply inside the window — honest, never a fake "denied". */
        val NoReply = DenyUpdate("No reply from phone — check the EVE app", autoDismiss = false)

        /** Map a real phone result to wrist copy via the single-source [WearActionCopy]. */
        fun fromResult(result: WearActionResult): DenyUpdate {
            val message = WearActionCopy.forResult(result, isInvoice = false).message
            // Only the truly-resolved states clear themselves; failures stay for the owner to see.
            val autoDismiss = result.outcome == Outcome.DENIED || result.outcome == Outcome.ALREADY_RESOLVED
            return DenyUpdate(message, autoDismiss)
        }
    }
}

/**
 * The testable core of the wrist Deny: send ONE deny [WearAction] over the [GatewayClient] seam and
 * await its correlated [WearActionResult], turning every leg into an honest [DenyUpdate]. No Android,
 * no notification — the [WearDenyReceiver] shell drives this and renders the result.
 *
 * The result collector is started BEFORE the send (inside a [coroutineScope]) so a fast phone reply
 * can never be missed; the whole thing is bounded by [timeoutMs] (well inside the ~10s goAsync
 * window) so a silent phone becomes [DenyUpdate.NoReply], never a fabricated success.
 */
class DenyFlow(
    private val gateway: GatewayClient,
    private val timeoutMs: Long = 8_000L,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun deny(approvalId: String): DenyUpdate = coroutineScope {
        val requestId = newRequestId()
        val result = kotlinx.coroutines.CompletableDeferred<WearActionResult>()
        // Subscribe first: the collector wins the race against a fast reply, then self-cancels.
        val collector = launch {
            gateway.results().collect { if (it.requestId == requestId) result.complete(it) }
        }
        try {
            when (val send = gateway.sendAction(WearLink.PATH_ACTION_DENY, WearAction(requestId, approvalId))) {
                // Leg 1 — the send never left the watch. Immediate, named, honest.
                SendOutcome.NoGatewayNode, is SendOutcome.SendFailed -> DenyUpdate.DataLayerDown
                // Sent — await the correlated reply; silence past the window is an honest failure.
                SendOutcome.Sent ->
                    withTimeoutOrNull(timeoutMs) { result.await() }
                        ?.let { DenyUpdate.fromResult(it) }
                        ?: DenyUpdate.NoReply
            }
        } finally {
            collector.cancel()
        }
    }
}
