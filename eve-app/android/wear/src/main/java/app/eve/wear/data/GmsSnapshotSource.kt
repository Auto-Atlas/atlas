package app.eve.wear.data

import android.content.Context
import android.util.Log
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.WearLink
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real [SnapshotSource] over the Play Services Wearable DataClient. Reads the retained
 * [ApprovalsSnapshot] the phone writes at [WearLink.PATH_APPROVALS_SNAPSHOT].
 *
 * [snapshots] wires a [callbackFlow]: it does ONE initial [DataClient.getDataItems] read to emit the
 * current retained value, then registers a [DataClient.OnDataChangedListener] for pushed updates.
 * The listener is added on collect and REMOVED in [awaitClose] — it lives only while collected, so
 * an idle/closed screen never holds a Data-Layer listener (battery honesty). Same success/failure
 * Task bridge as the wear scaffold (no kotlinx-coroutines-play-services dependency needed).
 *
 * House rule — a decode failure is LOUD, never silent: a corrupt payload is surfaced as a
 * `serverReachable=false` snapshot carrying an explicit decode-failure detail (which the ViewModel
 * renders as an error state), and logged. The flow stays alive so a subsequent good write recovers.
 */
class GmsSnapshotSource(context: Context) : SnapshotSource {

    private val dataClient: DataClient = Wearable.getDataClient(context.applicationContext)
    private val path = WearLink.PATH_APPROVALS_SNAPSHOT

    override fun snapshots(): Flow<ApprovalsSnapshot> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == path) {
                    decodeApprovalsSnapshot(event.dataItem.data)?.let { trySend(it) }
                }
            }
        }

        // 1) Initial read of the retained item (so we don't wait for the next phone write).
        try {
            val buffer = awaitDataClientTask { dataClient.dataItems }
            try {
                buffer.firstOrNull { it.uri.path == path }
                    ?.let { decodeApprovalsSnapshot(it.data) }
                    ?.let { trySend(it) }
            } finally {
                buffer.release()
            }
        } catch (t: Throwable) {
            // The INITIAL read failed (Play Services problem) — surface it, don't swallow. The push
            // listener below may still deliver a later write.
            Log.e(TAG, "Initial approvals snapshot read failed: ${t.message}", t)
            trySend(
                ApprovalsSnapshot(
                    approvals = emptyList(),
                    fetchedAtEpochMs = System.currentTimeMillis(),
                    serverReachable = false,
                    errorDetail = "watch could not read the phone's snapshot: ${t.message}",
                ),
            )
        }

        // 2) Push updates for as long as this flow is collected.
        dataClient.addListener(listener)
        awaitClose { dataClient.removeListener(listener) }
    }

    override suspend fun current(): ApprovalsSnapshot? {
        val buffer = awaitDataClientTask { dataClient.dataItems }
        return try {
            buffer.firstOrNull { it.uri.path == path }?.let { decodeApprovalsSnapshot(it.data) }
        } finally {
            buffer.release()
        }
    }

    private companion object {
        const val TAG = "GmsSnapshotSource"
    }
}
