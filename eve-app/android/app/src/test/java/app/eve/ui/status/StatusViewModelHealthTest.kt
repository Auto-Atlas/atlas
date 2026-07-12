package app.eve.ui.status

import app.eve.data.ApiClient
import app.eve.data.ApiResult
import app.eve.data.EveConnection
import app.eve.data.StatusRepository
import app.eve.data.models.Health
import app.eve.health.HealthAvailability
import app.eve.health.HealthController
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Status "Health" row's state mapping + actions, through a fake [HealthController] (the seam that
 * keeps Health Connect + WorkManager out of the JVM). Covers the named UI states — unavailable,
 * permissions denied, never-synced, last-synced — and the no-nagging scheduling rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelHealthTest {

    private class Repo : StatusRepository(
        ApiClient(engine = MockEngine { respond("", HttpStatusCode.OK) }, connection = { EveConnection("", "") }),
    ) {
        override suspend fun health(): ApiResult<Health> =
            ApiResult.Ok(Health(ok = true, service = "t", pending = 0, releasingOrphans = 0, remoteApprovalEnabled = false))
        override suspend fun status() = ApiResult.Err(app.eve.data.ApiError.Offline("n/a"))
    }

    private class FakeHealth(
        private val avail: HealthAvailability,
        var permitted: Boolean,
        var last: Long?,
    ) : HealthController {
        var syncNowCalls = 0
        var ensureCalls = 0
        override fun availability() = avail
        override suspend fun hasPermissions() = permitted
        override suspend fun lastUploadAt() = last
        override fun syncNow() { syncNowCalls++ }
        override fun ensurePeriodicScheduled() { ensureCalls++ }
    }

    @Test
    fun `unavailable Health Connect surfaces honestly and schedules nothing`() {
        val scope = TestScope()
        val health = FakeHealth(HealthAvailability.NOT_INSTALLED, permitted = false, last = null)
        val vm = StatusViewModel(Repo(), injectedScope = scope, health = health)

        vm.refresh(); scope.runCurrent()

        val s = vm.state.value
        assertTrue(s.healthSupported)
        assertEquals(HealthAvailability.NOT_INSTALLED, s.healthAvailability)
        assertFalse(s.healthPermitted)
        assertEquals(0, health.ensureCalls, "no nagging / no scheduling when unavailable")
        scope.cancel()
    }

    @Test
    fun `available and permitted with no prior upload is the never-synced state and keeps periodic scheduled`() {
        val scope = TestScope()
        val health = FakeHealth(HealthAvailability.AVAILABLE, permitted = true, last = null)
        val vm = StatusViewModel(Repo(), injectedScope = scope, health = health)

        vm.refresh(); scope.runCurrent()

        val s = vm.state.value
        assertTrue(s.healthPermitted)
        assertNull(s.healthLastUploadAt, "never synced")
        assertEquals(1, health.ensureCalls, "already opted in → keep the periodic worker alive")
        scope.cancel()
    }

    @Test
    fun `available and permitted with a prior upload exposes the last-sync time`() {
        val scope = TestScope()
        val health = FakeHealth(HealthAvailability.AVAILABLE, permitted = true, last = 1_752_000_000_000L)
        val vm = StatusViewModel(Repo(), injectedScope = scope, health = health)

        vm.refresh(); scope.runCurrent()

        assertEquals(1_752_000_000_000L, vm.state.value.healthLastUploadAt)
        scope.cancel()
    }

    @Test
    fun `sync now enqueues an upload and shows the syncing state`() {
        val scope = TestScope()
        val health = FakeHealth(HealthAvailability.AVAILABLE, permitted = true, last = null)
        val vm = StatusViewModel(Repo(), injectedScope = scope, health = health)

        vm.syncHealthNow(); scope.runCurrent()

        assertEquals(1, health.syncNowCalls)
        assertTrue(vm.state.value.healthSyncing)
        scope.cancel()
    }

    @Test
    fun `granting permission schedules periodic and fires a first sync`() {
        val scope = TestScope()
        val health = FakeHealth(HealthAvailability.AVAILABLE, permitted = false, last = null)
        val vm = StatusViewModel(Repo(), injectedScope = scope, health = health)

        // Simulate the permission dialog returning granted.
        health.permitted = true
        vm.onHealthPermissionsChanged(); scope.runCurrent()

        val s = vm.state.value
        assertTrue(s.healthPermitted)
        assertEquals(1, health.ensureCalls)
        assertEquals(1, health.syncNowCalls)
        assertTrue(s.healthSyncing)
        scope.cancel()
    }
}
