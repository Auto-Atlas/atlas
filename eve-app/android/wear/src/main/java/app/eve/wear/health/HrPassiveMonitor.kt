package app.eve.wear.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers/unregisters the passive HEART_RATE_BPM stream behind [HrAlertService] (Health v2).
 * Registration does NOT survive a reboot (Health Services rule), so [HrBootReceiver] re-runs
 * [ensureRegistered] after BOOT_COMPLETED; [app.eve.wear.WearApplication] re-runs it on app start.
 *
 * Every refusal is a NAMED [MonitorOutcome] — no permission, no capability, register failure —
 * never a silent no-op the owner mistakes for protection.
 */
class HrPassiveMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val client = HealthServices.getClient(appContext).passiveMonitoringClient

    sealed interface MonitorOutcome {
        data object Registered : MonitorOutcome
        data object NoPermission : MonitorOutcome
        data object HeartRateNotSupported : MonitorOutcome
        data class Failed(val reason: String) : MonitorOutcome
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

    /** Register the passive HR stream (idempotent — Health Services replaces a prior config). */
    suspend fun ensureRegistered(): MonitorOutcome {
        if (!hasPermission()) {
            Log.w(TAG, "HR alerts requested but BODY_SENSORS is not granted")
            return MonitorOutcome.NoPermission
        }
        return try {
            val capabilities = awaitFuture(client.getCapabilitiesAsync())
            if (DataType.HEART_RATE_BPM !in capabilities.supportedDataTypesPassiveMonitoring) {
                Log.e(TAG, "This watch does not support passive HEART_RATE_BPM")
                return MonitorOutcome.HeartRateNotSupported
            }
            val config = PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .build()
            awaitFuture(client.setPassiveListenerServiceAsync(HrAlertService::class.java, config))
            Log.i(TAG, "Passive HR stream registered -> HrAlertService")
            MonitorOutcome.Registered
        } catch (t: Throwable) {
            Log.e(TAG, "Passive HR registration failed: ${t.message}", t)
            MonitorOutcome.Failed(t.message ?: t::class.simpleName ?: "registration failed")
        }
    }

    /** Stop the stream (owner turned heart alerts off). Failure is logged, never silent. */
    suspend fun unregister() {
        try {
            awaitFuture(client.clearPassiveListenerServiceAsync())
            Log.i(TAG, "Passive HR stream unregistered")
        } catch (t: Throwable) {
            Log.e(TAG, "Passive HR unregister failed: ${t.message}", t)
        }
    }

    private suspend fun <T> awaitFuture(future: ListenableFuture<T>): T =
        suspendCancellableCoroutine { cont ->
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(appContext),
            )
            cont.invokeOnCancellation { future.cancel(false) }
        }

    private companion object {
        const val TAG = "HrPassiveMonitor"
    }
}
