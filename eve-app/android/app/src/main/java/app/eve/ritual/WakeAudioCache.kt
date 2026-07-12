package app.eve.ritual

import android.content.Context
import android.util.Log
import app.eve.EveApplication
import app.eve.data.WakeAudioResult
import java.io.File

/**
 * On-device cache of the 5 AM wake WAV (Atlas's real voice). The phone plays this file LOCALLY at wake
 * time — no WebRTC, no mic, works from deep Doze (exactly how an alarm app plays a sound). The file
 * is downloaded ahead of 5 AM (app start + after push-token registration) so it is already present
 * when the wake fires.
 *
 * The cache is keyed by the server's ETag (a content hash of the tenant's whys): a conditional GET
 * re-downloads only when the whys change. Nothing here is user-specific — the WAV content is
 * whatever the server renders for that tenant, fetched through the existing authenticated client.
 *
 * Every entry point is best-effort and never throws.
 */
object WakeAudioCache {
    private const val PREFS = "eve_wake_audio"
    private const val KEY_ETAG = "wake_etag"
    private const val FILE_NAME = "wake.wav"
    private const val TAG = "WakeAudioCache"

    /** The cached WAV file (may not exist yet). Stable path under filesDir. */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The cached file IFF it exists and is non-empty. */
    fun existingFile(context: Context): File? = file(context).takeIf { it.isFile && it.length() > 0 }

    private fun savedEtag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ETAG, null)

    private fun saveEtag(context: Context, etag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ETAG, etag)
            .apply()
    }

    /**
     * Best-effort download + cache. On [WakeAudioResult.Downloaded] writes the WAV to [file] and
     * persists the new ETag; on [WakeAudioResult.NotModified] keeps the existing file. Returns true
     * if, after the call, a playable cached file is present. Never throws.
     *
     * If [forceWhenMissing] is true, the conditional ETag is dropped when no cached file exists, so a
     * stale persisted ETag can't 304 us out of ever obtaining the file (the FGS fallback uses this).
     */
    suspend fun refresh(context: Context, forceWhenMissing: Boolean = false): Boolean {
        val app = context.applicationContext as? EveApplication ?: return existingFile(context) != null
        val hasFile = existingFile(context) != null
        val etag = if (forceWhenMissing && !hasFile) null else savedEtag(context)
        return try {
            when (val result = app.container.apiClient.downloadWakeAudio(etag)) {
                is WakeAudioResult.Downloaded -> {
                    writeAtomically(context, result.bytes)
                    saveEtag(context, result.etag)
                    Log.d(TAG, "wake audio cached (${result.bytes.size} bytes, etag=${result.etag})")
                    true
                }
                WakeAudioResult.NotModified -> {
                    Log.d(TAG, "wake audio unchanged; keeping cached file")
                    existingFile(context) != null
                }
                is WakeAudioResult.Failed -> {
                    Log.d(TAG, "wake audio download deferred: ${result.reason}")
                    existingFile(context) != null
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "wake audio refresh failed: ${t.message}")
            existingFile(context) != null
        }
    }

    /** Write to a temp file then rename, so a download interrupted mid-write never leaves a half WAV. */
    private fun writeAtomically(context: Context, bytes: ByteArray) {
        val target = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.outputStream().use { it.write(bytes) }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // renameTo can fail across some FS states; fall back to a direct copy.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
