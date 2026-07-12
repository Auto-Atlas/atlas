package app.eve.wear.health

import android.util.Log
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.SendOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-scoped glue between the passive HR stream and the phone gateway (Health v2). [HrAlertService]
 * hands each batch of (bpm, sensor-time) samples in; the pure [HrAlertPolicy] decides; a due alert goes
 * out via [GatewayClient.sendHealthAlert]. Lives in [app.eve.wear.di.WearContainer] because Health
 * Services recreates service instances at will and the hysteresis/cooldown memory must survive that.
 *
 * House rule — no silent fallbacks: a failed send is Log.e'd with the named leg. It is NOT retried
 * here: the passive stream keeps flowing (the policy re-fires after re-arm+cooldown), and Samsung
 * Health's own high/low HR alert remains the OS-level safety net underneath Atlas's voice.
 */
class HrAlertRelay(
    private val gateway: GatewayClient,
    private val scope: CoroutineScope,
    private val policy: HrAlertPolicy,
) {
    /** Fold one delivered batch. Samples are (bpm, epochMs) in delivery order. */
    fun onHeartRateSamples(samples: List<Pair<Double, Long>>) {
        for ((bpm, atMs) in samples) {
            val alert = policy.onSample(bpm, atMs) ?: continue
            scope.launch {
                when (val outcome = gateway.sendHealthAlert(alert)) {
                    SendOutcome.Sent ->
                        Log.i(TAG, "HR alert ${alert.requestId} (${alert.type} ${alert.bpm} bpm) sent to phone")
                    SendOutcome.NoGatewayNode ->
                        Log.e(TAG, "HR alert ${alert.requestId} NOT delivered — no phone gateway reachable")
                    is SendOutcome.SendFailed ->
                        Log.e(TAG, "HR alert ${alert.requestId} NOT delivered — send failed: ${outcome.reason}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "HrAlertRelay"
    }
}
