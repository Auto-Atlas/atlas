package app.eve.onboarding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The WAV header is the one byte-exact contract the server decodes, so verify the RIFF layout
 * field-by-field against the canonical 44-byte spec — not just "it runs".
 */
class WavEncoderTest {

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    private fun leInt(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    @Test
    fun writes_canonical_riff_header_for_16k_mono_pcm16() {
        val sampleRate = 16_000
        // 4 samples of PCM16 = 8 bytes of data.
        val pcm = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val wav = WavEncoder.pcm16ToWav(pcm, sampleRate)

        // Total size = 44-byte header + data.
        assertEquals(44 + pcm.size, wav.size, "header + data length")

        // RIFF / WAVE chunk ids.
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(44 - 8 + pcm.size, leInt(wav, 4), "RIFF chunk size = filesize - 8")
        assertEquals("WAVE", ascii(wav, 8, 4))

        // fmt subchunk.
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, leInt(wav, 16), "PCM fmt subchunk size")
        assertEquals(1, leShort(wav, 20), "audio format PCM=1")
        assertEquals(1, leShort(wav, 22), "mono = 1 channel")
        assertEquals(sampleRate, leInt(wav, 24), "sample rate")
        assertEquals(sampleRate * 1 * 2, leInt(wav, 28), "byte rate = rate*channels*bytesPerSample")
        assertEquals(1 * 2, leShort(wav, 32), "block align = channels*bytesPerSample")
        assertEquals(16, leShort(wav, 34), "bits per sample")

        // data subchunk.
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(pcm.size, leInt(wav, 40), "data chunk size")

        // Payload preserved verbatim after the header.
        val payload = wav.copyOfRange(44, wav.size)
        assertTrue(pcm.contentEquals(payload), "PCM bytes copied unchanged")
    }

    @Test
    fun honors_24k_sample_rate_in_header() {
        val wav = WavEncoder.pcm16ToWav(byteArrayOf(1, 2), 24_000)
        assertEquals(24_000, leInt(wav, 24))
        assertEquals(24_000 * 2, leInt(wav, 28))
    }

    @Test
    fun empty_pcm_still_produces_a_valid_44_byte_header() {
        val wav = WavEncoder.pcm16ToWav(ByteArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(0, leInt(wav, 40), "zero data length")
    }
}
