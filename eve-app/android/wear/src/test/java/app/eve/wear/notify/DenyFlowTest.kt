package app.eve.wear.notify

import app.eve.data.wear.Outcome
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.data.wear.WearLink
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on [DenyFlow] (the testable core of the wrist Deny) with a fake [GatewayClient] — no
 * GMS, no Android. Mirrors the VM test convention: a standalone [TestScope] driven with
 * runCurrent()/advanceTimeBy(). Every leg maps to an honest [DenyUpdate], never a fake "denied".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DenyFlowTest {

    private class FakeGatewayClient : GatewayClient {
        var sendOutcome: SendOutcome = SendOutcome.Sent
        val sent = mutableListOf<Pair<String, WearAction>>()
        val resultsFlow = MutableSharedFlow<WearActionResult>(extraBufferCapacity = 8)
        override suspend fun sendAction(path: String, action: WearAction): SendOutcome {
            sent += path to action
            return sendOutcome
        }
        override fun results(): Flow<WearActionResult> = resultsFlow
        override suspend fun requestRefresh(): SendOutcome = SendOutcome.Sent
        // Talk/health legs unused by the Deny flow — inert stubs so the fake satisfies the interface.
        override suspend fun sendTalk(request: TalkRequest): SendOutcome = SendOutcome.Sent
        override fun talkReplies(): Flow<TalkReply> = emptyFlow()
        override suspend fun sendHealthAlert(alert: app.eve.data.wear.HealthAlert): SendOutcome = SendOutcome.Sent
        fun emitResult(r: WearActionResult) {
            check(resultsFlow.tryEmit(r)) { "buffer overflow in FakeGatewayClient" }
        }
    }

    private fun flow(gateway: GatewayClient) =
        DenyFlow(gateway, timeoutMs = 8_000L, newRequestId = { "req-1" })

    @Test
    fun happy_deny_sends_on_the_deny_path_and_maps_to_denied_auto_dismiss() {
        val scope = TestScope()
        val gateway = FakeGatewayClient()
        var update: DenyUpdate? = null

        scope.launch { update = flow(gateway).deny("a1") }
        scope.runCurrent() // subscribed to results + sent; now awaiting the reply

        assertEquals(WearLink.PATH_ACTION_DENY, gateway.sent.single().first)
        assertEquals("a1", gateway.sent.single().second.approvalId)

        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.DENIED))
        scope.runCurrent()

        assertEquals("Denied", update!!.message)
        assertTrue(update!!.autoDismiss, "a real denial should clear itself off the wrist")
        scope.cancel()
    }

    @Test
    fun already_resolved_maps_to_handled_elsewhere_and_auto_dismisses() {
        val scope = TestScope()
        val gateway = FakeGatewayClient()
        var update: DenyUpdate? = null

        scope.launch { update = flow(gateway).deny("a1") }
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.ALREADY_RESOLVED))
        scope.runCurrent()

        assertEquals("Already handled elsewhere", update!!.message)
        assertTrue(update!!.autoDismiss)
        scope.cancel()
    }

    @Test
    fun server_unreachable_result_keeps_the_notification_and_names_the_leg() {
        val scope = TestScope()
        val gateway = FakeGatewayClient()
        var update: DenyUpdate? = null

        scope.launch { update = flow(gateway).deny("a1") }
        scope.runCurrent()
        gateway.emitResult(WearActionResult("req-1", "a1", Outcome.SERVER_UNREACHABLE, detail = "connection refused"))
        scope.runCurrent()

        assertEquals("Phone can't reach EVE: connection refused", update!!.message)
        assertTrue(!update!!.autoDismiss, "a failure must stay visible, never auto-clear")
        scope.cancel()
    }

    @Test
    fun timeout_with_no_reply_is_an_honest_failure_not_success() {
        val scope = TestScope()
        val gateway = FakeGatewayClient() // Sent, but no result ever arrives
        var update: DenyUpdate? = null

        scope.launch { update = flow(gateway).deny("a1") }
        scope.runCurrent()
        assertNull(update, "must still be awaiting before the window elapses")

        scope.advanceTimeBy(8_001)
        scope.runCurrent()

        assertEquals("No reply from phone — check the EVE app", update!!.message)
        assertTrue(!update!!.autoDismiss)
        scope.cancel()
    }

    @Test
    fun no_gateway_node_is_immediate_data_layer_down() {
        val scope = TestScope()
        val gateway = FakeGatewayClient().apply { sendOutcome = SendOutcome.NoGatewayNode }
        var update: DenyUpdate? = null

        scope.launch { update = flow(gateway).deny("a1") }
        scope.runCurrent()

        assertEquals("Phone unreachable — Data Layer down", update!!.message)
        assertTrue(!update!!.autoDismiss)
        scope.cancel()
    }

    @Test
    fun send_failed_is_also_data_layer_down() {
        val scope = TestScope()
        val gateway = FakeGatewayClient().apply { sendOutcome = SendOutcome.SendFailed("target busy") }
        var update: DenyUpdate? = null

        scope.launch { update = flow(gateway).deny("a1") }
        scope.runCurrent()

        assertEquals("Phone unreachable — Data Layer down", update!!.message)
        scope.cancel()
    }
}
