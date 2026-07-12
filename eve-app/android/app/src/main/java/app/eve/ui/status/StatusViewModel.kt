package app.eve.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiResult
import app.eve.data.StatusRepository
import app.eve.data.models.Health
import app.eve.data.models.SystemStatus
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val loading: Boolean = true,
    val online: Boolean = false,
    val health: Health? = null,
    val remoteApprovalEnabled: Boolean = false,
    /** The manual thinking toggle (Epic T): OFF = fast, ON = EVE reasons. */
    val thinkingEnabled: Boolean = false,
    /** True while a toggle write is in flight; the switch is disabled during it. */
    val togglePending: Boolean = false,
    /** True while the THINKING write is in flight (independent of remote-approval's pending). */
    val thinkingPending: Boolean = false,
    /** "Let me interrupt EVE" toggle: OFF = speakerphone-safe, ON = barge-in (best on a headset). */
    val bargeInEnabled: Boolean = false,
    /** True while the BARGE-IN write is in flight (independent of the others' pending). */
    val bargeInPending: Boolean = false,
    /** "Meta glasses" toggle (LOCAL, per-device, default off): route capture + speech to the glasses. */
    val glassesEnabled: Boolean = false,
    /** True while the glasses toggle write is in flight. */
    val glassesTogglePending: Boolean = false,
    /** Whether the glasses toggle is wired at all (production yes; tests/preview may omit it). */
    val glassesSupported: Boolean = false,
    /** Whether the Meta DAT SDK is actually bundled — drives the honest "toolkit not bundled" note. */
    val glassesToolkitAvailable: Boolean = false,
    val errorMessage: String? = null,
    /** Real engine telemetry from /v1/status; null until loaded / when unreachable. */
    val status: SystemStatus? = null,
    /** True when the sidecar is up but the desktop brain (OpenJarvis) is down. */
    val desktopOffline: Boolean = false,

    // ---- Health v1 (the "Health" row) ----
    /** Whether the Health controller is wired at all (production yes; tests/preview may omit it). */
    val healthSupported: Boolean = false,
    /** Health Connect's availability on this device; null until first read. Drives the honest states. */
    val healthAvailability: app.eve.health.HealthAvailability? = null,
    /** True when all six read permissions are granted. */
    val healthPermitted: Boolean = false,
    /** Epoch millis of the last successful upload, or null == never synced. */
    val healthLastUploadAt: Long? = null,
    /** True right after a manual/auto sync is enqueued (the button shows "Syncing…"). */
    val healthSyncing: Boolean = false,
)

/**
 * Local, per-device source for the "Meta glasses" toggle + toolkit-availability. Kept as a tiny seam
 * (not the server-synced StatusRepository) because the glasses are hardware paired to THIS phone —
 * the preference lives in DataStore, not /v1/settings. Null in tests/preview → the row is hidden.
 */
interface GlassesToggle {
    /** Whether the Meta DAT SDK is compiled into this build (false today — token-gated dev preview). */
    val isToolkitAvailable: Boolean
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
}

class StatusViewModel(
    private val repo: StatusRepository,
    injectedScope: CoroutineScope? = null,
    private val glasses: GlassesToggle? = null,
    private val health: app.eve.health.HealthController? = null,
) : ViewModel() {

    // Production: lifecycle-bound viewModelScope (auto-cancelled in onCleared, so launched work
    // never outlives the ViewModel). Tests: an injected TestScope. Each launch also carries
    // CRASH_GUARD as a last-resort safety net (see companion) — repo calls already map failures to
    // ApiResult; this only guards against anything they miss, instead of killing the process.
    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(loading = true, errorMessage = null) }
            // Glasses toggle is LOCAL (DataStore) — independent of the server, so read it up front so
            // the row is truthful even when EVE is unreachable.
            glasses?.let { g ->
                val enabled = g.isEnabled()
                _state.update {
                    it.copy(
                        glassesSupported = true,
                        glassesEnabled = enabled,
                        glassesToolkitAvailable = g.isToolkitAvailable,
                    )
                }
            }
            // Health row is LOCAL (Health Connect + WorkManager on THIS phone) — read up front so it's
            // truthful even when EVE is unreachable.
            health?.let { hc ->
                val availability = hc.availability()
                val permitted = hc.hasPermissions()
                val lastUpload = hc.lastUploadAt()
                _state.update {
                    it.copy(
                        healthSupported = true,
                        healthAvailability = availability,
                        healthPermitted = permitted,
                        healthLastUploadAt = lastUpload,
                        // A freshly-observed newer timestamp means a queued sync landed — drop the spinner.
                        healthSyncing = it.healthSyncing && lastUpload == it.healthLastUploadAt,
                    )
                }
                // No nagging: only keep the periodic worker alive once the user has already opted in.
                if (availability == app.eve.health.HealthAvailability.AVAILABLE && permitted) {
                    hc.ensurePeriodicScheduled()
                }
            }
            when (val h = repo.health()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        online = true,
                        health = h.value,
                        remoteApprovalEnabled = h.value.remoteApprovalEnabled,
                        thinkingEnabled = h.value.thinkingEnabled,
                        bargeInEnabled = h.value.bargeInEnabled,
                    )
                }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        loading = false,
                        online = false,
                        status = null,
                        desktopOffline = false,
                        errorMessage = describe(h.error),
                    )
                }
            }
            // Telemetry is best-effort and independent of the toggle/health controls: a failure here
            // (or a down brain) must never knock out the local approval surface, so it only updates
            // the status section.
            when (val st = repo.status()) {
                is ApiResult.Ok -> _state.value = _state.value.copy(
                    status = st.value,
                    desktopOffline = !st.value.desktopOnline,
                )
                is ApiResult.Err -> _state.value = _state.value.copy(
                    status = null,
                    desktopOffline = false,
                )
            }
        }
    }

    /**
     * The deliberate opt-in front door. Writes POST /v1/settings and only reflects the toggle
     * state the SERVER confirms — an offline/failed write leaves the switch where it was (never
     * a lie about the activation state).
     */
    fun setRemoteApproval(enabled: Boolean) {
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(togglePending = true, errorMessage = null) }
            when (val r = repo.setRemoteApproval(enabled)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        togglePending = false,
                        remoteApprovalEnabled = r.value,
                    )
                }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        togglePending = false,
                        errorMessage = "Couldn't update — ${describe(r.error)}",
                    )
                }
            }
        }
    }

    /**
     * The thinking toggle (Epic T). Like setRemoteApproval, only reflects the value the SERVER
     * confirms — a failed write leaves the switch where it was, never a lie about the state.
     */
    fun setThinking(enabled: Boolean) {
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(thinkingPending = true, errorMessage = null) }
            when (val r = repo.setThinking(enabled)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(thinkingPending = false, thinkingEnabled = r.value)
                }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        thinkingPending = false,
                        errorMessage = "Couldn't update — ${describe(r.error)}",
                    )
                }
            }
        }
    }

    /**
     * "Let me interrupt EVE" toggle. Like setThinking, only reflects the value the SERVER
     * confirms — a failed write leaves the switch where it was. Takes effect on the NEXT
     * voice session (the loop reads it at session start).
     */
    fun setBargeIn(enabled: Boolean) {
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(bargeInPending = true, errorMessage = null) }
            when (val r = repo.setBargeIn(enabled)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(bargeInPending = false, bargeInEnabled = r.value)
                }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        bargeInPending = false,
                        errorMessage = "Couldn't update — ${describe(r.error)}",
                    )
                }
            }
        }
    }

    /**
     * The "Meta glasses" toggle. LOCAL (DataStore) — no server round-trip, so a write can't fail the
     * way the others can; we reflect the requested value once persisted. Takes effect on the NEXT
     * capture event / voice session.
     */
    fun setGlasses(enabled: Boolean) {
        val g = glasses ?: return
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(glassesTogglePending = true, errorMessage = null) }
            g.setEnabled(enabled)
            _state.update { it.copy(glassesTogglePending = false, glassesEnabled = enabled) }
        }
    }

    /**
     * "Sync now" on the Health row: enqueue an immediate upload. We can't await the background worker,
     * so we show a transient "Syncing…" state; the fresh last-sync time appears on the next [refresh]
     * (the screen refreshes on entry). Never claims success — only a real upload updates the timestamp.
     */
    fun syncHealthNow() {
        val hc = health ?: return
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(healthSyncing = true) }
            hc.syncNow()
        }
    }

    /**
     * Called after the Health Connect permission dialog returns. Re-reads the granted state and, when
     * fully permitted, schedules the periodic worker AND fires a first sync so data shows up promptly.
     */
    fun onHealthPermissionsChanged() {
        val hc = health ?: return
        scope.launch(CRASH_GUARD) {
            val permitted = hc.hasPermissions()
            _state.update { it.copy(healthPermitted = permitted) }
            if (permitted) {
                hc.ensurePeriodicScheduled()
                hc.syncNow()
                _state.update { it.copy(healthSyncing = true) }
            }
        }
    }

    private fun describe(error: app.eve.data.ApiError): String = when (error) {
        is app.eve.data.ApiError.NotConfigured -> "not connected to EVE yet"
        is app.eve.data.ApiError.Offline -> "off the tailnet"
        is app.eve.data.ApiError.Unauthorized -> "invalid app token"
        is app.eve.data.ApiError.NotFound -> "not found"
        is app.eve.data.ApiError.AlreadyResolved -> "already changed"
        is app.eve.data.ApiError.Http -> "server error ${error.status}"
        is app.eve.data.ApiError.Decode -> "unexpected response"
        is app.eve.data.ApiError.Unknown -> error.message
    }

    private companion object {
        /** Last-resort guard: swallow+log any uncaught throw so the scope never crashes the app. */
        val CRASH_GUARD = CoroutineExceptionHandler { _, t ->
            Log.e("StatusViewModel", "uncaught in scope; suppressed to avoid process death", t)
        }
    }
}
