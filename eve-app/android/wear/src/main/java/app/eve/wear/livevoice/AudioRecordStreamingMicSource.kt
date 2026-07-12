package app.eve.wear.livevoice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Real [StreamingMicSource] over [AudioRecord] — the thin edge only. Opens the mic at 16 kHz mono
 * PCM16 and hands each captured chunk to the callback on a background thread so the client can stream
 * it up immediately (continuous, NOT turn-based like [app.eve.wear.talk.AudioRecordWristRecorder]).
 * When muted it keeps draining the mic (so the buffer never overflows) but drops the chunks locally.
 * All session logic lives in the JVM-tested controller/VM; this class just moves bytes.
 */
class AudioRecordStreamingMicSource : StreamingMicSource {

    @Volatile private var record: AudioRecord? = null
    @Volatile private var capturing = false
    @Volatile private var muted = false
    private var readerThread: Thread? = null

    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (capturing) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize returned $minBuf")
            return false
        }
        val bufSize = minBuf * 2
        val rec = try {
            @Suppress("MissingPermission") // RECORD_AUDIO confirmed by the screen before start()
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return false
        }

        record = rec
        capturing = true
        rec.startRecording()
        // ~20ms of 16 kHz mono PCM16 = 640 bytes; use a small frame so latency stays low.
        val frame = ByteArray(FRAME_BYTES)
        readerThread = thread(name = "live-mic") {
            while (capturing) {
                val n = rec.read(frame, 0, frame.size)
                if (n > 0) {
                    if (!muted) onChunk(frame.copyOf(n))
                } else if (n < 0) {
                    Log.e(TAG, "AudioRecord.read error $n"); break
                }
            }
        }
        return true
    }

    override fun setMuted(muted: Boolean) { this.muted = muted }

    override fun stop() {
        capturing = false
        readerThread?.let { runCatching { it.join(500) } }
        readerThread = null
        record?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        record = null
    }

    private companion object {
        const val TAG = "LiveMicSource"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_BYTES = 640 // 20 ms @ 16 kHz mono PCM16
    }
}
