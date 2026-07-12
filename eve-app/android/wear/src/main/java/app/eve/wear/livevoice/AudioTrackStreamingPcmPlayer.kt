package app.eve.wear.livevoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Real [StreamingPcmPlayer] over [AudioTrack] in MODE_STREAM — the thin edge only. Opens ONE streaming
 * track ([start]) and appends EVE's PCM frames as they arrive ([write]) so a live utterance plays
 * without the gaps the one-shot [app.eve.wear.talk.AudioTrackPcmPlayer] would introduce (it stops +
 * rebuilds the track per call). Writes are blocking on the caller's socket thread — fine, the frames
 * are tiny (~20-40 ms). A construction/write failure is logged; audio failure never hides the reply
 * TEXT (rendered by the screen from control frames regardless).
 */
class AudioTrackStreamingPcmPlayer : StreamingPcmPlayer {

    @Volatile private var track: AudioTrack? = null
    private var openRate: Int = 0

    override fun start(sampleRate: Int) {
        if (track != null && openRate == sampleRate) return
        stop()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioTrack.getMinBufferSize returned $minBuf")
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
                .setBufferSizeInBytes(maxOf(minBuf, minBuf * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack construction failed", t)
            return
        }
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized (state=${at.state})")
            at.release()
            return
        }
        track = at
        openRate = sampleRate
        at.play()
    }

    override fun write(pcm: ByteArray) {
        val at = track ?: return
        try {
            var off = 0
            while (off < pcm.size && track === at) {
                val n = at.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) { Log.e(TAG, "AudioTrack.write error $n"); return }
                off += n
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack playback write failed", t)
        }
    }

    override fun stop() {
        val at = track
        track = null
        openRate = 0
        if (at != null) {
            runCatching { if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.stop() }
            runCatching { at.release() }
        }
    }

    private companion object {
        const val TAG = "LivePcmPlayer"
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
