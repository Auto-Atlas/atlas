package app.eve.data.audio

import java.io.ByteArrayOutputStream

/**
 * Pure-JVM WAV (RIFF) codec shared by the watch (PCM16 -> WAV, [pcm16ToWav]) and the phone (WAV ->
 * raw PCM, [pcmFrom]). No Android types, so both the recorder seam's encoding and the relay's
 * header-strip are unit-testable on the host. One source of truth for the byte layout so the two
 * sides can never drift.
 *
 * The parser is chunk-aware (it walks `fmt `/`data`/any other chunk, never assumes the canonical
 * 44-byte header) so it decodes a real Chatterbox WAV that may carry extra chunks — and it fails
 * LOUDLY ([IllegalArgumentException]) on anything that is not a mono/PCM16 RIFF, never returning a
 * silent empty or mis-parsed buffer.
 */
object Wav {

    const val DEFAULT_SAMPLE_RATE = 16_000
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val NUM_CHANNELS = 1 // mono
    private const val HEADER_SIZE = 44

    /** Raw PCM extracted from a WAV, with the format the header declared. */
    data class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int) {
        override fun equals(other: Any?): Boolean =
            other is Pcm && bytes.contentEquals(other.bytes) && sampleRate == other.sampleRate && channels == other.channels

        override fun hashCode(): Int = (bytes.contentHashCode() * 31 + sampleRate) * 31 + channels
    }

    /**
     * Wrap little-endian 16-bit mono PCM samples in a canonical 44-byte WAV container.
     * @param pcm PCM16 mono samples exactly as produced by AudioRecord.
     * @param sampleRate capture rate (16000 for the voice turn).
     */
    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val byteRate = sampleRate * NUM_CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = NUM_CHANNELS * BYTES_PER_SAMPLE
        val dataLen = pcm.size
        val out = ByteArrayOutputStream(HEADER_SIZE + dataLen)

        out.writeAscii("RIFF")
        out.writeLEInt(HEADER_SIZE - 8 + dataLen) // file size minus the first 8 bytes
        out.writeAscii("WAVE")

        out.writeAscii("fmt ")
        out.writeLEInt(16) // PCM fmt subchunk size
        out.writeLEShort(1) // audio format 1 = PCM
        out.writeLEShort(NUM_CHANNELS)
        out.writeLEInt(sampleRate)
        out.writeLEInt(byteRate)
        out.writeLEShort(blockAlign)
        out.writeLEShort(BITS_PER_SAMPLE)

        out.writeAscii("data")
        out.writeLEInt(dataLen)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Parse a WAV and return its raw PCM `data` chunk plus the declared format. Walks the chunk list
     * so extra chunks (`LIST`, `fact`, …) before `data` are skipped correctly. Throws
     * [IllegalArgumentException] on a non-RIFF/WAVE file, a missing `fmt `/`data` chunk, a non-PCM
     * format, or a truncated chunk — a broken payload is loud, never a fake empty buffer.
     */
    fun pcmFrom(wav: ByteArray): Pcm {
        require(wav.size >= 12) { "not a WAV: only ${wav.size} bytes" }
        require(ascii(wav, 0, 4) == "RIFF") { "not a WAV: missing RIFF magic" }
        require(ascii(wav, 8, 4) == "WAVE") { "not a WAV: missing WAVE magic" }

        var sampleRate = -1
        var channels = -1
        var audioFormat = -1
        var pcm: ByteArray? = null

        var off = 12 // first chunk after "RIFF"<size>"WAVE"
        while (off + 8 <= wav.size) {
            val id = ascii(wav, off, 4)
            val size = leInt(wav, off + 4)
            require(size >= 0) { "WAV chunk '$id' has a negative size ($size)" }
            val body = off + 8
            when (id) {
                "fmt " -> {
                    require(body + 16 <= wav.size) { "WAV fmt chunk truncated" }
                    audioFormat = leShort(wav, body)
                    channels = leShort(wav, body + 2)
                    sampleRate = leInt(wav, body + 4)
                }
                "data" -> {
                    val end = body + size
                    require(end <= wav.size) { "WAV data chunk claims $size bytes past end of file" }
                    pcm = wav.copyOfRange(body, end)
                }
            }
            // Chunks are word-aligned: an odd size is padded with one byte.
            off = body + size + (size and 1)
        }

        require(audioFormat == 1) { "WAV is not PCM (audioFormat=$audioFormat)" }
        require(channels == NUM_CHANNELS) { "WAV is not mono (channels=$channels)" }
        require(sampleRate > 0) { "WAV has no valid sample rate" }
        val data = requireNotNull(pcm) { "WAV has no data chunk" }
        return Pcm(data, sampleRate, channels)
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String = String(b, off, len, Charsets.US_ASCII)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        for (c in s) write(c.code and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLEInt(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLEShort(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
