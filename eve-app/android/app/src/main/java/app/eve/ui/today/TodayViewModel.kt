package app.eve.ui.today

import app.eve.ASSISTANT_NAME
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.TodayRepository
import app.eve.data.models.Today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TodayUiState {
    data object Loading : TodayUiState
    data class Loaded(
        val today: Today,
        /** Indices of checked action items for [today].date — local, persisted, live. */
        val checked: Set<Int> = emptySet(),
        /** A background refetch is in flight while we keep showing the current content. */
        val refreshing: Boolean = false,
    ) : TodayUiState {
        val doneCount: Int get() = today.actionItems.indices.count { it in checked }
        val total: Int get() = today.actionItems.size
    }
    data class Empty(val date: String) : TodayUiState
    data class Error(val message: String) : TodayUiState
}

/**
 * Loads GET /v1/today and overlays the LOCAL per-date checked state. The checked-set is a live
 * DataStore flow scoped to the loaded date, so ticking an item updates instantly and survives
 * process death. [refresh] re-pulls the ritual; [toggle] persists a single check.
 */
class TodayViewModel(
    private val repo: TodayRepository,
    injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    // Cancelled + replaced whenever a new date loads, so we never watch a stale date's checks.
    private var checksJob: Job? = null

    fun refresh() {
        scope.launch {
            // Keep current content visible during a re-pull; only show the full loader on first load.
            _state.update { s -> if (s is TodayUiState.Loaded) s.copy(refreshing = true) else s }
            when (val r = repo.today()) {
                is ApiResult.Ok -> onLoaded(r.value)
                is ApiResult.Err -> {
                    // A refresh failure shouldn't blank out content the owner is already reading.
                    val current = _state.value
                    if (current is TodayUiState.Loaded) {
                        _state.update { (it as TodayUiState.Loaded).copy(refreshing = false) }
                    } else {
                        _state.value = TodayUiState.Error(messageFor(r.error))
                    }
                }
            }
        }
    }

    private fun onLoaded(today: Today) {
        if (today.isEmpty) {
            checksJob?.cancel()
            _state.value = TodayUiState.Empty(today.date)
            return
        }
        // Watch this date's checked set; each emission rebuilds the Loaded state. A successful
        // load always clears `refreshing` — fresh content has landed.
        checksJob?.cancel()
        checksJob = scope.launch {
            repo.checkedItems(today.date).collect { checked ->
                _state.value = TodayUiState.Loaded(today = today, checked = checked, refreshing = false)
            }
        }
    }

    /** Persist a single action item's checked state (local-only). */
    fun toggle(index: Int, checked: Boolean) {
        val s = _state.value
        if (s !is TodayUiState.Loaded) return
        scope.launch { repo.setChecked(s.today.date, index, checked) }
    }

    private fun messageFor(error: ApiError): String = when (error) {
        is ApiError.Offline -> "Off the tailnet — can't reach today's plan."
        is ApiError.NotConfigured -> "Not connected to $ASSISTANT_NAME yet."
        is ApiError.Unauthorized -> "Session expired — reconnect to $ASSISTANT_NAME."
        else -> "Couldn't load today."
    }
}
