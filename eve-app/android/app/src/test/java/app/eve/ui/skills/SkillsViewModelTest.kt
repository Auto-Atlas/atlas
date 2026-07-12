package app.eve.ui.skills

import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.SkillsRepository
import app.eve.data.models.ClearResult
import app.eve.data.models.FeedDto
import app.eve.data.models.FeedMode
import app.eve.data.models.FeedResult
import app.eve.data.models.SkillDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {

    private class FakeRepo(
        var skills: List<SkillDto> = emptyList(),
        var feeds: List<FeedDto> = emptyList(),
        var listError: ApiError? = null,
    ) : SkillsRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { app.eve.data.EveConnection("", "") },
        ),
    ) {
        var fedTool: String? = null
        var fedMode: FeedMode? = null
        override suspend fun list(): ApiResult<List<SkillDto>> =
            listError?.let { ApiResult.Err(it) } ?: ApiResult.Ok(skills)
        override suspend fun pendingFeeds(): ApiResult<List<FeedDto>> = ApiResult.Ok(feeds)
        override suspend fun feed(tool: String, mode: FeedMode): ApiResult<FeedResult> {
            fedTool = tool; fedMode = mode
            return ApiResult.Ok(FeedResult(ok = true, tool = tool, mode = mode.wire))
        }
        override suspend fun unprime(tool: String): ApiResult<ClearResult> =
            ApiResult.Ok(ClearResult(ok = true, cleared = 1))
    }

    private fun skill(tool: String, risk: String, confirm: Boolean = false) =
        SkillDto(tool = tool, catalog = "does $tool", risk = risk, requiresConfirmation = confirm)

    @Test
    fun loads_and_groups_by_risk_high_first() {
        val scope = TestScope()
        val repo = FakeRepo(skills = listOf(skill("w", "low"), skill("inv", "high", true)))
        val vm = SkillsViewModel(repo, scope)
        vm.refresh(); scope.runCurrent()
        val loaded = assertIs<SkillsUiState.Loaded>(vm.state.value)
        assertEquals(RiskLevel.High, loaded.groups.first().risk)
        assertEquals("inv", loaded.groups.first().rows[0].tool)
        assertEquals(true, loaded.groups.first().rows[0].requiresConfirmation)
        scope.cancel()
    }

    @Test
    fun pending_next_feed_marks_row_primed() {
        val scope = TestScope()
        val repo = FakeRepo(
            skills = listOf(skill("inv", "high")),
            feeds = listOf(FeedDto(tool = "inv", mode = "next", status = "pending", secondsLeft = 100.0)),
        )
        val vm = SkillsViewModel(repo, scope)
        vm.refresh(); scope.runCurrent()
        val loaded = assertIs<SkillsUiState.Loaded>(vm.state.value)
        assertEquals(FeedState.PrimedForNext, loaded.groups.first().rows[0].feedState)
        scope.cancel()
    }

    @Test
    fun offline_yields_offline_state() {
        val scope = TestScope()
        val vm = SkillsViewModel(FakeRepo(listError = ApiError.Offline("nope")), scope)
        vm.refresh(); scope.runCurrent()
        assertIs<SkillsUiState.Offline>(vm.state.value)
        scope.cancel()
    }

    @Test
    fun feed_calls_repo_with_mode() {
        val scope = TestScope()
        val repo = FakeRepo(skills = listOf(skill("inv", "high")))
        val vm = SkillsViewModel(repo, scope)
        vm.refresh(); scope.runCurrent()
        vm.feed("inv", FeedMode.Live); scope.runCurrent()
        assertEquals("inv", repo.fedTool)
        assertEquals(FeedMode.Live, repo.fedMode)
        val loaded = assertIs<SkillsUiState.Loaded>(vm.state.value)
        assertEquals(FeedState.HandedToEve, loaded.groups.first().rows[0].feedState)
        scope.cancel()
    }
}
