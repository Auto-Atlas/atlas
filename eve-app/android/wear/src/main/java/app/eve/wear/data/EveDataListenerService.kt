package app.eve.wear.data

import android.content.ComponentName
import android.util.Log
import app.eve.data.wear.WearLink
import app.eve.wear.WearApplication
import app.eve.wear.complication.PendingApprovalsComplicationService
import app.eve.wear.tile.EveTileService
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * The WATCH-side Data-Layer listener that makes the Tile + complication EVENT-DRIVEN. When the phone
 * writes a fresh approvals or status snapshot, Play Services binds this service and delivers
 * onDataChanged; we then ask the Tiles framework and the complication data source to re-read (both
 * pull the new snapshot via [app.eve.wear.tile.TileStateReader]). No polling — this fires only on an
 * actual push, so the surfaces update within seconds of a phone write and stay idle otherwise.
 *
 * The in-app snapshot flow ([GmsSnapshotSource]) is untouched; it has its own collect-scoped listener.
 *
 * Manifest: MUST be `android:exported="true"` with a DATA_CHANGED intent-filter scoped to the `wear`
 * scheme + `/eve` path prefix (same pattern as the phone's WearBridgeService) — a WearableListenerService
 * only receives events when the OS can bind it.
 */
class EveDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Capture the changed approvals payload (if any) WHILE the buffer is valid — bytes read here
        // are used synchronously below before onDataChanged returns and the buffer is recycled.
        var approvalsBytes: ByteArray? = null
        var relevant = false
        events.forEach { event: DataEvent ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path in SNAPSHOT_PATHS) {
                relevant = true
                if (event.dataItem.uri.path == WearLink.PATH_APPROVALS_SNAPSHOT) {
                    approvalsBytes = event.dataItem.data
                }
            }
        }
        if (!relevant) return

        try {
            TileService.getUpdater(applicationContext).requestUpdate(EveTileService::class.java)
        } catch (t: Throwable) {
            // A refresh request failing must never crash the listener process — log it loudly.
            Log.e(TAG, "Tile update request failed: ${t.message}", t)
        }

        try {
            ComplicationDataSourceUpdateRequester
                .create(
                    context = applicationContext,
                    complicationDataSourceComponent = ComponentName(
                        applicationContext,
                        PendingApprovalsComplicationService::class.java,
                    ),
                )
                .requestUpdateAll()
        } catch (t: Throwable) {
            Log.e(TAG, "Complication update request failed: ${t.message}", t)
        }

        // Watch-local approval notifications: decode the fresh snapshot via the SHARED decode helper
        // (never re-parsed ad hoc — a corrupt payload becomes a serverReachable=false snapshot the
        // notifier treats as "change nothing"). Loud-but-non-fatal, matching this file's style.
        approvalsBytes?.let { bytes ->
            try {
                val snapshot = decodeApprovalsSnapshot(bytes) ?: return@let
                val container = (applicationContext as? WearApplication)?.container ?: return@let
                container.approvalNotifier.onSnapshot(applicationContext, snapshot)
            } catch (t: Throwable) {
                Log.e(TAG, "Approval notification update failed: ${t.message}", t)
            }
        }
    }

    private companion object {
        const val TAG = "EveDataListenerService"
        val SNAPSHOT_PATHS = setOf(WearLink.PATH_APPROVALS_SNAPSHOT, WearLink.PATH_STATUS_SNAPSHOT)
    }
}
