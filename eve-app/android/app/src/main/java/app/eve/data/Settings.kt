package app.eve.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persisted connection settings: the tailnet base URL (e.g. https://host.ts.net:8443) and the
 * EVE_APP_TOKEN bearer. Backed by DataStore Preferences. The token is read fresh per request by
 * ApiClient so rotating it in-app takes effect immediately.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eve_settings")

data class EveConnection(
    val baseUrl: String,
    val token: String,
) {
    /**
     * Configured == a non-blank token AND a base URL that actually parses to http/https + host.
     * A previously-saved GARBAGE base URL (double scheme, host-less, etc.) is treated as NOT
     * configured so the app routes back to the Connect screen to be fixed, rather than handing a
     * throwing URL to the Ktor client / StreamService and crash-looping on every launch.
     */
    val isConfigured: Boolean get() = token.isNotBlank() && BaseUrl.isValid(baseUrl)
}

class Settings(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("app_token")
    private val keyVoiceUrlOverride = stringPreferencesKey("voice_url_override")
    private val keyWatchVoiceDoorUrl = stringPreferencesKey("watch_voice_door_url")
    private val keyGlassesEnabled = booleanPreferencesKey("glasses_enabled")

    val connection: Flow<EveConnection> = context.dataStore.data.map { prefs ->
        EveConnection(
            baseUrl = (prefs[keyBaseUrl] ?: "").trim(),
            token = (prefs[keyToken] ?: "").trim(),
        )
    }

    /**
     * Optional explicit voice (phone_bot) base URL. When set it wins verbatim in
     * [app.eve.voice.deriveVoiceUrl]; otherwise the voice URL is derived from [connection]'s base.
     * Mirrors the token read/write discipline so an in-app change takes effect on the next call.
     */
    val voiceUrlOverride: Flow<String> = context.dataStore.data.map { prefs ->
        (prefs[keyVoiceUrlOverride] ?: "").trim()
    }

    /**
     * The PUBLIC live-voice door URL the WATCH dials for the real-call feature (e.g.
     * `wss://eve-voice.<domain>/v1/watch/voice`). Empty by default — the watch shows "not configured"
     * until the owner sets it on the phone. Written to the watch over the Data Layer (paired with the
     * existing [connection] token) by [app.eve.wearbridge.WearBridge]; nothing is hardcoded on the
     * wrist. Trimmed on read/write so a stray space never becomes a bad URL.
     */
    val watchVoiceDoorUrl: Flow<String> = context.dataStore.data.map { prefs ->
        (prefs[keyWatchVoiceDoorUrl] ?: "").trim()
    }

    /**
     * "Meta glasses" capture/audio toggle. LOCAL to this device (unlike the server-synced
     * remote/thinking/barge-in toggles) because it's about hardware paired to THIS phone. Default
     * false: the glasses path is fully opt-in. When on AND the glasses are connected, glasses-sourced
     * (and "any"-sourced) captures come from the glasses camera and Atlas's speech routes to the
     * glasses speaker; when off, the phone owns every capture exactly as before.
     */
    val glassesEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyGlassesEnabled] ?: false
    }

    /** Snapshot read for one-shot request building. */
    suspend fun current(): EveConnection = connection.first()

    /** Snapshot read of the glasses toggle for one-shot routing decisions. */
    suspend fun glassesEnabledNow(): Boolean = glassesEnabled.first()

    /** Snapshot read of the watch live-voice door URL for one-shot Data-Layer writes. */
    suspend fun watchVoiceDoorUrlNow(): String = watchVoiceDoorUrl.first()

    suspend fun setWatchVoiceDoorUrl(url: String) {
        context.dataStore.edit { it[keyWatchVoiceDoorUrl] = url.trim() }
    }

    suspend fun setGlassesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyGlassesEnabled] = enabled }
    }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[keyBaseUrl] = url.trim() }
    }

    suspend fun setToken(token: String) {
        context.dataStore.edit { it[keyToken] = token.trim() }
    }

    suspend fun setVoiceUrlOverride(url: String) {
        context.dataStore.edit { it[keyVoiceUrlOverride] = url.trim() }
    }

    suspend fun set(baseUrl: String, token: String) {
        context.dataStore.edit {
            it[keyBaseUrl] = baseUrl.trim()
            it[keyToken] = token.trim()
        }
    }
}
