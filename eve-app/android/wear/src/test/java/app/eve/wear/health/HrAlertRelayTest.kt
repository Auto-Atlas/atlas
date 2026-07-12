package app.eve.wear.health

import app.eve.data.wear.HealthAlert
import app.eve.data.wear.TalkReply
import app.eve.data.wear.TalkRequest
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * App-scoped glue between the passive HR stream (HrAlertService hands samples in) and the phone
 * gateway (alerts go out). The policy state lives HERE, not in the service — Health Services
 * recreates service instances at will and the hysteresis/cooldown memory must survive that.
 */
class HrAlertRelayTest {

    private class FakeGateway : GatewayClient {
        val alerts = mutableListOf<HealthAlert>()
        var outcome: SendOutcome = SendOutcome.Sent
        override suspend fun sendHealthAlert(alert: HealthAlert): SendOutcome {
            alerts += alert
            return outcome
        }
        override suspend fun sendAction(path: String, action: WearAction): SendOutcome = SendOutcome.Sent
        override fun results(): Flow<WearActionResult> = emptyFlow()
        override suspend fun requestRefresh(): SendOutcome = SendOutcome.Sent
        override suspend fun sendTalk(request: TalkRequest): SendOutcome = SendOutcome.Sent
        override fun talkReplies(): Flow<TalkReply> = emptyFlow()
    }

    @Test
    fun crossing_sample_reaches_the_gateway() {
        val scope = TestScope()
        val gateway = FakeGateway()
        val relay = HrAlertRelay(gateway, scope, HrAlertPolicy(highBpm = 120))

        relay.onHeartRateSamples(listOf(90.0 to 0L, 142.0 to 60_000L))
        scope.runCurrent()

        assertEquals(1, gateway.alerts.size)
        assertEquals("hr_high", gateway.alerts[0].type)
        assertEquals(142, gateway.alerts[0].bpm)
    }

    @Test
    fun quiet_samples_send_nothing() {
        val scope = TestScope()
        val gateway = FakeGateway()
        val relay = HrAlertRelay(gateway, scope, HrAlertPolicy(highBpm = 120))

        relay.onHeartRateSamples(listOf(70.0 to 0L, 80.0 to 60_000L))
        scope.runCurrent()

        assertTrue(gateway.alerts.isEmpty())
    }

    @Test
    fun policy_state_survives_across_batches_like_service_recreations() {
        val scope = TestScope()
        val gateway = FakeGateway()
        val relay = HrAlertRelay(gateway, scope, HrAlertPolicy(highBpm = 120))

        relay.onHeartRateSamples(listOf(90.0 to 0L, 130.0 to 60_000L))
        scope.runCurrent()
        // The next batch arrives via a NEW service instance but the SAME relay: still disarmed.
        relay.onHeartRateSamples(listOf(135.0 to 120_000L))
        scope.runCurrent()

        assertEquals(1, gateway.alerts.size)
    }

    @Test
    fun failed_send_never_throws_out_of_the_relay() {
        val scope = TestScope()
        val gateway = FakeGateway().apply { outcome = SendOutcome.NoGatewayNode }
        val relay = HrAlertRelay(gateway, scope, HrAlertPolicy(highBpm = 120))

        relay.onHeartRateSamples(listOf(90.0 to 0L, 142.0 to 60_000L))
        scope.runCurrent() // reaching here without a throw IS the assertion (failure is logged)
    }
}
