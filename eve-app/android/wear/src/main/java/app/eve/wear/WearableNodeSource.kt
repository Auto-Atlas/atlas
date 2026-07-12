package app.eve.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real [PhoneNodeSource] over the Play Services Wear Data Layer. Wraps the callback-based
 * NodeClient.getConnectedNodes() Task in a suspend function (no kotlinx-coroutines-play-services
 * dependency needed — a plain success/failure listener bridge). A Play Services failure propagates
 * as an exception so [phoneLinkStateFrom] renders it as [PhoneLinkState.Failed], never a fake OK.
 *
 * Honest TIMEOUT: on an unpaired/companion-less watch this Task can simply never complete (observed
 * on the Wear OS 5 emulator, 2026-07-10 — the link check spun forever). After [TIMEOUT_MS] the hang
 * becomes a thrown IllegalStateException naming the Data Layer leg, which the caller renders as
 * [PhoneLinkState.Failed] — a loud answer instead of an eternal spinner.
 */
class WearableNodeSource(context: Context) : PhoneNodeSource {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)

    override suspend fun connectedNodes(): List<String> =
        try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    nodeClient.connectedNodes
                        .addOnSuccessListener { nodes -> cont.resume(nodes.map { it.displayName }) }
                        .addOnFailureListener { e -> cont.resumeWithException(e) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Data Layer not responding (node query timed out after ${TIMEOUT_MS / 1000}s)")
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
