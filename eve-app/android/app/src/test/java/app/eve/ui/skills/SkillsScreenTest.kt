package app.eve.ui.skills

import app.eve.ASSISTANT_NAME
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.eve.data.ApiClient
import app.eve.data.ApiResult
import app.eve.data.SkillsRepository
import app.eve.data.models.ClearResult
import app.eve.data.models.FeedDto
import app.eve.data.models.FeedMode
import app.eve.data.models.FeedResult
import app.eve.data.models.SkillDto
import app.eve.ui.theme.EveTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for SkillsScreen — the shipped Skills-in-app feature (compose-ui-testing-patterns:
 * drive the real SkillsViewModel to each branch with a FakeRepo, then assert rendered text/semantics
 * rather than pixels). The VM launches coroutines on an injected TestScope, so each test drives it
 * with refresh()/runCurrent() — the same discipline as SkillsViewModelTest — before setContent.
 * createComposeRule allows setContent ONCE per test → one @Test per state. scope.cancel() at end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkillsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    /** Fake repo returning a fixed catalog (mirrors SkillsViewModelTest.FakeRepo). */
    private class FakeRepo(
        var skills: List<SkillDto> = emptyList(),
        var feeds: List<FeedDto> = emptyList(),
    ) : SkillsRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { app.eve.data.EveConnection("", "") },
        ),
    ) {
        override suspend fun list(): ApiResult<List<SkillDto>> = ApiResult.Ok(skills)
        override suspend fun pendingFeeds(): ApiResult<List<FeedDto>> = ApiResult.Ok(feeds)
        override suspend fun feed(tool: String, mode: FeedMode): ApiResult<FeedResult> =
            ApiResult.Ok(FeedResult(ok = true, tool = tool, mode = mode.wire))
        override suspend fun unprime(tool: String): ApiResult<ClearResult> =
            ApiResult.Ok(ClearResult(ok = true, cleared = 0))
    }

    private fun skill(tool: String, risk: String, confirm: Boolean = false) =
        SkillDto(tool = tool, catalog = "does $tool", risk = risk, requiresConfirmation = confirm)

    @Test
    fun loaded_renders_catalog_grouped_by_risk_with_confirmation_marker() {
        val scope = TestScope()
        // One high-risk requires_confirmation skill + one low-risk skill spanning two groups.
        val repo = FakeRepo(
            skills = listOf(
                skill("create_invoice", "high", confirm = true),
                skill("get_weather", "low"),
            ),
        )
        val vm = SkillsViewModel(repo, scope)
        vm.refresh()
        scope.runCurrent()

        rule.setContent {
            EveTheme {
                SkillsScreen(viewModel = vm)
            }
        }

        // Screen title.
        rule.onNodeWithText("Skills").assertIsDisplayed()
        // Both skills' tool + catalog text render.
        rule.onNodeWithText("create_invoice").assertIsDisplayed()
        rule.onNodeWithText("does create_invoice").assertIsDisplayed()
        rule.onNodeWithText("get_weather").assertIsDisplayed()
        rule.onNodeWithText("does get_weather").assertIsDisplayed()
        // Risk group headers (group.risk.name.uppercase()).
        rule.onNodeWithText("HIGH").assertIsDisplayed()
        rule.onNodeWithText("LOW").assertIsDisplayed()
        // The requires-confirmation marker shows for the high-risk skill.
        rule.onNodeWithText("Asks first 🔒").assertIsDisplayed()
        scope.cancel()
    }

    @Test
    fun offline_renders_off_tailnet_copy() {
        val scope = TestScope()
        // Offline maps repo Offline error → SkillsUiState.Offline. Drive via a repo that errors.
        val repo = object : SkillsRepository(
            ApiClient(
                engine = MockEngine { respond("", HttpStatusCode.OK) },
                connection = { app.eve.data.EveConnection("", "") },
            ),
        ) {
            override suspend fun list(): ApiResult<List<SkillDto>> =
                ApiResult.Err(app.eve.data.ApiError.Offline("nope"))
        }
        val vm = SkillsViewModel(repo, scope)
        vm.refresh()
        scope.runCurrent()

        rule.setContent {
            EveTheme {
                SkillsScreen(viewModel = vm)
            }
        }

        rule.onNodeWithText("Off the tailnet — can't reach $ASSISTANT_NAME.").assertIsDisplayed()
        scope.cancel()
    }
}
