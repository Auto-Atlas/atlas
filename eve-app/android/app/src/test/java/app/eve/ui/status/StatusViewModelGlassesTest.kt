package app.eve.ui.status

import app.eve.data.ApiClient
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
 * The local "Meta glasses" toggle on the Status screen: read on refresh, persisted through the
 * [GlassesToggle] seam on write, and honest about whether the DAT SDK is bundled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelGlassesTest {

    private class Repo : StatusRepository(
        ApiClient(engine = MockEngine { respond("", HttpStatusCode.OK) }, connection = { EveConnection("", "") }),
    ) {
        override suspend fun health(): ApiResult<Health> =
            ApiResult.Ok(
                Health(
                    ok = true,
                    service = "test",
                    pending = 0,
                    releasingOrphans = 0,
                    remoteApprovalEnabled = false,
                ),
            )
        override suspend fun status() = ApiResult.Err(app.eve.data.ApiError.Offline("n/a"))
    }

    private class FakeGlasses(
        override val isToolkitAvailable: Boolean,
        var enabled: Boolean = false,
    ) : GlassesToggle {
        var writes = 0
        override suspend fun isEnabled(): Boolean = enabled
        override suspend fun setEnabled(enabled: Boolean) { writes++; this.enabled = enabled }
    }

    @Test
    fun `refresh reads the glasses toggle and toolkit-availability`() {
        val scope = TestScope()
        val glasses = FakeGlasses(isToolkitAvailable = false, enabled = true)
        val vm = StatusViewModel(Repo(), injectedScope = scope, glasses = glasses)

        vm.refresh()
        scope.runCurrent()

        val s = vm.state.value
        assertTrue(s.glassesSupported)
        assertTrue(s.glassesEnabled)
        assertFalse(s.glassesToolkitAvailable, "stub build → toolkit not bundled, surfaced honestly")
        scope.cancel()
    }

    @Test
    fun `setGlasses persists and reflects the new value`() {
        val scope = TestScope()
        val glasses = FakeGlasses(isToolkitAvailable = true, enabled = false)
        val vm = StatusViewModel(Repo(), injectedScope = scope, glasses = glasses)

        vm.setGlasses(true)
        scope.runCurrent()

        assertEquals(1, glasses.writes)
        assertTrue(glasses.enabled, "written through the toggle seam")
        assertTrue(vm.state.value.glassesEnabled)
        assertFalse(vm.state.value.glassesTogglePending)
        scope.cancel()
    }

    @Test
    fun `glasses row stays hidden when no toggle is wired`() {
        val scope = TestScope()
        val vm = StatusViewModel(Repo(), injectedScope = scope, glasses = null)

        vm.refresh()
        scope.runCurrent()
        vm.setGlasses(true) // no-op, must not crash
        scope.runCurrent()

        assertFalse(vm.state.value.glassesSupported)
        assertFalse(vm.state.value.glassesEnabled)
        scope.cancel()
    }
}
