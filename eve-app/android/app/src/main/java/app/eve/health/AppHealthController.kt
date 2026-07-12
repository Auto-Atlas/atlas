package app.eve.health

import android.content.Context

/**
 * Production [HealthController]: composes the three real pieces — [HealthConnectManager] (availability
 * + permissions), [HealthUploadStore] (last-sync time), and [HealthUploadWorker] (scheduling) — behind
 * the small seam the Status ViewModel talks to. Holds no state of its own; every call delegates.
 */
class AppHealthController(
    private val appContext: Context,
    private val manager: HealthConnectManager,
    private val store: HealthUploadStore,
) : HealthController {

    override fun availability(): HealthAvailability = manager.availability()

    override suspend fun hasPermissions(): Boolean = manager.hasAllPermissions()

    override suspend fun lastUploadAt(): Long? = store.lastUploadAt()

    override fun syncNow() {
        // Fire the immediate upload AND make sure the periodic worker is running (the user has clearly
        // opted in by tapping sync), so recurring syncs continue without a separate step.
        HealthUploadWorker.syncNow(appContext)
        HealthUploadWorker.schedulePeriodic(appContext)
    }

    override fun ensurePeriodicScheduled() {
        HealthUploadWorker.schedulePeriodic(appContext)
    }
}
