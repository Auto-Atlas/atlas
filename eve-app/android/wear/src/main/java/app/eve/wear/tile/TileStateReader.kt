package app.eve.wear.tile

import android.content.Context
import android.util.Log
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.wear.data.latestSnapshots
import com.google.android.gms.wearable.Wearable

/**
 * Thin GMS shell that both the Tile ([EveTileService]) and the complication
 * ([app.eve.wear.complication.PendingApprovalsComplicationService]) call to turn the phone's retained
 * snapshots into a [TileState]. All the honest mapping lives in the pure [WearStatusReader]; this
 * only does the one-shot Data-Layer read (via [latestSnapshots]) and delegates.
 *
 * If the WATCH-side read itself fails (a Play Services problem reading stored DataItems), that is
 * surfaced LOUDLY as a server-down-style [TileState.ServerDown] carrying the real reason — never a
 * fake [TileState.Live] or a fabricated 0.
 */
object TileStateReader {

    suspend fun read(context: Context, nowMs: Long = System.currentTimeMillis()): TileState {
        val dataClient = Wearable.getDataClient(context.applicationContext)
        return try {
            val latest = dataClient.latestSnapshots()
            WearStatusReader.reduce(latest.approvals, latest.status, nowMs)
        } catch (t: Throwable) {
            Log.e(TAG, "Tile/complication snapshot read failed: ${t.message}", t)
            WearStatusReader.reduce(
                approvals = ApprovalsSnapshot(
                    approvals = emptyList(),
                    fetchedAtEpochMs = nowMs,
                    serverReachable = false,
                    errorDetail = "watch could not read the phone's snapshot: ${t.message}",
                ),
                status = null,
                nowMs = nowMs,
            )
        }
    }

    private const val TAG = "TileStateReader"
}
