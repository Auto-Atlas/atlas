package app.eve.wear.data

import android.content.Context
import android.util.Log
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.data.wear.WearLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real [GatewayClient] over Play Services. Finds the phone gateway node via
 * [CapabilityClient.getCapability] ([WearLink.CAPABILITY_EVE_GATEWAY], reachable nodes only) and
 * sends [WearAction] messages to it; the phone's [WearActionResult] arrives via a MessageClient
 * listener.
 *
 * House rule — no silent fallback: ZERO capable reachable nodes maps to [SendOutcome.NoGatewayNode]
 * (the honest "watch<->phone Data Layer down" leg), and a MessageClient send failure maps to
 * [SendOutcome.SendFailed] with the real reason — never a fake "sent". Same Task success/failure
 * bridge as the wear scaffold (no kotlinx-coroutines-play-services dependency).
 */
class GmsGatewayClient(context: Context) : GatewayClient {

    private val appContext = context.applicationContext
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(appContext)
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)

    override suspend fun sendAction(path: String, action: WearAction): SendOutcome {
        val node = gatewayNode() ?: return SendOutcome.NoGatewayNode
        return try {
            awaitTask { messageClient.sendMessage(node.id, path, action.toBytes()) }
            SendOutcome.Sent
        } catch (t: Throwable) {
            Log.e(TAG, "sendAction $path to ${node.id} failed: ${t.message}", t)
            SendOutcome.SendFailed(t.message ?: t::class.simpleName ?: "send failed")
        }
    }

    override fun results(): Flow<WearActionResult> = callbackFlow {
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == WearLink.PATH_ACTION_RESULT) {
                try {
                    trySend(WearActionResult.fromBytes(event.data))
                } catch (t: Throwable) {
                    // A garbage result message is a real fault — log loudly, never crash the flow.
                    Log.e(TAG, "Corrupt action-result message dropped: ${t.message}", t)
                }
            }
        }
        messageClient.addListener(listener)
        awaitClose { messageClient.removeListener(listener) }
    }

    override suspend fun sendTalk(request: TalkRequest): SendOutcome {
        val node = gatewayNode() ?: return SendOutcome.NoGatewayNode
        return try {
            awaitTask { messageClient.sendMessage(node.id, WearLink.PATH_ACTION_TALK, request.toBytes()) }
            SendOutcome.Sent
        } catch (t: Throwable) {
            Log.e(TAG, "sendTalk to ${node.id} failed: ${t.message}", t)
            SendOutcome.SendFailed(t.message ?: t::class.simpleName ?: "send failed")
        }
    }

    override fun talkReplies(): Flow<TalkReply> = callbackFlow {
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == WearLink.PATH_TALK_REPLY) {
                try {
                    trySend(TalkReply.fromBytes(event.data))
                } catch (t: Throwable) {
                    // A garbage talk reply is a real fault — log loudly, never crash the flow.
                    Log.e(TAG, "Corrupt talk-reply message dropped: ${t.message}", t)
                }
            }
        }
        messageClient.addListener(listener)
        awaitClose { messageClient.removeListener(listener) }
    }

    override suspend fun sendHealthAlert(alert: app.eve.data.wear.HealthAlert): SendOutcome {
        val node = gatewayNode() ?: return SendOutcome.NoGatewayNode
        return try {
            awaitTask { messageClient.sendMessage(node.id, WearLink.PATH_ACTION_HEALTH_EVENT, alert.toBytes()) }
            SendOutcome.Sent
        } catch (t: Throwable) {
            Log.e(TAG, "sendHealthAlert ${alert.requestId} to ${node.id} failed: ${t.message}", t)
            SendOutcome.SendFailed(t.message ?: t::class.simpleName ?: "send failed")
        }
    }

    override suspend fun requestRefresh(): SendOutcome {
        val node = gatewayNode()
        if (node == null) {
            Log.w(TAG, "requestRefresh: no gateway node reachable — cannot pull fresh snapshots")
            return SendOutcome.NoGatewayNode
        }
        return try {
            awaitTask { messageClient.sendMessage(node.id, WearLink.PATH_ACTION_REFRESH, ByteArray(0)) }
            SendOutcome.Sent
        } catch (t: Throwable) {
            Log.e(TAG, "requestRefresh send failed: ${t.message}", t)
            SendOutcome.SendFailed(t.message ?: t::class.simpleName ?: "send failed")
        }
    }

    /** The nearest reachable node advertising the gateway capability, or null if none. */
    private suspend fun gatewayNode(): Node? {
        val info = try {
            awaitTask {
                capabilityClient.getCapability(WearLink.CAPABILITY_EVE_GATEWAY, CapabilityClient.FILTER_REACHABLE)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Capability lookup failed: ${t.message}", t)
            return null
        }
        val nodes = info.nodes
        // Prefer a directly-connected (nearby) node; else any reachable one.
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    /**
     * Task bridge with an honest TIMEOUT: on an unpaired watch a Wearable Task can never complete
     * (observed on the Wear OS 5 emulator, 2026-07-10). A hang becomes a thrown
     * IllegalStateException naming the Data Layer leg — the existing catch paths turn it into
     * [SendOutcome.SendFailed]/a logged capability failure instead of waiting silently forever.
     */
    private suspend fun <T> awaitTask(start: () -> Task<T>): T =
        try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    start()
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { e -> cont.resumeWithException(e) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Data Layer not responding (timed out after ${TIMEOUT_MS / 1000}s)")
        }

    private companion object {
        const val TAG = "GmsGatewayClient"
        const val TIMEOUT_MS = 10_000L
    }
}
