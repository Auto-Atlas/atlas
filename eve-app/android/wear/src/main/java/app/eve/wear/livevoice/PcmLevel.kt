package app.eve.wear.livevoice

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure PCM level math for the ring's REAL speaking amplitude (no Android imports — JVM-unit-tested
 * in PcmLevelTest). The thin [OkHttpWsVoiceClient] edge calls [rms01] + [SpeakingEnvelope.onFrame]
 * on each downlink binary frame and publishes the result; [JarvisRing] drives the Speaking pulse
 * from it — the day the synthetic sine stand-in died.
 */
object PcmLevel {

    /** RMS of one PCM16LE mono frame, normalized to 0..1. Empty → 0; an odd trailing byte is ignored. */
    fun rms01(pcm: ByteArray): Float {
        val samples = pcm.size / 2
        if (samples == 0) return 0f
        var sumSq = 0.0
        for (i in 0 until samples) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            val f = s / 32768.0
            sumSq += f * f
        }
        return min(1f, sqrt(sumSq / samples).toFloat())
    }

    /** Audio duration of a PCM16LE mono frame in ms — the envelope's time base (audio time, not wall clock). */
    fun frameMs(pcm: ByteArray, sampleRate: Int): Float =
        (pcm.size / 2f) / sampleRate * 1000f
}

/**
 * Fast-attack / slow-decay envelope over per-frame RMS: speech onsets light the ring instantly,
 * word gaps ease down instead of flickering. Time constants are in audio-time ms so bursty socket
 * delivery (frames faster than realtime) can't make the visual jitter.
 */
class SpeakingEnvelope(
    private val attackMs: Float = 30f,
    private val decayMs: Float = 220f,
) {
    var level: Float = 0f
        private set

    /** Fold one frame's [rms] over [frameMs] of audio time; returns the new smoothed level. */
    fun onFrame(rms: Float, frameMs: Float): Float {
        val tau = if (rms > level) attackMs else decayMs
        val k = 1f - exp(-frameMs / tau)
        level += (rms - level) * k
        return level
    }

    /** Hard reset (call teardown) — the next session never inherits a stale glow. */
    fun reset() {
        level = 0f
    }
}
