package app.eve.wearbridge

import android.content.Context
import android.util.Log
import app.eve.data.wear.WearLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real Data-Layer-backed [SnapshotWriter] + [ResultSender]. Snapshots go out as retained DataItems
 * (setUrgent so the watch syncs promptly); results go out as MessageClient messages to the asking
 * node. Wraps the callback-based GMS Tasks in suspend functions (same success/failure-listener
 * bridge as wear/WearableNodeSource — no kotlinx-coroutines-play-services dependency needed).
 *
 * A GMS Task failure (e.g. NO watch paired — putDataItem still works without one, but a transport
 * error can occur) is caught and Log.e'd with a distinctive tag: the phone UI must NOT crash because
 * no watch is around. It is NEVER faked as success — the write simply didn't land, and the next
 * refresh trigger will try again.
 */
class GmsWearGateway(context: Context) : SnapshotWriter, ResultSender {

    private val dataClient = Wearable.getDataClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)

    override suspend fun writeApprovals(bytes: ByteArray) =
        putDataItem(WearLink.PATH_APPROVALS_SNAPSHOT, bytes)

    override suspend fun writeStatus(bytes: ByteArray) =
        putDataItem(WearLink.PATH_STATUS_SNAPSHOT, bytes)

    override suspend fun writeVoiceDoor(bytes: ByteArray) =
        putDataItem(WearLink.PATH_VOICE_DOOR, bytes)

    private suspend fun putDataItem(path: String, bytes: ByteArray) {
        val request = PutDataRequest.create(path).setData(bytes).setUrgent()
        try {
            awaitTask { dataClient.putDataItem(request) }
        } catch (t: Throwable) {
            Log.e(TAG, "Data Layer write to $path failed (watch may be unpaired/unreachable): ${t.message}", t)
        }
    }

    override suspend fun sendResult(nodeId: String, bytes: ByteArray) {
        try {
            awaitTask { messageClient.sendMessage(nodeId, WearLink.PATH_ACTION_RESULT, bytes) }
        } catch (t: Throwable) {
            Log.e(TAG, "Result send to node $nodeId failed: ${t.message}", t)
        }
    }

    override suspend fun sendTalkReply(nodeId: String, bytes: ByteArray) {
        // Own path (PATH_TALK_REPLY), never PATH_ACTION_RESULT — the watch's talk listener and
        // approvals listener filter on different paths and must never cross-decode.
        try {
            awaitTask { messageClient.sendMessage(nodeId, WearLink.PATH_TALK_REPLY, bytes) }
        } catch (t: Throwable) {
            Log.e(TAG, "Talk reply send to node $nodeId failed: ${t.message}", t)
        }
    }

    /**
     * Task bridge with an honest TIMEOUT: a Wearable Task can hang indefinitely when the transport
     * is in a bad state (observed watch-side on the Wear OS 5 emulator, 2026-07-10). A hang becomes
     * a thrown IllegalStateException that the callers' existing catch paths Log.e loudly — the
     * bridge's service scope must never be stuck forever on one write.
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

    companion object {
        private const val TAG = "GmsWearGateway"
        private const val TIMEOUT_MS = 10_000L
    }
}
