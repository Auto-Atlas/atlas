package app.eve.wear.livevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The pure math behind the ring's REAL speaking amplitude: PCM16LE frame → 0..1 RMS, and the
 * attack/decay envelope that turns bursty socket frames into a smooth visual level. JVM-only —
 * this is the logic the thin OkHttp client edge calls per binary frame.
 */
class PcmLevelTest {

    private fun pcmOf(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ---- rms01 ---------------------------------------------------------------------------------

    @Test
    fun `silence is zero`() {
        assertEquals(0f, PcmLevel.rms01(pcmOf(0, 0, 0, 0)), 1e-6f)
    }

    @Test
    fun `full-scale square wave is full level`() {
        val full = pcmOf(32767, -32767, 32767, -32767)
        assertEquals(1f, PcmLevel.rms01(full), 0.01f)
    }

    @Test
    fun `half-scale sine lands near its true rms`() {
        val n = 1600 // 100 ms at 16 kHz
        val samples = IntArray(n) { i -> (16384 * sin(2 * PI * 440 * i / 16000.0)).toInt() }
        // RMS of a 0.5-amplitude sine = 0.5 / sqrt(2) ≈ 0.354
        assertEquals(0.354f, PcmLevel.rms01(pcmOf(*samples)), 0.02f)
    }

    @Test
    fun `empty frame is zero, never a crash`() {
        assertEquals(0f, PcmLevel.rms01(ByteArray(0)), 1e-6f)
    }

    @Test
    fun `odd trailing byte is ignored, never a crash`() {
        val odd = pcmOf(32767, 32767) + byteArrayOf(0x7F)
        assertEquals(1f, PcmLevel.rms01(odd), 0.01f)
    }

    // ---- SpeakingEnvelope ------------------------------------------------------------------------

    @Test
    fun `envelope attacks fast - one loud frame lifts most of the way`() {
        val env = SpeakingEnvelope()
        val level = env.onFrame(rms = 1f, frameMs = 40f)
        assertTrue("expected fast attack, got $level", level > 0.5f)
    }

    @Test
    fun `envelope decays slower than it attacks`() {
        val env = SpeakingEnvelope()
        env.onFrame(rms = 1f, frameMs = 40f)
        val afterLoud = env.level
        env.onFrame(rms = 0f, frameMs = 40f)
        val afterQuiet = env.level
        assertTrue("decay should not slam to zero", afterQuiet > afterLoud * 0.4f)
        assertTrue("but it must decay", afterQuiet < afterLoud)
    }

    @Test
    fun `envelope eventually settles to silence`() {
        val env = SpeakingEnvelope()
        env.onFrame(rms = 1f, frameMs = 40f)
        repeat(50) { env.onFrame(rms = 0f, frameMs = 40f) }
        assertTrue("expected near-zero after 2s of silence, got ${env.level}", env.level < 0.05f)
    }

    @Test
    fun `reset drops the level immediately`() {
        val env = SpeakingEnvelope()
        env.onFrame(rms = 1f, frameMs = 40f)
        env.reset()
        assertEquals(0f, env.level, 1e-6f)
    }

    @Test
    fun `frame duration derives from pcm size and sample rate`() {
        // 640 bytes = 320 samples = 20 ms @ 16 kHz
        assertEquals(20f, PcmLevel.frameMs(ByteArray(640), sampleRate = 16_000), 1e-3f)
    }
}
