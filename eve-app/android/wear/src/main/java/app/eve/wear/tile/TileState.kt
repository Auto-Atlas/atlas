package app.eve.wear.tile

/**
 * The honest, exhaustive state a Status surface (Tile + complication) can render, reduced by
 * [WearStatusReader] from the two retained snapshots the phone writes. This is a PURE model — no
 * Compose, no ProtoLayout, no GMS — so every branch (including the null-snapshot cases) unit-tests
 * on the JVM.
 *
 * House rule (no silent fallbacks): [NeverSynced] is a first-class state precisely so a fresh /
 * never-paired watch says "Waiting for phone" instead of showing a fabricated fresh `0`.
 */
sealed interface TileState {

    /**
     * No snapshot has EVER arrived (fresh install / never-paired). The surface must say so honestly
     * — it must NOT render `0 pending` as if that were a fresh reading from the server.
     */
    data object NeverSynced : TileState

    /**
     * A snapshot exists but the phone could not reach the EVE server (`serverReachable = false`).
     * [detail] is the phone's real "which leg is down" reason; [pendingCountFromStale] is a
     * last-known count ONLY when the stale snapshot actually carries one (never a fake fresh 0);
     * [ageMs] is how old the stale snapshot is.
     */
    data class ServerDown(
        val detail: String?,
        val pendingCountFromStale: Int?,
        val ageMs: Long,
    ) : TileState

    /**
     * The phone reached the server (`serverReachable = true`). [pendingCount] is the live count of
     * pending approvals; [desktopOnline] is the EVE desktop/brain presence; [ageMs] is snapshot age.
     */
    data class Live(
        val pendingCount: Int,
        val desktopOnline: Boolean,
        val ageMs: Long,
    ) : TileState
}
