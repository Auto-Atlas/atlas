package app.eve.ui.today

import app.eve.data.ActionItemChecks
import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.EveConnection
import app.eve.data.TodayRepository
import app.eve.data.models.Today
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    /**
     * In-memory [ActionItemChecks] — a live per-date checked-set that emits on every write,
     * mirroring the DataStore-backed production path without Android/DataStore.
     */
    private class FakeChecks : ActionItemChecks {
        private val store = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
        override fun checkedFor(date: String): Flow<Set<Int>> = store.map { it[date] ?: emptySet() }
        override suspend fun setChecked(date: String, index: Int, checked: Boolean) {
            store.update { m ->
                val cur = (m[date] ?: emptySet()).toMutableSet()
                if (checked) cur.add(index) else cur.remove(index)
                m + (date to cur)
            }
        }
    }

    private class FakeRepo(
        var result: ApiResult<Today> = ApiResult.Ok(LOADED),
    ) : TodayRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { EveConnection("", "") },
        ),
        checks = FakeChecks(),
    ) {
        override suspend fun today(): ApiResult<Today> = result
    }

    @Test
    fun refresh_success_emitsLoaded_withItems() {
        val scope = TestScope()
        val vm = TodayViewModel(FakeRepo(), injectedScope = scope)
        vm.refresh()
        scope.runCurrent()
        val s = assertIs<TodayUiState.Loaded>(vm.state.value)
        assertEquals("2026-06-24", s.today.date)
        assertEquals(2, s.total)
        assertEquals(0, s.doneCount)
        scope.cancel()
    }

    @Test
    fun refresh_emptyPayload_emitsEmpty() {
        val scope = TestScope()
        val empty = Today(date = "2026-06-24")
        val vm = TodayViewModel(FakeRepo(ApiResult.Ok(empty)), injectedScope = scope)
        vm.refresh()
        scope.runCurrent()
        val s = assertIs<TodayUiState.Empty>(vm.state.value)
        assertEquals("2026-06-24", s.date)
        scope.cancel()
    }

    @Test
    fun toggle_persistsCheck_andReflectsInState() {
        val scope = TestScope()
        val vm = TodayViewModel(FakeRepo(), injectedScope = scope)
        vm.refresh()
        scope.runCurrent()
        vm.toggle(0, true)
        scope.runCurrent()
        val s = assertIs<TodayUiState.Loaded>(vm.state.value)
        assertEquals(setOf(0), s.checked)
        assertEquals(1, s.doneCount)
        scope.cancel()
    }

    @Test
    fun refresh_offline_emitsError() {
        val scope = TestScope()
        val vm = TodayViewModel(FakeRepo(ApiResult.Err(ApiError.Offline("no route"))), injectedScope = scope)
        vm.refresh()
        scope.runCurrent()
        assertIs<TodayUiState.Error>(vm.state.value)
        scope.cancel()
    }

    private companion object {
        val LOADED = Today(
            date = "2026-06-24",
            user = "Test Owner",
            whys = listOf("A reason to get up that the ritual recites."),
            goals = mapOf("wealth" to listOf("grow the sample business 10x")),
            strategy = "First, ship one thing. Second, tell people about it.",
            actionItems = listOf("ship one thing", "tell people about it"),
        )
    }
}
