package app.eve.wear.health

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import app.eve.wear.WearApplication

/**
 * The thin Health Services edge (Health v2): receives BATCHED passive HEART_RATE_BPM samples —
 * delivery is minutes-late on a dozing watch, which is why every sample carries its own sensor
 * timestamp — and hands them to the app-scoped [HrAlertRelay] (policy state lives THERE; this
 * service is recreated at the OS's whim). Registered by [HrPassiveMonitor]; declared in the
 * manifest with the PASSIVE_DATA_BINDING permission.
 */
class HrAlertService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootInstant = java.time.Instant.ofEpochMilli(
            System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime(),
        )
        val samples = dataPoints.getData(DataType.HEART_RATE_BPM).map { point ->
            point.value to point.getTimeInstant(bootInstant).toEpochMilli()
        }
        if (samples.isEmpty()) return
        (application as WearApplication).container.hrAlertRelay.onHeartRateSamples(samples)
    }
}
