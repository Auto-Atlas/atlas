package app.eve.ui.activity

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ActivityRepository
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.models.ConversationDetail
import app.eve.data.models.ConversationSummary
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The conversation feed list (what EVE actually did, newest first). */
sealed interface ActivityUiState {
    data object Loading : ActivityUiState
    data class Loaded(val conversations: List<ConversationSummary>) : ActivityUiState
    /** Connected, brain reachable, but no conversations yet. */
    data object Empty : ActivityUiState
    /** Reached the sidecar but the desktop brain (OpenJarvis) is down. */
    data object Offline : ActivityUiState
    /** Couldn't reach the sidecar at all (off the tailnet / not configured). */
    data class Error(val message: String) : ActivityUiState
}

/** The drill-in detail for one tapped conversation. Null = list view. */
sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Loaded(val detail: ConversationDetail) : DetailUiState
    data object Offline : DetailUiState
    data class Error(val message: String) : DetailUiState
}

class ActivityViewModel(
    private val repo: ActivityRepository,
    injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow<ActivityUiState>(ActivityUiState.Loading)
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    /** Non-null while a conversation is open. The Activity screen renders detail over the list. */
    private val _detail = MutableStateFlow<DetailUiState?>(null)
    val detail: StateFlow<DetailUiState?> = _detail.asStateFlow()

    fun load() {
        scope.launch(CRASH_GUARD) {
            _state.value = ActivityUiState.Loading
            _state.value = when (val r = repo.feed()) {
                is ApiResult.Ok -> when {
                    !r.value.desktopOnline -> ActivityUiState.Offline
                    r.value.conversations.isEmpty() -> ActivityUiState.Empty
                    else -> ActivityUiState.Loaded(r.value.conversations)
                }
                is ApiResult.Err -> ActivityUiState.Error(describe(r.error))
            }
        }
    }

    fun open(convId: String) {
        scope.launch(CRASH_GUARD) {
            _detail.value = DetailUiState.Loading
            _detail.value = when (val r = repo.detail(convId)) {
                is ApiResult.Ok -> {
                    val conv = r.value.conversation
                    when {
                        !r.value.desktopOnline || conv == null -> DetailUiState.Offline
                        else -> DetailUiState.Loaded(conv)
                    }
                }
                is ApiResult.Err -> DetailUiState.Error(describe(r.error))
            }
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    private fun describe(error: ApiError): String = when (error) {
        is ApiError.Offline -> "Off the tailnet."
        is ApiError.NotConfigured -> "Not connected to EVE yet."
        is ApiError.Unauthorized -> "Invalid app token."
        else -> "Couldn't load activity."
    }

    private companion object {
        val CRASH_GUARD = CoroutineExceptionHandler { _, t ->
            Log.e("ActivityViewModel", "uncaught in scope; suppressed to avoid process death", t)
        }
    }
}
