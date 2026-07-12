package app.eve.ui.approvals

import app.eve.data.ApiClient
import app.eve.data.ApiResult
import app.eve.data.ApprovalRepository
import app.eve.data.CancelOutcome
import app.eve.data.RedirectOutcome
import app.eve.data.models.AgentTasksResponse
import app.eve.data.models.Approval
import app.eve.data.models.StreamEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live Agent Activity fold (live-delegation-approvals): agent_* stream events open and drive
 * per-task cards; Cancel/Redirect call the repo and reflect HONEST outcomes (cancel-requested
 * is not cancelled until the stop is observed). Same harness as ApprovalsViewModelTest:
 * standalone TestScope + runCurrent, hand-rolled fakes, no mockk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelAgentActivityTest {

    private class FakeRepo(
        var cancelOutcome: CancelOutcome = CancelOutcome.Requested("cancel requested — hermes will stop at its next check-in"),
        var redirectOutcome: RedirectOutcome = RedirectOutcome.Staged("redirect staged"),
        var tasks: AgentTasksResponse = AgentTasksResponse(),
    ) : ApprovalRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { app.eve.data.EveConnection("", "") },
        ),
    ) {
        val cancelCalls = mutableListOf<String>()
        val redirectCalls = mutableListOf<Pair<String, String>>()
        override suspend fun pending(): ApiResult<List<Approval>> = ApiResult.Ok(emptyList())
        override suspend fun agentTasks(): ApiResult<AgentTasksResponse> = ApiResult.Ok(tasks)
        override suspend fun cancelTask(id: String): CancelOutcome {
            cancelCalls.add(id); return cancelOutcome
        }
        override suspend fun redirectTask(id: String, instructions: String): RedirectOutcome {
            redirectCalls.add(id to instructions); return redirectOutcome
        }
    }

    private fun fixtureTasks(): AgentTasksResponse {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("agent_tasks_sample.json"))
            .bufferedReader().use { it.readText() }
        return ApiClient.DEFAULT_JSON.decodeFromString(AgentTasksResponse.serializer(), text)
    }

    private fun vm(
        scope: TestScope,
        events: MutableSharedFlow<StreamEvent> = MutableSharedFlow(extraBufferCapacity = 16),
        repo: FakeRepo = FakeRepo(),
    ) = Triple(ApprovalsViewModel(repo, events, scope, nowMs = { 1_000_000_000L }), events, repo)

    @Test
    fun assigned_event_opens_a_working_card() {
        val scope = TestScope()
        val (vm, events, _) = vm(scope)
        scope.runCurrent()   // let the init collector subscribe before emitting
        events.tryEmit(
            StreamEvent(type = StreamEvent.TYPE_AGENT_TASK_ASSIGNED, taskId = "t1",
                        agent = "hermes", task = "audit the shop site", status = "pending"),
        )
        scope.runCurrent()
        val cards = vm.agentActivity.value
        assertEquals(1, cards.size)
        assertEquals("hermes", cards[0].agent)
        assertEquals(AgentTaskState.Working, cards[0].state)
        assertTrue(cards[0].taskText.contains("audit the shop site"))
        scope.cancel()
    }

    @Test
    fun progress_appends_live_feed_lines_and_question_flips_waiting() {
        val scope = TestScope()
        val (vm, events, _) = vm(scope)
        scope.runCurrent()   // let the init collector subscribe before emitting
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_TASK_ASSIGNED, taskId = "t1",
                                   agent = "hermes", task = "job"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_PROGRESS, taskId = "t1",
                                   agent = "hermes", text = "crawling the site"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_PROGRESS, taskId = "t1",
                                   agent = "hermes", text = "found 3 broken links"))
        scope.runCurrent()
        var card = vm.agentActivity.value.single()
        assertTrue(card.feed.any { it.contains("crawling the site") })
        assertTrue(card.feed.last().contains("found 3 broken links"))
        assertEquals(AgentTaskState.Working, card.state)

        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_QUESTION, taskId = "t1",
                                   agent = "hermes", text = "which env?"))
        scope.runCurrent()
        card = vm.agentActivity.value.single()
        assertEquals(AgentTaskState.WaitingOnYou, card.state)
        assertEquals("which env?", card.question)
        scope.cancel()
    }

    @Test
    fun result_and_blocker_terminalize_cards() {
        val scope = TestScope()
        val (vm, events, _) = vm(scope)
        scope.runCurrent()   // let the init collector subscribe before emitting
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_PROGRESS, taskId = "t1",
                                   agent = "hermes", text = "working"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_RESULT, taskId = "t1",
                                   agent = "hermes", text = "all done"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_BLOCKER, taskId = "t2",
                                   agent = "hermes", text = "no creds"))
        scope.runCurrent()
        val byId = vm.agentActivity.value.associateBy { it.id }
        assertEquals(AgentTaskState.Done, byId["t1"]?.state)
        assertEquals(AgentTaskState.Failed, byId["t2"]?.state)
        scope.cancel()
    }

    @Test
    fun cancel_reflects_honest_cooperative_state_until_stop_observed() {
        val scope = TestScope()
        val (vm, events, repo) = vm(scope)
        scope.runCurrent()   // let the init collector subscribe before emitting
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_TASK_ASSIGNED, taskId = "t1",
                                   agent = "hermes", task = "job"))
        scope.runCurrent()

        vm.cancelTask("t1")
        scope.runCurrent()
        assertEquals(listOf("t1"), repo.cancelCalls)
        var card = vm.agentActivity.value.single()
        assertEquals(AgentTaskState.CancelPending, card.state)   // NOT Cancelled yet — honest
        assertTrue(card.feed.last().contains("check-in"))

        // The observed stop arrives on the stream → the card flips terminal.
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_TASK_CANCELLED, taskId = "t1",
                                   agent = "hermes", status = "cancelled"))
        scope.runCurrent()
        card = vm.agentActivity.value.single()
        assertEquals(AgentTaskState.Cancelled, card.state)
        scope.cancel()
    }

    @Test
    fun redirect_sends_instructions_and_feed_shows_the_steer_landing() {
        val scope = TestScope()
        val (vm, events, repo) = vm(scope)
        scope.runCurrent()   // let the init collector subscribe before emitting
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_TASK_ASSIGNED, taskId = "t1",
                                   agent = "hermes", task = "job"))
        scope.runCurrent()

        vm.redirectTask("t1", "only the checkout flow")
        scope.runCurrent()
        assertEquals(listOf("t1" to "only the checkout flow"), repo.redirectCalls)
        assertTrue(vm.agentActivity.value.single().feed.last().contains("redirect staged"))

        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_TASK_REDIRECTED, taskId = "t1",
                                   agent = "hermes", text = "only the checkout flow",
                                   status = "redirect_delivered"))
        scope.runCurrent()
        val card = vm.agentActivity.value.single()
        assertTrue(card.feed.last().contains("only the checkout flow"))
        assertEquals(AgentTaskState.Working, card.state)
        scope.cancel()
    }

    @Test
    fun refresh_seeds_cards_from_the_server_list_including_waiting_state() {
        val scope = TestScope()
        val repo = FakeRepo(tasks = fixtureTasks())
        val (vm, _, _) = vm(scope, repo = repo)
        vm.refresh()
        scope.runCurrent()
        val cards = vm.agentActivity.value
        val byId = cards.associateBy { it.id }
        val waiting = byId["ffff23def456abc123def456abc123de"]
        assertEquals(AgentTaskState.WaitingOnYou, waiting?.state)
        assertEquals("which date works?", waiting?.question)
        val done = byId["0000000000000000000000000000dead"]
        assertEquals(AgentTaskState.Done, done?.state)
        assertEquals(false, done?.canRedirect)
        assertEquals("task already finished", done?.redirectReason)
        scope.cancel()
    }

    @Test
    fun stream_failure_flips_health_flag_so_ui_can_show_reconnecting() {
        val scope = TestScope()
        val failing = kotlinx.coroutines.flow.flow<StreamEvent> {
            throw java.io.IOException("socket dropped")
        }
        val vm = ApprovalsViewModel(FakeRepo(), failing, scope, nowMs = { 0L })
        assertTrue(vm.streamHealthy.value, "starts optimistic")
        scope.runCurrent()
        assertEquals(false, vm.streamHealthy.value)   // dropped => visibly unhealthy, no lie
        scope.cancel()
    }

    // ---- Brain delegations (jarvis_agent: claude code over ACP / codex / glm) ----
    // The single-brain routing (2026-07-08, no waterfall) is untouched — these cover
    // DISPLAY of the trace events one run already emits.

    @Test
    fun brain_delegation_start_opens_a_watch_only_card_with_display_name() {
        val scope = TestScope()
        val (vm, events, _) = vm(scope)
        scope.runCurrent()
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_START, delegId = "d1",
                                   task = "fix the login bug", brains = listOf("acp")))
        scope.runCurrent()
        val card = vm.agentActivity.value.single()
        assertEquals("claude code", card.agent)          // acp IS claude code (no -p / no SDK)
        assertEquals(AgentTaskState.Working, card.state)
        assertTrue(card.taskText.contains("fix the login bug"))
        // Watch-only: brain runs have no talk-back channel — honest disabled controls.
        assertEquals(false, card.canCancel)
        assertEquals(false, card.canRedirect)
        assertTrue(card.redirectReason!!.isNotBlank())
        scope.cancel()
    }

    @Test
    fun brain_delegation_steps_feed_and_heartbeats_collapse() {
        val scope = TestScope()
        val (vm, events, _) = vm(scope)
        scope.runCurrent()
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_START, delegId = "d1",
                                   task = "job", brains = listOf("codex")))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_STEP, delegId = "d1",
                                   brain = "codex", phase = "try"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_STEP, delegId = "d1",
                                   brain = "codex", phase = "working", detail = "10s"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_STEP, delegId = "d1",
                                   brain = "codex", phase = "working", detail = "20s"))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_STEP, delegId = "d1",
                                   brain = "codex", phase = "working", detail = "30s"))
        scope.runCurrent()
        val card = vm.agentActivity.value.single()
        // Heartbeats REPLACE the previous heartbeat line — three of them must not flood
        // the feed; the latest elapsed time shows.
        assertEquals(1, card.feed.count { it.contains("working") })
        assertTrue(card.feed.last().contains("30s"))
        scope.cancel()
    }

    @Test
    fun brain_delegation_end_terminalizes_with_full_result_for_the_detail_view() {
        val scope = TestScope()
        val (vm, events, _) = vm(scope)
        scope.runCurrent()
        val longResult = "line one of the answer\n" + "x".repeat(600)
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_START, delegId = "d1",
                                   task = "job", brains = listOf("codex")))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_END, delegId = "d1",
                                   brain = "codex", ok = true, result = longResult))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_START, delegId = "d2",
                                   task = "other", brains = listOf("glm")))
        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_DELEGATION_END, delegId = "d2",
                                   ok = false, failures = listOf("glm: timed out after 300s")))
        scope.runCurrent()
        val byId = vm.agentActivity.value.associate { it.agent to it }
        val done = byId["codex"]!!
        assertEquals(AgentTaskState.Done, done.state)
        assertEquals(longResult, done.fullResult)        // UNTRUNCATED — the tap-detail shows it
        val failed = byId["glm"]!!
        assertEquals(AgentTaskState.Failed, failed.state)
        assertTrue(failed.feed.last().contains("timed out"))
        scope.cancel()
    }

    @Test
    fun acp_rows_and_events_display_as_claude_code() {
        // The ACP brain's talk-back rows are agent="acp" on the wire — the owner knows it
        // as claude code (ACP claude --bg; no -p, no SDK). Both the fetched-row seed and
        // live agent_* events must display the human name.
        val scope = TestScope()
        val repo = FakeRepo(tasks = AgentTasksResponse(active = listOf(
            app.eve.data.models.AgentTaskDto(id = "r1", agent = "acp",
                task = "build a page", status = "pending", delivery = "push"),
        )))
        val events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 4)
        val vm = ApprovalsViewModel(repo, events, scope, nowMs = { 0L })
        vm.refresh()
        scope.runCurrent()
        assertEquals("claude code", vm.agentActivity.value.single().agent)

        events.tryEmit(StreamEvent(type = StreamEvent.TYPE_AGENT_PROGRESS, taskId = "r2",
                                   agent = "acp", text = "working"))
        scope.runCurrent()
        val fromEvent = vm.agentActivity.value.first { it.id == "r2" }
        assertEquals("claude code", fromEvent.agent)
        scope.cancel()
    }
}
