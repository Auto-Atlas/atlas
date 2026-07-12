package app.eve.wear.data

import android.util.Log
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.StatusSnapshot
import app.eve.data.wear.WearLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared, one-shot reads of the retained snapshots the phone writes on the Data Layer. This is the
 * SINGLE place both DataItem paths are parsed, so the live in-app flow ([GmsSnapshotSource]) and the
 * push-driven Tile / complication reader ([app.eve.wear.tile.TileStateReader]) never duplicate the
 * decode/error logic.
 *
 * House rule — a decode failure is LOUD, never silent: a corrupt payload becomes a
 * `serverReachable=false` snapshot carrying an explicit decode-failure detail (surfaced on-screen /
 * as ServerDown), and is logged. Absent bytes stay null (nothing has been written yet).
 */

/** Both retained snapshots read together. Either is null when the phone has never written that one. */
data class LatestSnapshots(
    val approvals: ApprovalsSnapshot?,
    val status: StatusSnapshot?,
)

/**
 * Reads the approvals + status DataItems in a single [DataClient.getDataItems] pass and decodes
 * each. Missing item -> null (honest "never written"), corrupt item -> a loud error snapshot.
 */
internal suspend fun DataClient.latestSnapshots(): LatestSnapshots {
    val buffer = awaitDataClientTask { dataItems }
    return try {
        var approvals: ApprovalsSnapshot? = null
        var status: StatusSnapshot? = null
        buffer.forEach { item ->
            when (item.uri.path) {
                WearLink.PATH_APPROVALS_SNAPSHOT -> approvals = decodeApprovalsSnapshot(item.data)
                WearLink.PATH_STATUS_SNAPSHOT -> status = decodeStatusSnapshot(item.data)
            }
        }
        LatestSnapshots(approvals, status)
    } finally {
        buffer.release()
    }
}

/**
 * Decode an approvals DataItem payload, or turn a corrupt payload into a LOUD error snapshot (never
 * a silent null-drop). Returns null only when the bytes are absent (nothing to decode yet).
 */
internal fun decodeApprovalsSnapshot(bytes: ByteArray?): ApprovalsSnapshot? {
    if (bytes == null) return null
    return try {
        ApprovalsSnapshot.fromBytes(bytes)
    } catch (t: Throwable) {
        Log.e(TAG, "Corrupt approvals snapshot payload — surfacing as error state: ${t.message}", t)
        ApprovalsSnapshot(
            approvals = emptyList(),
            fetchedAtEpochMs = System.currentTimeMillis(),
            serverReachable = false,
            errorDetail = "watch could not read the phone's snapshot (corrupt payload): ${t.message}",
        )
    }
}

/** Decode a status DataItem payload, or a LOUD error snapshot on corruption. Null when absent. */
internal fun decodeStatusSnapshot(bytes: ByteArray?): StatusSnapshot? {
    if (bytes == null) return null
    return try {
        StatusSnapshot.fromBytes(bytes)
    } catch (t: Throwable) {
        Log.e(TAG, "Corrupt status snapshot payload — surfacing as error state: ${t.message}", t)
        StatusSnapshot(
            status = null,
            fetchedAtEpochMs = System.currentTimeMillis(),
            serverReachable = false,
            errorDetail = "watch could not read the phone's status snapshot (corrupt payload): ${t.message}",
        )
    }
}

/**
 * Same success/failure Task bridge the rest of :wear uses (no kotlinx-coroutines-play-services dep),
 * plus an honest TIMEOUT: on an unpaired/companion-less watch the Wearable Task can simply never
 * complete (observed on the Wear OS 5 emulator, 2026-07-10) — without a bound the caller hangs on a
 * spinner forever. A hang becomes a thrown IllegalStateException naming the Data Layer leg, so every
 * existing catch path surfaces it loudly instead of waiting silently.
 */
internal suspend fun <T> awaitDataClientTask(timeoutMs: Long = 10_000, start: () -> Task<T>): T =
    try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                start()
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw IllegalStateException("Data Layer not responding (timed out after ${timeoutMs / 1000}s)")
    }

private const val TAG = "SnapshotReads"
