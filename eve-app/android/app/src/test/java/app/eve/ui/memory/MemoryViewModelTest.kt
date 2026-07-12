package app.eve.ui.memory

import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.EveConnection
import app.eve.data.MemoryRepository
import app.eve.data.models.MemoryItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {

    private class FakeRepo(
        var itemsResult: ApiResult<List<MemoryItem>> = ApiResult.Ok(emptyList()),
        var rememberResult: ApiResult<String> = ApiResult.Ok("ok"),
    ) : MemoryRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { EveConnection("", "") },
        ),
    ) {
        var rememberCalls = 0
        var lastItemsSpeaker: String? = "UNSET"
        var lastRememberSpeaker: String? = "UNSET"
        override suspend fun items(speaker: String?): ApiResult<List<MemoryItem>> {
            lastItemsSpeaker = speaker
            return itemsResult
        }
        override suspend fun remember(speaker: String?, fact: String): ApiResult<String> {
            rememberCalls++
            lastRememberSpeaker = speaker
            return rememberResult
        }
    }

    private fun TestScope.collectEvents(vm: MemoryViewModel): Pair<List<String>, Job> {
        val out = mutableListOf<String>()
        val job = launch { vm.events.collect { out.add(it) } }
        runCurrent()
        return out to job
    }

    private fun item(text: String, date: String = "", category: String = "") =
        MemoryItem(text = text, date = date, category = category)

    @Test
    fun load_pullsOwnerMemory_noSpeaker_groupsByCategoryInOrder() {
        val scope = TestScope()
        // Server gives newest-first across categories; the VM groups them in display order.
        val repo = FakeRepo(
            itemsResult = ApiResult.Ok(
                listOf(
                    item("10x the business", "2026-06-22", "business"),
                    item("loves his kids", "2026-06-20", "family"),
                    item("prays each morning", "2026-06-19", "faith"),
                    item("an undated note", "", "weird-unknown"),
                ),
            ),
        )
        val vm = MemoryViewModel(repo, injectedScope = scope)
        vm.load()
        scope.runCurrent()
        val phase = vm.state.value.phase as MemoryPhase.Loaded
        assertEquals(4, phase.total)
        assertFalse(phase.filtered)
        // Faith, Family, Business order (Goals/Prefs absent); unknown category -> Other (last).
        assertEquals(
            listOf(
                MemoryCategory.Faith,
                MemoryCategory.Family,
                MemoryCategory.Business,
                MemoryCategory.Other,
            ),
            phase.groups.map { it.category },
        )
        assertNull(repo.lastItemsSpeaker) // owner page: no speaker sent
        scope.cancel()
    }

    @Test
    fun emptyVault_showsEmptyPhase() {
        val scope = TestScope()
        val vm = MemoryViewModel(FakeRepo(itemsResult = ApiResult.Ok(emptyList())), injectedScope = scope)
        vm.load()
        scope.runCurrent()
        assertTrue(vm.state.value.phase is MemoryPhase.Empty)
        scope.cancel()
    }

    @Test
    fun loadError_showsErrorPhase_andEmitsMessage() {
        val scope = TestScope()
        val repo = FakeRepo(itemsResult = ApiResult.Err(ApiError.Offline("boom")))
        val vm = MemoryViewModel(repo, injectedScope = scope)
        val (events, job) = scope.collectEvents(vm)
        vm.load()
        scope.runCurrent()
        assertTrue(vm.state.value.phase is MemoryPhase.Error)
        assertEquals(listOf("Couldn't load memory."), events)
        job.cancel(); scope.cancel()
    }

    @Test
    fun search_filtersOverTextAndCategory_clientSide() {
        val scope = TestScope()
        val repo = FakeRepo(
            itemsResult = ApiResult.Ok(
                listOf(
                    item("10x the business", "2026-06-22", "business"),
                    item("loves his kids", "2026-06-20", "family"),
                ),
            ),
        )
        val vm = MemoryViewModel(repo, injectedScope = scope)
        vm.load(); scope.runCurrent()

        // Substring over text.
        vm.search("kids"); scope.runCurrent()
        var phase = vm.state.value.phase as MemoryPhase.Loaded
        assertTrue(phase.filtered)
        assertEquals(listOf(MemoryCategory.Family), phase.groups.map { it.category })

        // Substring over category, case-insensitive — no extra fetch.
        vm.search("BUSINESS"); scope.runCurrent()
        phase = vm.state.value.phase as MemoryPhase.Loaded
        assertEquals(listOf(MemoryCategory.Business), phase.groups.map { it.category })

        // No match -> Loaded with empty groups (screen renders a "no match" note, not Empty).
        vm.search("zzz"); scope.runCurrent()
        phase = vm.state.value.phase as MemoryPhase.Loaded
        assertTrue(phase.groups.isEmpty())

        // Clearing restores everything.
        vm.search(""); scope.runCurrent()
        phase = vm.state.value.phase as MemoryPhase.Loaded
        assertFalse(phase.filtered)
        assertEquals(2, phase.total)
        scope.cancel()
    }

    @Test
    fun remember_savesToOwner_noSpeaker_emitsSavedAndReloads() {
        val scope = TestScope()
        val repo = FakeRepo(
            itemsResult = ApiResult.Ok(listOf(item("likes tea", "2026-06-22", "preference"))),
            rememberResult = ApiResult.Ok("saved"),
        )
        val vm = MemoryViewModel(repo, injectedScope = scope)
        val (events, job) = scope.collectEvents(vm)
        vm.remember("likes tea")
        scope.runCurrent()
        val s = vm.state.value
        assertFalse(s.saving)
        assertEquals(1, repo.rememberCalls)
        assertNull(repo.lastRememberSpeaker) // saved to owner page
        val phase = s.phase as MemoryPhase.Loaded // chained load() refreshed the vault
        assertEquals(listOf(MemoryCategory.Preferences), phase.groups.map { it.category })
        assertEquals(listOf("Saved."), events) // one-shot event survives the reload
        job.cancel(); scope.cancel()
    }

    @Test
    fun remember_blankFact_emitsEmptyFactEvent_withoutSaving() {
        val scope = TestScope()
        val repo = FakeRepo()
        val vm = MemoryViewModel(repo, injectedScope = scope)
        val (events, job) = scope.collectEvents(vm)
        vm.remember("   ")
        scope.runCurrent()
        assertEquals(listOf("The fact is empty."), events)
        assertEquals(0, repo.rememberCalls)
        job.cancel(); scope.cancel()
    }
}
