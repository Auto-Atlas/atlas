package app.eve.ui.approvals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.ApproveOutcome
import app.eve.data.ApprovalRepository
import app.eve.data.DenyOutcome
import app.eve.data.models.Approval
import app.eve.data.models.ApprovalsResponse
import app.eve.ui.theme.EveTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for ApprovalsScreen — EVE's hero screen (compose-ui-testing-patterns: drive the
 * real state holder to each branch, then assert the rendered text/semantics). The VM auto-launches
 * an infinite stream collector + ticker in init, so each test gives it a standalone TestScope and
 * drives it with refresh()/runCurrent() — the same discipline as ApprovalsViewModelTest — before
 * setContent. createComposeRule allows setContent ONCE per test → one @Test per state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    /** A fake repo whose pending() returns a fixed list or error (mirrors ApprovalsViewModelTest). */
    private class FakeRepo(
        var pendingList: List<Approval> = emptyList(),
        var pendingError: ApiError? = null,
    ) : ApprovalRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { app.eve.data.EveConnection("", "") },
        ),
    ) {
        override suspend fun pending(): ApiResult<List<Approval>> =
            pendingError?.let { ApiResult.Err(it) } ?: ApiResult.Ok(pendingList)
        override suspend fun approve(id: String): ApproveOutcome = ApproveOutcome.Sent
        override suspend fun deny(id: String): DenyOutcome = DenyOutcome.Denied
    }

    private fun fixtureApprovals(): List<Approval> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json"))
            .bufferedReader().use { it.readText() }
        return ApiClient.DEFAULT_JSON.decodeFromString<ApprovalsResponse>(text).approvals
    }

    @Test
    fun empty_state_renders_all_clear_copy() {
        val scope = TestScope()
        val repo = FakeRepo(pendingList = emptyList())
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { 1L })
        vm.refresh()
        scope.runCurrent()

        rule.setContent {
            EveTheme {
                ApprovalsScreen(viewModel = vm)
            }
        }

        // The empty inbox's spec copy.
        rule.onNodeWithText("All clear").assertIsDisplayed()
        rule.onNodeWithText("Nothing waiting.").assertIsDisplayed()
        scope.cancel()
    }

    @Test
    fun items_state_renders_design_header_with_count_and_guarded_pill() {
        val now = 1_000_000_000L
        val scope = TestScope()
        // One pending card → header reads "1 action waiting on you".
        val repo = FakeRepo(pendingList = listOf(fixtureApprovals().first()))
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { now })
        vm.refresh()
        scope.runCurrent()

        rule.setContent {
            EveTheme {
                ApprovalsScreen(viewModel = vm)
            }
        }

        // The hero header: title, the live waiting-count subtitle (singular), and the trust pill.
        rule.onNodeWithText("Approvals").assertIsDisplayed()
        rule.onNodeWithText("1 action waiting on you").assertIsDisplayed()
        rule.onNodeWithText("Guarded").assertIsDisplayed()
        scope.cancel()
    }

    @Test
    fun offline_state_renders_banner_and_reconnecting_subtitle() {
        val scope = TestScope()
        val repo = FakeRepo(pendingError = ApiError.Offline("connection refused"))
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { 1L })
        vm.refresh()
        scope.runCurrent()

        rule.setContent {
            EveTheme {
                ApprovalsScreen(viewModel = vm)
            }
        }

        // The honest off-tailnet banner (never silently showing "all clear" while blind).
        rule.onNode(hasText("Can't reach EVE — you're off the tailnet", substring = true))
            .assertExists()
        // The header subtitle flips to the reconnecting variant while offline.
        rule.onNodeWithText("Reconnecting to EVE…").assertIsDisplayed()
        scope.cancel()
    }
}
