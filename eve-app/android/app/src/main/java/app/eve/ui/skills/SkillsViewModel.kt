package app.eve.ui.skills

import app.eve.ASSISTANT_NAME
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.SkillsRepository
import app.eve.data.models.FeedDto
import app.eve.data.models.FeedMode
import app.eve.data.models.SkillDto
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SkillsViewModel(
    private val repo: SkillsRepository,
    injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope
    private val _state = MutableStateFlow<SkillsUiState>(SkillsUiState.Loading)
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch(CRASH_GUARD) {
            _state.value = SkillsUiState.Loading
            when (val skills = repo.list()) {
                is ApiResult.Err -> _state.value = errorState(skills.error)
                is ApiResult.Ok -> {
                    // Feeds are best-effort: a feed-list failure shouldn't blank the catalog.
                    val feeds = (repo.pendingFeeds() as? ApiResult.Ok)?.value ?: emptyList()
                    _state.value = SkillsUiState.Loaded(group(skills.value, feeds))
                }
            }
        }
    }

    fun feed(tool: String, mode: FeedMode) {
        setRowState(tool, FeedState.Sending)
        scope.launch(CRASH_GUARD) {
            when (repo.feed(tool, mode)) {
                is ApiResult.Ok -> setRowState(
                    tool,
                    if (mode == FeedMode.Next) FeedState.PrimedForNext else FeedState.HandedToEve,
                )
                is ApiResult.Err -> setRowState(tool, FeedState.Idle)
            }
        }
    }

    fun unprime(tool: String) {
        scope.launch(CRASH_GUARD) {
            if (repo.unprime(tool) is ApiResult.Ok) setRowState(tool, FeedState.Idle)
        }
    }

    private fun group(skills: List<SkillDto>, feeds: List<FeedDto>): List<RiskGroup> {
        val feedByTool = feeds.associateBy { it.tool }
        val rows = skills.map { s ->
            SkillRow(
                tool = s.tool,
                catalog = s.catalog,
                risk = riskOf(s.risk),
                requiresConfirmation = s.requiresConfirmation,
                feedState = feedStateOf(feedByTool[s.tool]),
            )
        }
        return listOf(RiskLevel.High, RiskLevel.Medium, RiskLevel.Low)
            .map { lvl -> RiskGroup(lvl, rows.filter { it.risk == lvl }) }
            .filter { it.rows.isNotEmpty() }
    }

    private fun feedStateOf(feed: FeedDto?): FeedState = when {
        feed == null -> FeedState.Idle
        feed.status == "expired" -> FeedState.Expired
        feed.mode == "next" -> FeedState.PrimedForNext
        else -> FeedState.HandedToEve
    }

    /** Replace one row's feedState in place, preserving every other row's identity (so stable
     *  rows skip recomposition). No-op unless we're already Loaded. */
    private fun setRowState(tool: String, fs: FeedState) {
        val cur = _state.value as? SkillsUiState.Loaded ?: return
        _state.value = SkillsUiState.Loaded(
            cur.groups.map { g ->
                if (g.rows.none { it.tool == tool }) g
                else g.copy(rows = g.rows.map { if (it.tool == tool) it.copy(feedState = fs) else it })
            },
        )
    }

    private fun errorState(error: ApiError): SkillsUiState = when (error) {
        is ApiError.Offline, is ApiError.NotConfigured -> SkillsUiState.Offline
        else -> SkillsUiState.Error(describe(error))
    }

    private fun describe(error: ApiError): String = when (error) {
        is ApiError.NotConfigured -> "not connected to $ASSISTANT_NAME yet"
        is ApiError.Offline -> "off the tailnet"
        is ApiError.Unauthorized -> "invalid app token"
        is ApiError.NotFound -> "$ASSISTANT_NAME doesn't have that skill"
        is ApiError.AlreadyResolved -> "already changed"
        is ApiError.Http -> "server error ${error.status}"
        is ApiError.Decode -> "unexpected response"
        is ApiError.Unknown -> error.message
    }

    private companion object {
        val CRASH_GUARD = CoroutineExceptionHandler { _, t ->
            Log.e("SkillsViewModel", "uncaught in scope; suppressed to avoid process death", t)
        }
    }
}
