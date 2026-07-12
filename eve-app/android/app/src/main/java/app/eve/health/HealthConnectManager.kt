package app.eve.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * Availability + permission edge for Health Connect. ALL androidx.health.connect.* types for
 * availability/permissions live here (the record READS live in [HealthConnectReader]); nothing above
 * this class ever imports the SDK. Not unit-tested on the JVM — it is a thin wrapper over the SDK's
 * own static/edge calls (getSdkStatus / getOrCreate / getGrantedPermissions / the request contract),
 * exactly the kind of Android-runtime seam the app leaves to instrumentation, like the camera code.
 * The testable decisions it feeds (which UI state, which upload outcome) are pure and covered.
 */
class HealthConnectManager(private val appContext: Context) {

    /** Maps HealthConnectClient.getSdkStatus() to the honest three-state the UI renders. */
    fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthAvailability.PROVIDER_UPDATE_REQUIRED
            else -> HealthAvailability.NOT_INSTALLED
        }

    /** The client, or null when Health Connect isn't available (getOrCreate would throw otherwise). */
    fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthAvailability.AVAILABLE) {
            HealthConnectClient.getOrCreate(appContext)
        } else {
            null
        }

    /** True only when EVERY read permission in [READ_PERMISSIONS] is granted (all-or-nothing honesty). */
    suspend fun hasAllPermissions(): Boolean {
        val client = clientOrNull() ?: return false
        // 1.1.0: getGrantedPermissions() takes NO argument and returns Set<String>.
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(READ_PERMISSIONS)
    }

    /** The set of granted read permissions right now (empty when HC is unavailable). */
    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return client.permissionController.getGrantedPermissions()
    }

    /**
     * The ActivityResultContract the Health card launches to request the reads. Requires Health
     * Connect to be available; returns null otherwise so the caller shows the "unavailable" state
     * instead of a launcher that would crash.
     */
    fun requestPermissionsContract(): ActivityResultContract<Set<String>, Set<String>>? {
        // Gate on availability (a launcher is useless without a provider), but the contract factory is
        // static on PermissionController's companion — NOT an instance method on permissionController.
        if (availability() != HealthAvailability.AVAILABLE) return null
        return PermissionController.createRequestPermissionResultContract()
    }

    companion object {
        /**
         * The six read permissions (spec §Manifest). Built from the record KClasses so the strings
         * (android.permission.health.READ_*) can never drift from the manifest's <uses-permission>.
         */
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )
    }
}
