package app.eve.ui.status

import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.EveConnection
import app.eve.data.StatusRepository
import app.eve.data.models.Health
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [StatusViewModel] with an injected [TestScope] (the same seam the production code uses,
 * defaulting to viewModelScope). runCurrent() flushes the launched coroutine; the scope is
 * cancelled at the end so nothing leaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {

    private class FakeRepo(
        var healthResult: ApiResult<Health> = ApiResult.Ok(SAMPLE_HEALTH),
        var setResult: ApiResult<Boolean> = ApiResult.Ok(true),
        var thinkingResult: ApiResult<Boolean> = ApiResult.Ok(true),
    ) : StatusRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { EveConnection("", "") },
        ),
    ) {
        override suspend fun health(): ApiResult<Health> = healthResult
        override suspend fun setRemoteApproval(enabled: Boolean): ApiResult<Boolean> = setResult
        override suspend fun setThinking(enabled: Boolean): ApiResult<Boolean> = thinkingResult
    }

    @Test
    fun refresh_success_setsOnlineHealthAndRemoteFlag() {
        val scope = TestScope()
        val vm = StatusViewModel(FakeRepo(), injectedScope = scope)
        vm.refresh()
        scope.runCurrent()
        val s = vm.state.value
        assertFalse(s.loading)
        assertTrue(s.online)
        assertEquals(SAMPLE_HEALTH, s.health)
        assertTrue(s.remoteApprovalEnabled)
        assertTrue(s.thinkingEnabled)            // Epic T: read alongside remote-approval from health
        assertEquals(null, s.errorMessage)
        scope.cancel()
    }

    @Test
    fun setThinking_reflectsServerConfirmedValue_only() {
        val scope = TestScope()
        // Server confirms OFF even though the user asked ON — UI must follow the server, not the tap.
        val vm = StatusViewModel(FakeRepo(thinkingResult = ApiResult.Ok(false)), injectedScope = scope)
        vm.setThinking(enabled = true)
        scope.runCurrent()
        val s = vm.state.value
        assertFalse(s.thinkingPending)
        assertFalse(s.thinkingEnabled)
        scope.cancel()
    }

    @Test
    fun refresh_error_setsOfflineWithMessage() {
        val scope = TestScope()
        val vm = StatusViewModel(FakeRepo(healthResult = ApiResult.Err(ApiError.Offline("no route"))), injectedScope = scope)
        vm.refresh()
        scope.runCurrent()
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.online)
        assertEquals("off the tailnet", s.errorMessage)
        scope.cancel()
    }

    @Test
    fun setRemoteApproval_reflectsServerConfirmedValue_only() {
        val scope = TestScope()
        // Server confirms DISABLED even though the user asked to enable — UI must follow the server.
        val vm = StatusViewModel(FakeRepo(setResult = ApiResult.Ok(false)), injectedScope = scope)
        vm.setRemoteApproval(enabled = true)
        scope.runCurrent()
        val s = vm.state.value
        assertFalse(s.togglePending)
        assertFalse(s.remoteApprovalEnabled)
        scope.cancel()
    }

    @Test
    fun setRemoteApproval_failure_clearsPendingAndMessages() {
        val scope = TestScope()
        val vm = StatusViewModel(FakeRepo(setResult = ApiResult.Err(ApiError.Unauthorized)), injectedScope = scope)
        vm.setRemoteApproval(enabled = true)
        scope.runCurrent()
        val s = vm.state.value
        assertFalse(s.togglePending)
        assertTrue(s.errorMessage!!.contains("invalid app token"))
        scope.cancel()
    }

    private companion object {
        val SAMPLE_HEALTH = Health(
            ok = true,
            service = "eve-approval-api",
            pending = 0,
            releasingOrphans = 0,
            remoteApprovalEnabled = true,
            thinkingEnabled = true,
        )
    }
}
