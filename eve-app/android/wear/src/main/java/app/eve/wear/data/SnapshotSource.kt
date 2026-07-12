package app.eve.wear.data

import app.eve.data.wear.ApprovalsSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Seam over the Wearable Data Layer's retained [ApprovalsSnapshot] the phone writes. Fakeable in
 * tests (manual DI, no mocking library — matches :app / the wear scaffold). The GMS impl reads the
 * retained DataItem the phone put at [app.eve.data.wear.WearLink.PATH_APPROVALS_SNAPSHOT].
 */
interface SnapshotSource {
    /**
     * The live stream of approval snapshots: the current retained value (if any) FIRST, then a new
     * emission every time the phone writes a fresh snapshot. Push-based (DataClient listener) — no
     * polling. The listener MUST live only while this flow is collected (battery honesty).
     *
     * A snapshot the watch cannot DECODE is never silently dropped: the impl emits a
     * [ApprovalsSnapshot] with `serverReachable=false` and an explicit decode-failure
     * `errorDetail`, so a corrupt payload surfaces LOUDLY as an on-screen error, not an empty list.
     */
    fun snapshots(): Flow<ApprovalsSnapshot>

    /** One-shot read of the current retained snapshot, or null if the phone has never written one. */
    suspend fun current(): ApprovalsSnapshot?
}
