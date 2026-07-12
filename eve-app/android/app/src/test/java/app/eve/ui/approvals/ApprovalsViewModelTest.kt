package app.eve.ui.approvals

import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.ApproveOutcome
import app.eve.data.ApprovalRepository
import app.eve.data.DenyOutcome
import app.eve.data.models.Approval
import app.eve.data.models.ApprovalsResponse
import app.eve.data.models.StreamEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The VM auto-launches an infinite stream collector + 1s ticker in init. Each test gives it a
 * standalone TestScope (its own scheduler), drives it with runCurrent()/advanceTimeBy() — NOT
 * advanceUntilIdle, which would never return against the infinite loops — and cancels the scope
 * at the end so no coroutine leaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelTest {

    /** A fake repo whose approve/deny outcomes the test controls; pending() returns fixtures. */
    private class FakeRepo(
        var approveOutcome: ApproveOutcome = ApproveOutcome.Sent,
        var denyOutcome: DenyOutcome = DenyOutcome.Denied,
        var pendingList: List<Approval> = emptyList(),
        var pendingError: ApiError? = null,
    ) : ApprovalRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { app.eve.data.EveConnection("", "") },
        ),
    ) {
        var approveCalls = 0
        override suspend fun pending(): ApiResult<List<Approval>> =
            pendingError?.let { ApiResult.Err(it) } ?: ApiResult.Ok(pendingList)
        override suspend fun approve(id: String): ApproveOutcome { approveCalls++; return approveOutcome }
        override suspend fun deny(id: String): DenyOutcome = denyOutcome
    }

    private val json = ApiClient.DEFAULT_JSON
    private fun fixtureApprovals(): List<Approval> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json"))
            .bufferedReader().use { it.readText() }
        return json.decodeFromString<ApprovalsResponse>(text).approvals
    }

    /** A fixed-clock approval: expires `secs` from `nowMs`. */
    private fun approvalExpiringIn(id: String, secs: Long, nowMs: Long): Approval {
        val base = fixtureApprovals()[0]
        val expiresAt = (nowMs / 1000.0) + secs
        return base.copy(id = id, expiresAt = expiresAt, createdAt = nowMs / 1000.0, secondsLeft = secs.toDouble())
    }

    @Test
    fun countdown_ticks_down_and_flips_to_expired_at_zero() {
        val now = longArrayOf(1_000_000_000L)
        val scope = TestScope()
        val repo = FakeRepo(pendingList = listOf(approvalExpiringIn("x", secs = 2, nowMs = now[0])))
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { now[0] }, tickMs = 1_000)
        vm.refresh()
        scope.runCurrent()
        val items = assertIs<ApprovalsUiState.Items>(vm.state.value)
        assertEquals(2L, items.cards[0].secondsLeft)
        assertIs<CardPhase.Pending>(items.cards[0].phase)

        // Advance wall clock past expiry, then let the 1s ticker fire once.
        now[0] += 3_000
        scope.advanceTimeBy(1_100)
        scope.runCurrent()
        val expired = assertIs<ApprovalsUiState.Items>(vm.state.value)
        assertIs<CardPhase.Expired>(expired.cards[0].phase)
        assertEquals(0L, expired.cards[0].secondsLeft)
        scope.cancel()
    }

    @Test
    fun tick_with_unchanged_countdown_keeps_card_identity_no_churn() {
        val now = longArrayOf(1_000_000_000L)
        val scope = TestScope()
        val repo = FakeRepo(pendingList = listOf(approvalExpiringIn("x", secs = 30, nowMs = now[0])))
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { now[0] }, tickMs = 1_000)
        vm.refresh()
        scope.runCurrent()
        val before = assertIs<ApprovalsUiState.Items>(vm.state.value).cards[0]

        // Tick fires but the clock hasn't moved → same secondsLeft → the SAME instance is kept
        // (no copy), so strong-skipping lets the card skip recomposition (no per-second churn).
        scope.advanceTimeBy(1_100)
        scope.runCurrent()
        val afterStill = assertIs<ApprovalsUiState.Items>(vm.state.value).cards[0]
        assertSame(before, afterStill)

        // Clock advances one second → countdown changes → a NEW instance (a legitimate recompose).
        now[0] += 1_000
        scope.advanceTimeBy(1_100)
        scope.runCurrent()
        val afterTick = assertIs<ApprovalsUiState.Items>(vm.state.value).cards[0]
        assertNotSame(before, afterTick)
        assertEquals(29L, afterTick.secondsLeft)
        scope.cancel()
    }

    @Test
    fun under_60s_is_marked_urgent() {
        val now = 1_000_000_000L
        val scope = TestScope()
        val repo = FakeRepo(pendingList = listOf(approvalExpiringIn("x", secs = 45, nowMs = now)))
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { now })
        vm.refresh()
        scope.runCurrent()
        val items = assertIs<ApprovalsUiState.Items>(vm.state.value)
        assertTrue(items.cards[0].isUrgent, "45s left -> urgent (amber)")
        scope.cancel()
    }

    @Test
    fun stream_resolved_event_flips_an_open_card_to_resolved() {
        val now = 1_000_000_000L
        val scope = TestScope()
        val events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 4)
        val repo = FakeRepo(pendingList = listOf(approvalExpiringIn("abc", secs = 600, nowMs = now)))
        val vm = ApprovalsViewModel(repo, events, scope, nowMs = { now })
        vm.refresh()
        scope.runCurrent()

        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_RESOLVED, id = "abc", ok = true))
        scope.runCurrent()

        val items = assertIs<ApprovalsUiState.Items>(vm.state.value)
        val phase = assertIs<CardPhase.Resolved>(items.cards[0].phase)
        // Resolved by another device => Elsewhere (we didn't hold to approve locally).
        assertEquals(ResolvedOutcome.Elsewhere, phase.outcome)
        scope.cancel()
    }

    @Test
    fun approve_ok_false_yields_send_failed_not_false_success() {
        val now = 1_000_000_000L
        val scope = TestScope()
        val repo = FakeRepo(
            approveOutcome = ApproveOutcome.SendFailed,
            pendingList = listOf(approvalExpiringIn("abc", secs = 600, nowMs = now)),
        )
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { now })
        vm.refresh()
        scope.runCurrent()

        vm.approve("abc")
        scope.runCurrent()

        val items = assertIs<ApprovalsUiState.Items>(vm.state.value)
        val phase = assertIs<CardPhase.Resolved>(items.cards[0].phase)
        assertEquals(ResolvedOutcome.SendFailed, phase.outcome, "ok:false must never read as Sent")
        scope.cancel()
    }

    @Test
    fun offline_disables_actions() {
        val scope = TestScope()
        val repo = FakeRepo(pendingError = ApiError.Offline("connection refused"))
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { 1L })
        vm.refresh()
        scope.runCurrent()
        assertIs<ApprovalsUiState.Offline>(vm.state.value)

        // approve() on an offline state must be a no-op (no repo call).
        vm.approve("abc")
        scope.runCurrent()
        assertEquals(0, repo.approveCalls, "actions disabled while offline")
        scope.cancel()
    }

    @Test
    fun empty_is_distinct_from_offline() {
        val scope = TestScope()
        val repo = FakeRepo(pendingList = emptyList())
        val vm = ApprovalsViewModel(repo, MutableSharedFlow(), scope, nowMs = { 1L })
        vm.refresh()
        scope.runCurrent()
        assertIs<ApprovalsUiState.Empty>(vm.state.value)
        scope.cancel()
    }
}
