package app.eve.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Records a short voice clip from the real mic via [AudioRecord] — 16 kHz, mono, 16-bit PCM, the
 * exact format the server's enroll endpoint wants — and returns it as a self-contained WAV (RIFF)
 * file ([WavEncoder.pcm16ToWav]). No files touch disk; the clip lives in memory until it's
 * base64-encoded for the request.
 *
 * [record] is a suspend fn that captures for [maxMillis] (or until the coroutine is cancelled, e.g.
 * the user navigates away) on the IO dispatcher. RECORD_AUDIO permission MUST be granted before
 * calling — the UI gates the record button on [ContextCompat.checkSelfPermission]. [record] re-checks
 * the grant itself (defense-in-depth) and throws [SecurityException] LOUDLY if it is missing, rather
 * than constructing [AudioRecord] into a guaranteed failure. [context] is retained as the application
 * context only (no leak) for that runtime permission check.
 */
class VoiceRecorder(
    context: Context,
    val sampleRate: Int = 16_000,
) {
    private val appContext: Context = context.applicationContext
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Captures up to [maxMillis] of audio and returns it as WAV bytes, or null if nothing usable
     * was captured (mic unavailable / empty buffer). Honors coroutine cancellation: a cancelled
     * recording still returns whatever was captured so far is discarded (returns null on too-short).
     *
     * @throws SecurityException if RECORD_AUDIO is not granted at call time.
     * @throws IllegalStateException if AudioRecord can't initialize (mic busy / permission missing).
     */
    suspend fun record(maxMillis: Long = 3_500L): ByteArray? = withContext(Dispatchers.IO) {
        // Explicit runtime permission check BEFORE touching AudioRecord. The onboarding UI already
        // gates the record button on the same grant; if we somehow got here without it, fail loud
        // (SecurityException the caller surfaces) instead of building a recorder that can't init.
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "RECORD_AUDIO not granted — obtain the mic permission before calling record()",
            )
        }

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        check(minBuf != AudioRecord.ERROR && minBuf != AudioRecord.ERROR_BAD_VALUE) {
            "AudioRecord min buffer unavailable for ${sampleRate}Hz mono PCM16"
        }
        // A generous buffer (2x the min, floor 4KB) so we never drop frames during the short clip.
        val bufSize = maxOf(minBuf * 2, 4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (mic busy or RECORD_AUDIO not granted)"
        }

        val pcm = ByteArrayOutputStream()
        val chunk = ByteArray(bufSize)
        val deadline = System.currentTimeMillis() + maxMillis
        try {
            recorder.startRecording()
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive() // cancellation -> stop & clean up below
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) pcm.write(chunk, 0, read)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        val bytes = pcm.toByteArray()
        // Require at least ~0.5s of audio so a fumbled tap doesn't enroll silence.
        val minBytes = sampleRate / 2 * 2 // 0.5s * 2 bytes/sample
        if (bytes.size < minBytes) return@withContext null
        WavEncoder.pcm16ToWav(bytes, sampleRate)
    }
}
