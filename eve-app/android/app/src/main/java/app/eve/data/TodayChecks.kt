package app.eve.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local, per-date persistence of which Today action items are checked off. A small interface so
 * the repository depends on the capability, not the Android-backed implementation — keeping the
 * ViewModel/repository unit-testable without a Context.
 */
interface ActionItemChecks {
    /** Live set of checked indices for [date]. Emits on every check/uncheck. */
    fun checkedFor(date: String): Flow<Set<Int>>
    suspend fun setChecked(date: String, index: Int, checked: Boolean)
}

private val Context.todayChecksStore: DataStore<Preferences> by
    preferencesDataStore(name = "eve_today_checks")

/**
 * DataStore-backed [ActionItemChecks]. Survives process death and is keyed by date so ticking
 * off "ship one demo" today never carries over to tomorrow's fresh list. No server write-back —
 * this is the owner's private, on-device satisfaction layer.
 *
 * Storage: one StringSet of "yyyy-MM-dd|index" entries. Encoding the date INTO each entry (rather
 * than one set per date) keeps a single key and scopes reads/writes by a cheap prefix filter,
 * with no growth ceremony — a new day simply has no matching entries yet.
 */
class TodayChecks(private val context: Context) : ActionItemChecks {

    private val keyChecked = stringSetPreferencesKey("checked_items")

    private fun entry(date: String, index: Int) = "$date|$index"

    override fun checkedFor(date: String): Flow<Set<Int>> =
        context.todayChecksStore.data.map { prefs ->
            val all = prefs[keyChecked] ?: emptySet()
            val prefix = "$date|"
            all.asSequence()
                .filter { it.startsWith(prefix) }
                .mapNotNull { it.substringAfter('|').toIntOrNull() }
                .toSet()
        }

    override suspend fun setChecked(date: String, index: Int, checked: Boolean) {
        val token = entry(date, index)
        context.todayChecksStore.edit { prefs ->
            val current = prefs[keyChecked]?.toMutableSet() ?: mutableSetOf()
            if (checked) current.add(token) else current.remove(token)
            prefs[keyChecked] = current
        }
    }
}
