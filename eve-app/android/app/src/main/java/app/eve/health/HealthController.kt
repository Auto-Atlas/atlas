package app.eve.health

import androidx.activity.result.contract.ActivityResultContract

/**
 * Whether the on-phone Health Connect hub is usable on THIS device — the honest three-state the
 * Status "Health" row renders (never a silent "off"). Mirrors HealthConnectClient.getSdkStatus.
 */
enum class HealthAvailability {
    /** Health Connect is installed/enabled and ready. */
    AVAILABLE,

    /** No Health Connect on this device (pre-install on API < 34, or disabled). */
    NOT_INSTALLED,

    /** Present but too old — the user must update the Health Connect provider. */
    PROVIDER_UPDATE_REQUIRED,
}

/**
 * The Status screen's window onto the health feature — a tiny seam so [app.eve.ui.status.StatusViewModel]
 * stays JVM-testable with a fake (like the existing GlassesToggle). It exposes only what the row needs:
 * the availability state, whether all reads are permitted, when we last synced (null == never), and the
 * two actions (sync now / keep the periodic worker scheduled). The permission-REQUEST launcher is NOT
 * here — that needs an Android ActivityResultContract and lives in [HealthPermissionRequest], used only
 * by the composable.
 */
interface HealthController {
    fun availability(): HealthAvailability

    /** True when all six read permissions are currently granted. */
    suspend fun hasPermissions(): Boolean

    /** Epoch millis of the last SUCCESSFUL upload, or null if the phone has never synced health yet. */
    suspend fun lastUploadAt(): Long?

    /** Enqueue an immediate (expedited) one-shot upload AND make sure the periodic worker is scheduled. */
    fun syncNow()

    /** (Re)schedule the periodic 30-min worker — safe to call repeatedly (unique work, KEEP policy). */
    fun ensurePeriodicScheduled()
}

/**
 * Everything the Health card's Compose permission launcher needs, bundled so it can be passed into
 * the Status screen without leaking Health Connect types through the ViewModel. Null in tests/preview
 * (no Android runtime) — the card then still renders availability/denied state, but the "Allow" button
 * is inert. In production the container builds it from the real HealthConnectManager.
 */
class HealthPermissionRequest(
    /** The six read-permission strings to request (android.permission.health.READ_*). */
    val permissions: Set<String>,
    /** PermissionController.createRequestPermissionResultContract() — returns the granted set. */
    val contract: ActivityResultContract<Set<String>, Set<String>>,
)
