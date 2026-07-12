package app.eve.wear.tile

/**
 * PURE copy for the Status Tile — every user-visible string, so pluralization / truncation /
 * freshness bucketing are unit-tested without rendering a single ProtoLayout element ([EveTileService]
 * is then a thin builder over these). Honest by construction: no state ever prints a fabricated 0,
 * and the server-down detail is shown (truncated), never swallowed.
 */
object TileCopy {

    /** The big numeral for a [TileState.Live]. */
    fun pendingCountText(count: Int): String = count.toString()

    /** Label under the numeral — singular exactly at 1, plural otherwise (incl. an honest 0). */
    fun pendingLabel(count: Int): String = if (count == 1) "pending approval" else "pending approvals"

    /** EVE desktop/brain presence line for [TileState.Live]. */
    fun desktopLine(online: Boolean): String = if (online) "EVE desktop online" else "EVE desktop offline"

    /** Freshness footer, e.g. "updated 12s ago". */
    fun freshness(ageMs: Long): String = "updated ${relativeAge(ageMs)}"

    /** [TileState.ServerDown] headline. */
    fun serverDownHeadline(): String = "Phone can't reach EVE"

    /** The phone's real "which leg is down" detail, truncated sanely (never hidden). Null stays null. */
    fun serverDownDetail(detail: String?): String? = detail?.let { truncate(it, MAX_DETAIL) }

    /** "As of" footer for a stale server-down snapshot. */
    fun serverDownAge(ageMs: Long): String = "as of ${relativeAge(ageMs)}"

    /** Last-known count line, or null when the stale snapshot carries no count (never a fake 0). */
    fun staleLine(count: Int?): String? = count?.let {
        val noun = if (it == 1) "pending approval" else "pending approvals"
        "last known: $it $noun"
    }

    /** The one honest [TileState.NeverSynced] line. */
    fun neverSynced(): String = "Waiting for phone"

    /**
     * Coarse, dependency-free "how long ago" from an already-computed age delta (ms). Mirrors
     * ui/ApprovalFormatting.relativeAge's buckets; kept here because [TileState] hands us a delta,
     * not (fetchedAt, now).
     */
    fun relativeAge(ageMs: Long): String {
        val deltaS = (ageMs / 1000L).coerceAtLeast(0)
        return when {
            deltaS < 60 -> "${deltaS}s ago"
            deltaS < 3600 -> "${deltaS / 60}m ago"
            deltaS < 86_400 -> "${deltaS / 3600}h ago"
            else -> "${deltaS / 86_400}d ago"
        }
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private const val MAX_DETAIL = 80
}
