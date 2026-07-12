package app.eve.wear.notify

import android.content.Context

/**
 * Tiny persistence seam for the set of approval ids the watch has ALREADY posted a wrist
 * notification for — the dedupe cache that lets [planApprovalNotifications] notify only NEW ids and
 * cancel vanished ones across process restarts. Fakeable in tests (in-memory), like every other
 * seam in :wear (manual DI, no mocking library).
 */
interface NotifiedIdsStore {
    fun load(): Set<String>
    fun save(ids: Set<String>)
}

/**
 * SharedPreferences-backed [NotifiedIdsStore]. NOTE: the phone app persists its real settings with
 * DataStore, but this is a throwaway dedupe cache (not user data / not settings) — a small
 * SharedPreferences string-set is the right, boring tool and needs no coroutine/Flow surface. It is
 * written from the Data-Layer listener's background thread; SharedPreferences is thread-safe and
 * `apply()` persists asynchronously, which is fine for a best-effort cache.
 */
class SharedPrefsNotifiedIdsStore(context: Context) : NotifiedIdsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // getStringSet returns a set that MUST NOT be mutated by the caller — copy it defensively.
    override fun load(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()

    // Store a fresh copy so a later mutation of the caller's set can't corrupt the persisted value.
    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_IDS, HashSet(ids)).apply()
    }

    private companion object {
        const val PREFS_NAME = "eve_wear_notified_approvals"
        const val KEY_IDS = "notified_ids"
    }
}
