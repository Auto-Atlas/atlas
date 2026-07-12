package app.eve.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Local record of the health upload's outcome — drives the Status row's "never synced" vs "last
 * synced <time>" states. Its own DataStore file (separate from eve_settings) so a health write never
 * contends with a connection write. Nothing owner-specific: just a timestamp + the last error text.
 */
private val Context.healthDataStore: DataStore<Preferences> by preferencesDataStore(name = "eve_health")

class HealthUploadStore(private val context: Context) {

    private val keyLastUploadAt = longPreferencesKey("last_upload_at")
    private val keyLastError = stringPreferencesKey("last_error")

    /** Epoch millis of the last SUCCESSFUL upload, or null if the phone has never synced. */
    suspend fun lastUploadAt(): Long? =
        context.healthDataStore.data.first()[keyLastUploadAt]

    /** The last upload error text (for diagnostics), or null when the last attempt succeeded. */
    suspend fun lastError(): String? =
        context.healthDataStore.data.first()[keyLastError]

    /** Record a successful upload at [epochMillis] and clear any prior error. */
    suspend fun recordSuccess(epochMillis: Long) {
        context.healthDataStore.edit { prefs ->
            prefs[keyLastUploadAt] = epochMillis
            prefs.remove(keyLastError)
        }
    }

    /** Record a failed attempt with an honest [reason]; the last-success timestamp is left intact. */
    suspend fun recordFailure(reason: String) {
        context.healthDataStore.edit { prefs ->
            prefs[keyLastError] = reason
        }
    }
}
