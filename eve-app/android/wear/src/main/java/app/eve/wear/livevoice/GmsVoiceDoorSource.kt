package app.eve.wear.livevoice

import android.content.Context
import android.util.Log
import app.eve.data.wear.VoiceDoorConfig
import app.eve.data.wear.WearLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
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
 * Real [VoiceDoorSource] over the Play Services Wearable DataClient. Reads the retained
 * [VoiceDoorConfig] the phone writes at [WearLink.PATH_VOICE_DOOR] — same push-based, collect-scoped,
 * fail-loud-on-corruption pattern as [app.eve.wear.data.GmsSnapshotSource] (listener lives only while
 * collected; a corrupt payload becomes a BLANK "not configured" config, logged, never a fake door).
 *
 * An ABSENT DataItem (phone never wrote one) also surfaces as a blank config on the initial read, so
 * the orb honestly shows "No voice door configured" instead of hanging with no state.
 */
class GmsVoiceDoorSource(context: Context) : VoiceDoorSource {

    private val dataClient: DataClient = Wearable.getDataClient(context.applicationContext)
    private val path = WearLink.PATH_VOICE_DOOR

    override fun configs(): Flow<VoiceDoorConfig> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == path) {
                    decode(event.dataItem.data)?.let { trySend(it) }
                }
            }
        }

        try {
            val buffer = awaitDataClientTask { dataClient.dataItems }
            try {
                val found = buffer.firstOrNull { it.uri.path == path }?.let { decode(it.data) }
                // Absent → the honest not-configured blank, so the orb resolves immediately.
                trySend(found ?: VoiceDoorConfig("", ""))
            } finally {
                buffer.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Initial voice-door read failed: ${t.message}", t)
            trySend(VoiceDoorConfig("", "")) // fail to "not configured", never a fake door
        }

        dataClient.addListener(listener)
        awaitClose { dataClient.removeListener(listener) }
    }

    override suspend fun current(): VoiceDoorConfig? {
        val buffer = awaitDataClientTask { dataClient.dataItems }
        return try {
            buffer.firstOrNull { it.uri.path == path }?.let { decode(it.data) }
        } finally {
            buffer.release()
        }
    }

    /** Decode the door payload; a corrupt payload becomes a blank "not configured" config (loud log). */
    private fun decode(bytes: ByteArray?): VoiceDoorConfig? {
        if (bytes == null) return null
        return try {
            VoiceDoorConfig.fromBytes(bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "Corrupt voice-door payload — surfacing as not configured: ${t.message}", t)
            VoiceDoorConfig("", "")
        }
    }

    private suspend fun <T> awaitDataClientTask(start: () -> Task<T>): T =
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
        const val TAG = "GmsVoiceDoorSource"
        const val TIMEOUT_MS = 10_000L
    }
}
