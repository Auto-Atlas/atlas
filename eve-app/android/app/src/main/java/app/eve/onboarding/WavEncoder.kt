package app.eve.onboarding

import java.io.ByteArrayOutputStream

/**
 * Wraps raw PCM16 mono samples in a canonical WAV (RIFF) container so the server's
 * `POST /v1/enroll` can decode them (it expects RIFF, mono, 16-bit PCM, 16k/24k).
 *
 * Pure JVM (no Android types) so it is unit-testable on the host — the one genuinely new
 * capability in onboarding, and the one the server must be able to read, so the header layout
 * is exact and covered by a test.
 */
object WavEncoder {

    /** Bytes per sample for 16-bit PCM. */
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val NUM_CHANNELS = 1 // mono
    private const val WAV_HEADER_SIZE = 44

    /**
     * @param pcm little-endian 16-bit PCM samples (mono) exactly as produced by AudioRecord.
     * @param sampleRate the rate the PCM was captured at (16000 or 24000 for Atlas enroll).
     * @return a complete WAV file (44-byte canonical header + the PCM data) as bytes.
     */
    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val byteRate = sampleRate * NUM_CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = NUM_CHANNELS * BYTES_PER_SAMPLE
        val dataLen = pcm.size
        val riffChunkLen = WAV_HEADER_SIZE - 8 + dataLen // file size minus the first 8 bytes

        val out = ByteArrayOutputStream(WAV_HEADER_SIZE + dataLen)

        // ---- RIFF header ----
        out.writeAscii("RIFF")
        out.writeLEInt(riffChunkLen)
        out.writeAscii("WAVE")

        // ---- fmt subchunk (PCM, 16 bytes) ----
        out.writeAscii("fmt ")
        out.writeLEInt(16)                  // subchunk1 size for PCM
        out.writeLEShort(1)                 // audio format 1 = PCM
        out.writeLEShort(NUM_CHANNELS)
        out.writeLEInt(sampleRate)
        out.writeLEInt(byteRate)
        out.writeLEShort(blockAlign)
        out.writeLEShort(BITS_PER_SAMPLE)

        // ---- data subchunk ----
        out.writeAscii("data")
        out.writeLEInt(dataLen)
        out.write(pcm)

        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        for (c in s) write(c.code and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLEInt(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLEShort(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }
}
