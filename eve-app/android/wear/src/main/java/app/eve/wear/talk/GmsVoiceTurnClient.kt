package app.eve.wear.talk

import android.content.Context
import android.util.Log
import app.eve.data.wear.VoiceEnvelope
import app.eve.data.wear.VoiceTurnReply
import app.eve.data.wear.VoiceTurnRequest
import app.eve.data.wear.WearLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real [VoiceTurnClient] over Play Services [ChannelClient] — the thin edge only. Finds the phone
 * gateway node via [CapabilityClient] (reachable nodes), opens ONE channel on
 * [WearLink.PATH_VOICE_TURN], writes the framed [VoiceTurnRequest] + WAV (then closes its output so
 * the phone reads EOF), and reads the [VoiceTurnReply] envelope + raw PCM back. All envelope framing
 * is the shared, unit-tested [VoiceEnvelope]; this class only does the stream I/O and maps a broken
 * leg to the honest [VoiceTurnOutcome] (never a fake reply).
 */
class GmsVoiceTurnClient(context: Context) : VoiceTurnClient {

    private val appContext = context.applicationContext
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(appContext)
    private val channelClient: ChannelClient = Wearable.getChannelClient(appContext)

    override suspend fun runTurn(
        request: VoiceTurnRequest,
        wav: ByteArray,
        onSent: () -> Unit,
    ): VoiceTurnOutcome = withContext(Dispatchers.IO) {
        val node = gatewayNode() ?: return@withContext VoiceTurnOutcome.NoGatewayNode
        val channel = try {
            awaitTask { channelClient.openChannel(node.id, WearLink.PATH_VOICE_TURN) }
        } catch (t: Throwable) {
            Log.e(TAG, "openChannel to ${node.id} failed: ${t.message}", t)
            return@withContext VoiceTurnOutcome.SendFailed(t.message ?: "open channel failed")
        }

        try {
            // ---- write our half: framed request + WAV, then close output (phone reads EOF) ----
            val output: OutputStream = awaitTask { channelClient.getOutputStream(channel) }
            try {
                VoiceEnvelope.write(output, VoiceTurnRequest.serializer(), request)
                output.write(wav)
                output.flush()
            } finally {
                runCatching { output.close() }
            }
            onSent()

            // ---- read the phone's half: reply envelope + raw PCM ----
            val input: InputStream = awaitTask { channelClient.getInputStream(channel) }
            try {
                val reply = VoiceEnvelope.read(input, VoiceTurnReply.serializer())
                val pcm = input.readBytes()
                VoiceTurnOutcome.Replied(reply, pcm)
            } catch (t: Throwable) {
                Log.e(TAG, "reading voice reply failed: ${t.message}", t)
                VoiceTurnOutcome.NoReply(t.message ?: "channel closed before reply")
            } finally {
                runCatching { input.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "voice turn channel I/O failed: ${t.message}", t)
            VoiceTurnOutcome.SendFailed(t.message ?: "channel write failed")
        } finally {
            runCatching { awaitTask { channelClient.close(channel) } }
        }
    }

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
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    /** Task bridge with an honest timeout — an unpaired watch can hang a Wearable Task forever. */
    private suspend fun <T> awaitTask(start: () -> Task<T>): T =
        try {
            withTimeout(TASK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    start()
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { e -> cont.resumeWithException(e) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Data Layer not responding (timed out after ${TASK_TIMEOUT_MS / 1000}s)")
        }

    private companion object {
        const val TAG = "GmsVoiceTurnClient"
        const val TASK_TIMEOUT_MS = 10_000L
    }
}
