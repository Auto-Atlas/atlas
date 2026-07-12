package app.eve.wear.approvals

import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.Outcome
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.wear.PhoneLinkState
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import app.eve.wear.data.SnapshotSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on [WearApprovalsViewModel] with fakes (no GMS, no mocking library). Mirrors the
 * :app ApprovalsViewModel test convention: a standalone [TestScope] whose infinite init collectors
 * are driven with runCurrent()/advanceTimeBy() (never advanceUntilIdle) and cancelled at the end.
 * Every state transition + failure leg is asserted with its EXACT user copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearApprovalsViewModelTest {

    private class FakeSnapshotSource : SnapshotSource {
        val flow = MutableSharedFlow<ApprovalsSnapshot>(extraBufferCapacity = 8)
        var currentValue: ApprovalsSnapshot? = null
        override fun snapshots(): Flow<ApprovalsSnapshot> = flow
        override suspend fun current(): ApprovalsSnapshot? = currentValue
        fun emit(s: ApprovalsSnapshot) {
            currentValue = s
            check(flow.tryEmit(s)) { "buffer overflow in FakeSnapshotSource" }
        }
    }

    private class FakeGatewayClient : GatewayClient {
        var sendOutcome: SendOutcome = SendOutcome.Sent
        val sent = mutableListOf<Pair<String, WearAction>>()
        var refreshCount = 0
        var refreshOutcome: SendOutcome = SendOutcome.Sent
        val resultsFlow = MutableSharedFlow<WearActionResult>(extraBufferCapacity = 8)
        override suspend fun sendAction(path: String, action: WearAction): SendOutcome {
            sent += path to action
            return sendOutcome
        }
        override fun results(): Flow<WearActionResult> = resultsFlow
        override suspend fun requestRefresh(): SendOutcome { refreshCount++; return refreshOutcome }
        // Talk/health legs unused by the approvals VM — inert stubs so the fake satisfies the interface.
        override suspend fun sendTalk(request: TalkRequest): SendOutcome = SendOutcome.Sent
        override fun talkReplies(): Flow<TalkReply> = emptyFlow()
        override suspend fun sendHealthAlert(alert: app.eve.data.wear.HealthAlert): SendOutcome = SendOutcome.Sent
        fun emitResult(r: WearActionResult) {
            check(resultsFlow.tryEmit(r)) { "buffer overflow in FakeGatewayClient" }
        }
    }

    /** Build a started VM whose init collectors are already subscribed. */
    private fun startedVm(
        scope: TestScope,
        snapshots: FakeSnapshotSource,
        gateway: FakeGatewayClient,
        requestId: String = "req-1",
    ): WearApprovalsViewModel {
        val vm = WearApprovalsViewModel(
            snapshotSource = snapshots,
            gateway = gateway,
            scope = scope,
            nowMs = { 10_000L },
            newRequestId = { requestId },
        )
        scope.runCurrent()
        return vm
    }

    // ---- snapshot fold ------------------------------------------------------

    @Test
    fun pending_snapshot_becomes_pending_state() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val vm = startedVm(scope, snapshots, FakeGatewayClient())

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()

        val state = assertIs<WearApprovalsUiState.Pending>(vm.uiState.value)
        assertEquals("a1", state.approvals.single().id)
        scope.cancel()
    }

    @Test
    fun empty_snapshot_becomes_empty_state() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val vm = startedVm(scope, snapshots, FakeGatewayClient())

        snapshots.emit(TestApprovals.pendingSnapshot(emptyList()))
        scope.runCurrent()

        assertIs<WearApprovalsUiState.Empty>(vm.uiState.value)
        scope.cancel()
    }

    @Test
    fun server_down_snapshot_shows_real_detail_and_stale_prior_list() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val vm = startedVm(scope, snapshots, FakeGatewayClient())

        // A prior GOOD list, then the phone loses EVE.
        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        snapshots.emit(TestApprovals.serverDownSnapshot("cannot reach EVE: connection refused", atMs = 5_000L))
        scope.runCurrent()

        val state = assertIs<WearApprovalsUiState.ServerDown>(vm.uiState.value)
        assertEquals("cannot reach EVE: connection refused", state.detail)
        assertEquals("a1", state.staleApprovals?.single()?.id)
        assertEquals(5_000L, state.fetchedAtEpochMs)
        scope.cancel()
    }

    @Test
    fun server_down_with_no_prior_list_has_null_stale() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val vm = startedVm(scope, snapshots, FakeGatewayClient())

        snapshots.emit(TestApprovals.serverDownSnapshot("phone not connected to EVE"))
        scope.runCurrent()

        val state = assertIs<WearApprovalsUiState.ServerDown>(vm.uiState.value)
        assertNull(state.staleApprovals)
        scope.cancel()
    }

    @Test
    fun next_snapshot_drives_removal_and_prunes_action_banner() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        vm.approve("a1")
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.APPROVED))
        scope.runCurrent()
        assertTrue(vm.actions.value.containsKey("a1"))

        // The phone re-fetched; the resolved row is gone → Empty AND the action banner is pruned.
        snapshots.emit(TestApprovals.pendingSnapshot(emptyList()))
        scope.runCurrent()

        assertIs<WearApprovalsUiState.Empty>(vm.uiState.value)
        assertTrue(vm.actions.value.isEmpty(), "resolved-then-removed row must not keep a banner")
        scope.cancel()
    }

    // ---- phone-link diagnosis (pre-first-snapshot) --------------------------

    @Test
    fun not_reachable_before_snapshot_shows_no_phone_leg() {
        val scope = TestScope()
        val vm = startedVm(scope, FakeSnapshotSource(), FakeGatewayClient())

        vm.reportPhoneLink(PhoneLinkState.NotReachable)

        val state = assertIs<WearApprovalsUiState.NoPhone>(vm.uiState.value)
        assertEquals("Phone unreachable — Data Layer down", state.reason)
        scope.cancel()
    }

    @Test
    fun failed_link_surfaces_the_real_reason() {
        val scope = TestScope()
        val vm = startedVm(scope, FakeSnapshotSource(), FakeGatewayClient())

        vm.reportPhoneLink(PhoneLinkState.Failed("Play services unavailable"))

        assertEquals(
            "Phone link failed: Play services unavailable",
            assertIs<WearApprovalsUiState.NoPhone>(vm.uiState.value).reason,
        )
        scope.cancel()
    }

    @Test
    fun phone_link_is_ignored_once_a_snapshot_exists() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val vm = startedVm(scope, snapshots, FakeGatewayClient())

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        // A late "not reachable" must NOT wipe the real pending list to NoPhone.
        vm.reportPhoneLink(PhoneLinkState.NotReachable)

        assertIs<WearApprovalsUiState.Pending>(vm.uiState.value)
        scope.cancel()
    }

    // ---- approve / deny action legs -----------------------------------------

    @Test
    fun approve_happy_marks_inflight_then_approved_invoice_released() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()

        vm.approve("a1")
        scope.runCurrent()
        assertIs<WearActionState.InFlight>(vm.actions.value["a1"])
        assertEquals("req-1", gateway.sent.single().second.requestId)

        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.APPROVED))
        scope.runCurrent()

        val resolved = assertIs<WearActionState.Resolved>(vm.actions.value["a1"])
        assertEquals("Approved — invoice released", resolved.message)
        assertEquals(WearActionState.Tone.Positive, resolved.tone)
        scope.cancel()
    }

    @Test
    fun deny_happy_maps_to_denied() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()

        vm.deny("a1")
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.DENIED))
        scope.runCurrent()

        assertEquals("Denied", assertIs<WearActionState.Resolved>(vm.actions.value["a1"]).message)
        scope.cancel()
    }

    @Test
    fun already_resolved_names_leg_three() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        vm.approve("a1")
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.ALREADY_RESOLVED))
        scope.runCurrent()

        assertEquals(
            "Already handled elsewhere",
            assertIs<WearActionState.Resolved>(vm.actions.value["a1"]).message,
        )
        scope.cancel()
    }

    @Test
    fun server_unreachable_result_shows_phone_cannot_reach_eve_with_detail() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        vm.approve("a1")
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.SERVER_UNREACHABLE, detail = "connection refused"))
        scope.runCurrent()

        assertEquals(
            "Phone can't reach EVE: connection refused",
            assertIs<WearActionState.Resolved>(vm.actions.value["a1"]).message,
        )
        scope.cancel()
    }

    @Test
    fun no_gateway_node_is_immediate_data_layer_down_leg() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient().apply { sendOutcome = SendOutcome.NoGatewayNode }
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        vm.approve("a1")
        scope.runCurrent()

        val resolved = assertIs<WearActionState.Resolved>(vm.actions.value["a1"])
        assertEquals("Phone unreachable — Data Layer down", resolved.message)
        assertEquals(WearActionState.Tone.Negative, resolved.tone)
        scope.cancel()
    }

    @Test
    fun send_failed_names_leg_one_with_real_reason() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient().apply { sendOutcome = SendOutcome.SendFailed("target api busy") }
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        vm.approve("a1")
        scope.runCurrent()

        assertEquals(
            "Phone unreachable — Data Layer down: target api busy",
            assertIs<WearActionState.Resolved>(vm.actions.value["a1"]).message,
        )
        scope.cancel()
    }

    @Test
    fun no_reply_within_timeout_is_an_honest_failure_not_success() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient() // Sent, but no result ever arrives
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()
        vm.approve("a1")
        scope.runCurrent()
        assertIs<WearActionState.InFlight>(vm.actions.value["a1"])

        // Past the 20s window with no reply → honest "No reply from phone", never a fake success.
        scope.advanceTimeBy(20_001)
        scope.runCurrent()

        val resolved = assertIs<WearActionState.Resolved>(vm.actions.value["a1"])
        assertEquals("No reply from phone", resolved.message)
        assertEquals(WearActionState.Tone.Negative, resolved.tone)
        scope.cancel()
    }

    @Test
    fun channel_approval_reports_sent_not_invoice_released() {
        val scope = TestScope()
        val snapshots = FakeSnapshotSource()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, snapshots, gateway)

        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.channel("c1"))))
        scope.runCurrent()
        vm.approve("c1")
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "c1", Outcome.APPROVED))
        scope.runCurrent()

        assertEquals(
            "Approved — sent",
            assertIs<WearActionState.Resolved>(vm.actions.value["c1"]).message,
        )
        scope.cancel()
    }

    @Test
    fun request_refresh_is_a_single_one_shot() {
        val scope = TestScope()
        val gateway = FakeGatewayClient()
        val vm = startedVm(scope, FakeSnapshotSource(), gateway)

        vm.requestRefresh()
        scope.runCurrent()

        assertEquals(1, gateway.refreshCount)
        scope.cancel()
    }

    // ---- Pre-first-snapshot refresh honesty (the Wear OS 5 emulator eternal-spinner gap) ----

    @Test
    fun refresh_with_no_gateway_node_before_first_snapshot_names_the_missing_eve_app() {
        val scope = TestScope()
        val gateway = FakeGatewayClient().apply { refreshOutcome = SendOutcome.NoGatewayNode }
        val vm = startedVm(scope, FakeSnapshotSource(), gateway)

        vm.requestRefresh()
        scope.runCurrent()

        assertEquals(
            "Phone link up, but EVE isn't reachable on the phone — open the EVE app",
            assertIs<WearApprovalsUiState.NoPhone>(vm.uiState.value).reason,
        )
        scope.cancel()
    }

    @Test
    fun refresh_send_failure_before_first_snapshot_names_the_data_layer_leg() {
        val scope = TestScope()
        val gateway = FakeGatewayClient().apply { refreshOutcome = SendOutcome.SendFailed("transport reset") }
        val vm = startedVm(scope, FakeSnapshotSource(), gateway)

        vm.requestRefresh()
        scope.runCurrent()

        assertEquals(
            "Phone unreachable — Data Layer down: transport reset",
            assertIs<WearApprovalsUiState.NoPhone>(vm.uiState.value).reason,
        )
        scope.cancel()
    }

    @Test
    fun refresh_sent_but_no_snapshot_within_the_watchdog_says_so_instead_of_spinning() {
        val scope = TestScope()
        val gateway = FakeGatewayClient() // refreshOutcome = Sent
        val vm = startedVm(scope, FakeSnapshotSource(), gateway)

        vm.requestRefresh()
        scope.runCurrent()
        // Still waiting inside the window — the spinner is honest here.
        scope.advanceTimeBy(WearApprovalsViewModel.FIRST_SNAPSHOT_WAIT_MS - 1)
        scope.runCurrent()
        assertIs<WearApprovalsUiState.Loading>(vm.uiState.value)

        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(
            "No data from the phone yet — open the EVE app on your phone",
            assertIs<WearApprovalsUiState.NoPhone>(vm.uiState.value).reason,
        )
        scope.cancel()
    }

    @Test
    fun snapshot_arriving_inside_the_watchdog_window_wins_and_cancels_it() {
        val scope = TestScope()
        val gateway = FakeGatewayClient()
        val snapshots = FakeSnapshotSource()
        val vm = startedVm(scope, snapshots, gateway)

        vm.requestRefresh()
        scope.runCurrent()
        snapshots.emit(TestApprovals.pendingSnapshot(listOf(TestApprovals.invoice("a1"))))
        scope.runCurrent()

        scope.advanceTimeBy(WearApprovalsViewModel.FIRST_SNAPSHOT_WAIT_MS + 1_000)
        scope.runCurrent()
        // The authoritative snapshot stays — the watchdog never overwrote it.
        assertIs<WearApprovalsUiState.Pending>(vm.uiState.value)
        scope.cancel()
    }
}
