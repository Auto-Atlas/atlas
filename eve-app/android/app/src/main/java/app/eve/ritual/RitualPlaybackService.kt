package app.eve.ritual

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Plays the 5 AM wake LOCALLY, alarm-style, from a foreground service — no WebRTC, no mic, no echo,
 * works from deep Doze (exactly how an alarm app plays a sound). This is the connection-free PRIMARY
 * wake path: the FCM `morning_ritual` push starts this service, which plays the cached `wake.wav`
 * (Atlas's real voice) on the ALARM stream at full alarm volume so it actually wakes him.
 *
 * Fallback order (so he is ALWAYS woken, never a crash):
 *   1. Play the cached `wake.wav` if present.
 *   2. If missing, synchronously download it (the FGS has a Doze network window) and play it.
 *   3. If THAT fails, speak the FCM `text` fallback via Android TextToSpeech.
 *
 * Lifecycle: acquire a CPU wake lock (+ screen-on) on start, release it and stopSelf() when playback
 * (or TTS) completes. Android-14 FGS-start exceptions are caught exactly like [app.eve.push.StreamService].
 */
class RitualPlaybackService : Service() {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var workJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground first; if that throws (Android 14+ FGS-start restrictions) we degrade
        // gracefully and still try to play — the wake must never crash the app.
        startInForeground()
        acquireWakeLock()

        val fallbackText = intent?.getStringExtra(EXTRA_TEXT)
        if (workJob?.isActive != true) {
            workJob = scope.launch { runWake(fallbackText) }
        }
        // Not sticky: a background-restarted FGS would re-throw on Android 14. One-shot wake only.
        return START_NOT_STICKY
    }

    /** Best-effort foreground promotion. Never throws (mirrors StreamService's Android-14 guard). */
    private fun startInForeground() {
        try {
            val notification = RitualNotification.playbackNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException (API 34+) subclasses IllegalStateException;
            // a broad catch covers every FGS-start failure. Degrade: keep playing without FGS.
            Log.w(TAG, "Could not start foreground ritual playback; running degraded.", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP is deprecated but still turns the screen on for the
            // wake; PARTIAL_WAKE_LOCK alone keeps the CPU alive for audio. Combine for an alarm feel.
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            wakeLock = pm.newWakeLock(flags, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // Safety net: auto-release after the longest plausible wake so a missed completion
                // callback can never pin the CPU/screen on indefinitely.
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock unavailable: ${t.message}")
        }
    }

    private suspend fun runWake(fallbackText: String?) {
        // 1) cached file, 2) sync download then play, 3) TTS fallback.
        val cached = WakeAudioCache.existingFile(applicationContext)
        if (cached != null && tryPlay(cached)) return

        Log.d(TAG, "no cached wake audio; attempting synchronous download")
        if (WakeAudioCache.refresh(applicationContext, forceWhenMissing = true)) {
            val fresh = WakeAudioCache.existingFile(applicationContext)
            if (fresh != null && tryPlay(fresh)) return
        }

        Log.w(TAG, "wake audio unavailable; falling back to TTS")
        if (!fallbackText.isNullOrBlank() && speak(fallbackText)) return

        // Nothing left to do — at least the high-priority notification woke the screen.
        Log.w(TAG, "no wake audio and no fallback text; finishing")
        finish()
    }

    /** Start MediaPlayer on the ALARM usage. Returns true if playback was launched. Never throws. */
    private fun tryPlay(file: File): Boolean {
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { finish() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    finish()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp
            Log.d(TAG, "playing wake audio locally on ALARM stream")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "wake audio playback failed: ${t.message}")
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            false
        }
    }

    /** Last-resort: speak the FCM fallback text. Returns true if TTS was initialized to speak. */
    private fun speak(text: String): Boolean {
        return try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let { engine ->
                        runCatching { engine.language = Locale.US }
                        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { finish() }
                            @Deprecated("deprecated in API 21")
                            override fun onError(utteranceId: String?) { finish() }
                        })
                        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                        if (result == TextToSpeech.ERROR) finish()
                    } ?: finish()
                } else {
                    Log.w(TAG, "TTS init failed (status=$status)")
                    finish()
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "TTS fallback failed: ${t.message}")
            false
        }
    }

    /** Idempotently tear down playback, release the wake lock, and stop the service. */
    private fun finish() {
        if (finished) return
        finished = true
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { RitualNotification.cancel(applicationContext) }
        stopSelf()
    }

    override fun onDestroy() {
        workJob?.cancel()
        scope.cancel()
        // Final safety: ensure resources are freed even if finish() didn't run.
        runCatching { mediaPlayer?.release() }
        runCatching { tts?.shutdown() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RitualPlaybackService"
        private const val FOREGROUND_ID = 0x5A4D10
        private const val WAKE_LOCK_TAG = "eve:ritual-wake"
        private const val WAKE_LOCK_TIMEOUT_MS = 90_000L
        private const val UTTERANCE_ID = "eve_wake"

        /** Extra carrying the FCM `text` (last-resort TTS fallback). */
        const val EXTRA_TEXT = "eve_wake_text"

        /**
         * Start the local-playback wake. Starting an FGS from an FCM high-priority message is
         * permitted, but the rare [android.app.ForegroundServiceStartNotAllowedException] must be
         * handled gracefully — failing to start the wake service must never crash the app.
         */
        fun start(context: Context, fallbackText: String?) {
            val intent = Intent(context, RitualPlaybackService::class.java).apply {
                putExtra(EXTRA_TEXT, fallbackText)
            }
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService(ritual playback) failed.", t)
            }
        }
    }
}
