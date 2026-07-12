package app.eve.wear.tile

import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.StatusSnapshot

/**
 * PURE reducer: the two retained snapshots the phone writes (either null = never written) -> a
 * [TileState] the Tile and the complication both render. No GMS/Compose/ProtoLayout here, so every
 * branch is JVM-unit-tested (see WearStatusReaderTest).
 *
 * Design contract — the pending COUNT comes from the APPROVALS snapshot's `approvals.size`, NOT from
 * [app.eve.data.models.SystemStatus.pendingApprovals]. The approvals snapshot is the exact same
 * source the on-watch approvals LIST renders, so the Tile's number can never disagree with the list
 * the user opens by tapping it. SystemStatus supplies only `desktopOnline` (and is a last-resort
 * count fallback if the approvals snapshot was somehow never written but the status one was).
 */
object WearStatusReader {

    fun reduce(
        approvals: ApprovalsSnapshot?,
        status: StatusSnapshot?,
        nowMs: Long,
    ): TileState {
        // Nothing has ever arrived from the phone — honest "waiting", never a fabricated fresh 0.
        if (approvals == null && status == null) return TileState.NeverSynced

        // The approvals snapshot is authoritative for reachability + count + freshness (it is the
        // list source). Fall back to the status snapshot only when approvals was never written.
        val reachable = approvals?.serverReachable ?: status!!.serverReachable
        val ageBaseMs = approvals?.fetchedAtEpochMs ?: status!!.fetchedAtEpochMs
        val ageMs = (nowMs - ageBaseMs).coerceAtLeast(0)

        if (!reachable) {
            val detail = approvals?.errorDetail ?: status?.errorDetail
            // A server-down snapshot carries no fresh list (the phone writes an empty list on an
            // unreachable server). Surface a stale count ONLY if one genuinely exists — never turn
            // an empty down-snapshot into a fake "0 pending".
            val staleCount = approvals?.approvals?.size?.takeIf { it > 0 }
                ?: status?.status?.pendingApprovals?.takeIf { it > 0 }
            return TileState.ServerDown(detail = detail, pendingCountFromStale = staleCount, ageMs = ageMs)
        }

        // Live. Count = approvals.size (the list source). If approvals was somehow never written but
        // status is live, fall back to SystemStatus.pendingApprovals so we still show a real number.
        val pendingCount = approvals?.approvals?.size ?: status?.status?.pendingApprovals ?: 0
        val desktopOnline = status?.status?.desktopOnline ?: false
        return TileState.Live(pendingCount = pendingCount, desktopOnline = desktopOnline, ageMs = ageMs)
    }
}
