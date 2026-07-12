package app.eve.data

/**
 * Outcome of [ApiClient.downloadWakeAudio]. Never an exception — the wake download is best-effort
 * (it runs from app start and from the FCM wake path) and may never crash the caller.
 */
sealed interface WakeAudioResult {
    /** 200: a fresh WAV body plus its ETag (the cache key). [etag] may be null if the server omitted it. */
    data class Downloaded(val bytes: ByteArray, val etag: String?) : WakeAudioResult {
        // ByteArray needs structural equals/hashCode for sane comparisons/tests.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Downloaded) return false
            return etag == other.etag && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * (etag?.hashCode() ?: 0) + bytes.contentHashCode()
    }

    /** 304: the cached file is still current; keep it as-is. */
    data object NotModified : WakeAudioResult

    /** Unconfigured / offline / bad status / decode failure — captured honestly, never thrown. */
    data class Failed(val reason: String) : WakeAudioResult
}
