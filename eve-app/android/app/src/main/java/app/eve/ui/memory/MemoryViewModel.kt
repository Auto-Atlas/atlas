package app.eve.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiResult
import app.eve.data.MemoryRepository
import app.eve.data.models.MemoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Display states for the Memory tab. The list is small now but grows as EVE's `remember` tool
 * fires, so the tab is built around grouping + search from the start.
 *
 * - [Loading]  first load in flight, nothing to show yet.
 * - [Empty]    loaded, the vault is genuinely empty (EVE hasn't learned anything).
 * - [Loaded]   has facts; [groups] is the category-bucketed, ordered render model.
 * - [Error]    the load failed.
 */
sealed interface MemoryPhase {
    data object Loading : MemoryPhase
    data object Empty : MemoryPhase
    data object Error : MemoryPhase
    data class Loaded(val groups: List<MemoryGroup>, val total: Int, val filtered: Boolean) :
        MemoryPhase
}

/** A category header + the facts under it (already filtered by the active query). */
data class MemoryGroup(val category: MemoryCategory, val items: List<MemoryItem>)

/**
 * The owner-fact categories, in the order they render. `key` matches the server's `category`
 * string; `label` is the human header. `general`/unknown -> Other (always last).
 */
enum class MemoryCategory(val key: String, val label: String) {
    Faith("faith", "Faith"),
    Health("health", "Health"),
    Family("family", "Family"),
    Business("business", "Business"),
    Goals("goal", "Goals"),
    Preferences("preference", "Preferences"),
    Other("general", "Other");

    companion object {
        /** Map a raw server category to a bucket; blanks and unknowns fall into Other. */
        fun from(raw: String): MemoryCategory =
            entries.firstOrNull { it.key.equals(raw.trim(), ignoreCase = true) } ?: Other
    }
}

data class MemoryUiState(
    val phase: MemoryPhase = MemoryPhase.Loading,
    val query: String = "",
    val saving: Boolean = false,
)

/**
 * Shows EVE's ACTUAL memory: the owner page (jarvis-memory.md, the boot pack), loaded with no
 * speaker. No typing required to view — load() pulls the real vault and renders the structured
 * `items` grouped by category. A search box filters client-side (substring over text + category);
 * an optional add box writes a new fact straight to the owner page (no speaker → owner).
 */
class MemoryViewModel(
    private val repo: MemoryRepository,
    injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    // The raw, newest-first vault as loaded — grouping/filtering derive from this, never refetch.
    private var allItems: List<MemoryItem> = emptyList()
    private var lastLoadFailed = false

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    // One-shot user feedback ("Saved.", errors) — a Channel, never sticky state (can't replay
    // after rotation; the chained load() in remember() can't wipe a just-emitted confirmation).
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    /** Load the owner's real memory (no speaker). Items are already newest-first from the server. */
    fun load() {
        scope.launch {
            _state.update { it.copy(phase = MemoryPhase.Loading) }
            when (val r = repo.items()) {
                is ApiResult.Ok -> {
                    allItems = r.value
                    lastLoadFailed = false
                    recompute()
                }
                is ApiResult.Err -> {
                    allItems = emptyList()
                    lastLoadFailed = true
                    _state.update { it.copy(phase = MemoryPhase.Error) }
                    _events.trySend("Couldn't load memory.")
                }
            }
        }
    }

    /** Update the live search query and re-derive the visible groups (no network). */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        if (!lastLoadFailed) recompute()
    }

    /** Save a fact to the owner page — no speaker required (it's the owner's own memory). */
    fun remember(fact: String) {
        val cleaned = fact.trim()
        if (cleaned.isBlank()) {
            _events.trySend("The fact is empty.")
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true) }
            when (repo.remember(fact = cleaned)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(saving = false) }
                    _events.trySend("Saved.")
                    load()
                }
                is ApiResult.Err -> {
                    _state.update { it.copy(saving = false) }
                    _events.trySend("Couldn't save.")
                }
            }
        }
    }

    /** Derive the render phase from [allItems] + the current query. Pure, no I/O. */
    private fun recompute() {
        val query = _state.value.query.trim()
        if (allItems.isEmpty()) {
            _state.update { it.copy(phase = MemoryPhase.Empty) }
            return
        }
        val visible =
            if (query.isEmpty()) allItems
            else allItems.filter {
                it.text.contains(query, ignoreCase = true) ||
                    it.category.contains(query, ignoreCase = true)
            }
        // Bucket by category in display order; keep each bucket newest-first (input order).
        val groups = MemoryCategory.entries.mapNotNull { cat ->
            val inCat = visible.filter { MemoryCategory.from(it.category) == cat }
            if (inCat.isEmpty()) null else MemoryGroup(cat, inCat)
        }
        _state.update {
            it.copy(
                phase = MemoryPhase.Loaded(
                    groups = groups,
                    total = allItems.size,
                    filtered = query.isNotEmpty(),
                ),
            )
        }
    }
}
