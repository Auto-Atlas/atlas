package app.eve.wearbridge

import android.util.Log
import app.eve.EveApplication
import app.eve.data.wear.WearLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin GMS shell around [WearBridge] + [VoiceTurnRelay]. WearableListenerService is bound by Play
 * Services and its callbacks run on a background thread, so `runBlocking` here is idiomatic (there is
 * no lifecycle scope to leak into). All routing/mapping/honesty lives in the pure, unit-tested core;
 * this class only decodes the transport envelope and delegates.
 *
 * Manifest: this MUST be `android:exported="true"` with a MESSAGE_RECEIVED intent-filter AND a
 * CHANNEL_EVENT intent-filter, both scoped to the `wear` scheme + `/eve` path prefix —
 * WearableListenerService only receives an event when the OS can bind it for that action + path.
 */
class WearBridgeService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        // Only our action namespace; ignore anything else the Data Layer might deliver.
        if (!path.startsWith(ACTION_PREFIX)) return
        val bridge = (applicationContext as EveApplication).container.wearBridge
        try {
            runBlocking {
                bridge.handleAction(path, event.data ?: ByteArray(0), event.sourceNodeId)
            }
        } catch (t: Throwable) {
            // Never let a handler failure crash the listener process — surface it loudly instead.
            Log.e(TAG, "Wear action handling failed for $path", t)
        }
    }

    /**
     * v2 native voice turn: the watch OPENS a channel on [WearLink.PATH_VOICE_TURN]. We hand its
     * input/output streams to the pure [VoiceTurnRelay] and close the channel afterwards. The relay
     * never throws into us; a broken read simply ends with the channel closed and the watch's own
     * await-timeout names the leg. Blocking Task awaits are fine on this background callback thread.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearLink.PATH_VOICE_TURN) {
            // Not ours — ignore (and don't hold it open). Never crash on an unexpected channel.
            return
        }
        val relay = (applicationContext as EveApplication).container.voiceTurnRelay
        val channelClient = Wearable.getChannelClient(applicationContext)
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            input = Tasks.await(channelClient.getInputStream(channel))
            output = Tasks.await(channelClient.getOutputStream(channel))
            runBlocking { relay.handleTurn(input, output) }
        } catch (t: Throwable) {
            Log.e(TAG, "Voice turn channel handling failed", t)
        } finally {
            runCatching { output?.close() }
            runCatching { input?.close() }
            runCatching { Tasks.await(channelClient.close(channel)) }
        }
    }

    companion object {
        private const val TAG = "WearBridgeService"
        private const val ACTION_PREFIX = "/eve/action/"
    }
}
