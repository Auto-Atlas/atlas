package app.eve.data.audio

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Byte-exact guard on the shared WAV codec: PCM16 -> WAV (watch side) round-trips through WAV -> PCM
 * (phone header-strip), extra chunks are skipped, and every non-WAV/non-PCM payload fails LOUDLY.
 */
class WavTest {

    @Test
    fun encode_then_strip_roundtrips_pcm_and_rate() {
        val pcm = ByteArray(64) { it.toByte() }
        val wav = Wav.pcm16ToWav(pcm, 16_000)
        val back = Wav.pcmFrom(wav)
        assertTrue(pcm.contentEquals(back.bytes), "PCM must survive encode->strip unchanged")
        assertEquals(16_000, back.sampleRate)
        assertEquals(1, back.channels)
    }

    @Test
    fun canonical_header_is_44_bytes() {
        val wav = Wav.pcm16ToWav(byteArrayOf(1, 2, 3, 4), 16_000)
        assertEquals(44 + 4, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun strip_skips_an_extra_chunk_before_data() {
        // Build RIFF/WAVE + fmt + a LIST chunk + data — the parser must walk past LIST to find data.
        val pcm = byteArrayOf(9, 8, 7, 6)
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = s.forEach { out.write(it.code) }
        fun leInt(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun leShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        val listBody = "INFO".toByteArray()
        val riffLen = 4 + (8 + 16) + (8 + listBody.size) + (8 + pcm.size)
        ascii("RIFF"); leInt(riffLen); ascii("WAVE")
        ascii("fmt "); leInt(16); leShort(1); leShort(1); leInt(24_000); leInt(24_000 * 2); leShort(2); leShort(16)
        ascii("LIST"); leInt(listBody.size); out.write(listBody)
        ascii("data"); leInt(pcm.size); out.write(pcm)

        val back = Wav.pcmFrom(out.toByteArray())
        assertTrue(pcm.contentEquals(back.bytes))
        assertEquals(24_000, back.sampleRate, "rate must come from the fmt chunk, not a fixed offset")
    }

    @Test
    fun empty_pcm_still_encodes_a_valid_header() {
        val wav = Wav.pcm16ToWav(ByteArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals(0, Wav.pcmFrom(wav).bytes.size)
    }

    @Test
    fun non_riff_fails_loudly() {
        assertFailsWith<IllegalArgumentException> { Wav.pcmFrom("not a wav at all".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { Wav.pcmFrom(byteArrayOf(0x00, 0x01, 0x02, 0x03)) }
    }

    @Test
    fun stereo_wav_is_rejected() {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = s.forEach { out.write(it.code) }
        fun leInt(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun leShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        ascii("RIFF"); leInt(36); ascii("WAVE")
        ascii("fmt "); leInt(16); leShort(1); leShort(2); leInt(16_000); leInt(16_000 * 4); leShort(4); leShort(16)
        ascii("data"); leInt(0)
        assertFailsWith<IllegalArgumentException> { Wav.pcmFrom(out.toByteArray()) }
    }
}
