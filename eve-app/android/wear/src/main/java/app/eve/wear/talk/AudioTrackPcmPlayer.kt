package app.eve.wear.talk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Real [PcmPlayer] over [AudioTrack] — the thin edge only. Streams raw 16 kHz mono PCM16 to the wrist
 * speaker on a background thread with USAGE_ASSISTANT / CONTENT_TYPE_SPEECH attributes. All turn logic
 * is in the JVM-tested VM; this class just writes samples and maps a failure to the honest, NAMED
 * [VoiceState.Failed] (from [WearTalkCopy]) — the reply TEXT is rendered by the screen regardless.
 */
class AudioTrackPcmPlayer : PcmPlayer {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    @Volatile private var track: AudioTrack? = null
    private var playThread: Thread? = null

    override fun play(pcm: ByteArray, sampleRate: Int) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioTrack.getMinBufferSize returned $minBuf")
            _state.value = VoiceState.Failed(WearTalkCopy.PLAYBACK_FAILED)
            return
        }
        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL)
                        .setEncoding(ENCODING)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack construction failed", t)
            _state.value = VoiceState.Failed(WearTalkCopy.PLAYBACK_FAILED)
            return
        }
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized (state=${at.state})")
            at.release()
            _state.value = VoiceState.Failed(WearTalkCopy.PLAYBACK_FAILED)
            return
        }

        track = at
        _state.value = VoiceState.Speaking
        at.play()
        playThread = thread(name = "pcm-player") {
            try {
                var off = 0
                while (off < pcm.size && track === at) {
                    val n = at.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) { Log.e(TAG, "AudioTrack.write error $n"); _state.value = VoiceState.Failed(WearTalkCopy.PLAYBACK_FAILED); return@thread }
                    off += n
                }
                if (track === at) {
                    runCatching { at.stop() }
                    _state.value = VoiceState.Idle
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AudioTrack playback failed", t)
                _state.value = VoiceState.Failed(WearTalkCopy.PLAYBACK_FAILED)
            } finally {
                if (track === at) track = null
                runCatching { at.release() }
            }
        }
    }

    override fun stop() {
        val at = track
        track = null
        if (at != null) {
            runCatching { if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.stop() }
            runCatching { at.release() }
        }
        playThread = null
        if (_state.value is VoiceState.Speaking) _state.value = VoiceState.Idle
    }

    override fun release() {
        stop()
        _state.value = VoiceState.Idle
    }

    private companion object {
        const val TAG = "AudioTrackPcmPlayer"
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
