package app.eve.wear.talk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import app.eve.data.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Real [WristRecorder] over [AudioRecord] — the thin edge only. It opens the mic at 16 kHz mono PCM16,
 * drains samples on a background thread into a buffer, and on [stop] wraps them in a WAV via the
 * shared [Wav] codec. ALL turn logic (cap, elapsed, send) lives in the JVM-tested VM; this class just
 * moves bytes and maps device failures to the NAMED copy in [WearTalkCopy] (never a silent empty WAV).
 */
class AudioRecordWristRecorder : WristRecorder {

    @Volatile private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var readerThread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    override fun start(): RecordStart {
        if (capturing) return RecordStart.Failed(WearTalkCopy.MIC_BUSY)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize returned $minBuf")
            return RecordStart.Failed(WearTalkCopy.MIC_BUSY)
        }
        val bufSize = minBuf * 2
        val rec = try {
            @Suppress("MissingPermission") // RECORD_AUDIO is confirmed by the screen before start()
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord SecurityException — permission not granted", e)
            return RecordStart.Failed(WearTalkCopy.MIC_PERMISSION)
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return RecordStart.Failed(WearTalkCopy.MIC_BUSY)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return RecordStart.Failed(WearTalkCopy.MIC_BUSY)
        }

        buffer.reset()
        record = rec
        capturing = true
        rec.startRecording()
        readerThread = thread(name = "wrist-recorder") {
            val chunk = ByteArray(bufSize)
            while (capturing) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
                else if (n < 0) { Log.e(TAG, "AudioRecord.read error $n"); break }
            }
        }
        return RecordStart.Started
    }

    override suspend fun stop(): RecordStop = withContext(Dispatchers.IO) {
        val rec = record
        stopCapture(rec)
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.isEmpty()) {
            RecordStop.Failed(WearTalkCopy.RECORDING_EMPTY)
        } else {
            try {
                RecordStop.Wav(Wav.pcm16ToWav(pcm, SAMPLE_RATE))
            } catch (t: Throwable) {
                Log.e(TAG, "WAV encode failed", t)
                RecordStop.Failed(WearTalkCopy.RECORDING_EMPTY)
            }
        }
    }

    override fun cancel() {
        stopCapture(record)
        synchronized(buffer) { buffer.reset() }
    }

    private fun stopCapture(rec: AudioRecord?) {
        capturing = false
        readerThread?.let { runCatching { it.join(500) } }
        readerThread = null
        if (rec != null) {
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        record = null
    }

    private companion object {
        const val TAG = "AudioRecordWristRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
