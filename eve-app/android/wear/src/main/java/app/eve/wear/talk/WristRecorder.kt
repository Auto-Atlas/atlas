package app.eve.wear.talk

/**
 * Seam over the on-watch microphone capture (AudioRecord). The talk VM depends on this interface —
 * fakeable, manual DI, no mocking library — so ALL recording logic stays JVM-testable and the real
 * AudioRecord impl ([AudioRecordWristRecorder]) stays thin.
 *
 * Contract — no silent fallback: [start] returns [RecordStart.Failed] with a NAMED reason when the
 * mic can't be opened (permission, busy); [stop] returns [RecordStop.Failed] when the capture yielded
 * nothing usable (zero-length / encode error). A blank recording is never returned as an empty WAV
 * and sent onward — it is a named failure. The VM owns the 15s cap and the elapsed clock (so both are
 * virtual-time testable); this seam only opens/closes the mic and hands back WAV bytes.
 */
interface WristRecorder {

    /** Open the mic and begin capturing 16 kHz mono PCM16. Returns immediately. */
    fun start(): RecordStart

    /** Stop capturing and return the recorded audio as a 16 kHz mono PCM16 WAV, or a named failure. */
    suspend fun stop(): RecordStop

    /** Abandon an in-progress capture without producing audio (e.g. a leg failed before send). */
    fun cancel()
}

/** The honest result of opening the mic — never a fake "started" when the device refused. */
sealed interface RecordStart {
    data object Started : RecordStart

    /** The mic could not be opened. [message] is the exact user copy (from [WearTalkCopy]). */
    data class Failed(val message: String) : RecordStart
}

/** The honest result of finishing a capture — never a fake empty WAV. */
sealed interface RecordStop {
    /** A real capture: [bytes] is a complete 16 kHz mono PCM16 WAV (RIFF). */
    data class Wav(val bytes: ByteArray) : RecordStop {
        override fun equals(other: Any?): Boolean = other is Wav && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Nothing usable was captured. [message] is the exact user copy (from [WearTalkCopy]). */
    data class Failed(val message: String) : RecordStop
}
