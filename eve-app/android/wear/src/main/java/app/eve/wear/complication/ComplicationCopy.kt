package app.eve.wear.complication

import app.eve.ASSISTANT_NAME
import app.eve.wear.tile.TileState

/**
 * PURE mapping from [TileState] to what the pending-approvals complication renders, for both
 * supported types (SHORT_TEXT and RANGED_VALUE). Kept free of the watchface data classes so all
 * branches — including the honest NeverSynced / ServerDown fallbacks — unit-test on the JVM;
 * [PendingApprovalsComplicationService] is a thin assembler over these values.
 *
 * Honesty contract: only a [TileState.Live] shows a number. NeverSynced and ServerDown show an
 * em dash ("—") with a content description that says WHY (a screen reader must never announce a
 * fabricated "0 pending").
 */
object ComplicationCopy {

    /** SHORT_TEXT title + the icon/monochrome-image caption. */
    const val TITLE = "$ASSISTANT_NAME"

    /** The em dash shown when there is no live count to display. */
    const val NO_VALUE = "—"

    /** SHORT_TEXT / RANGED_VALUE main text: the live count, or "—" for the honest fallbacks. */
    fun shortText(state: TileState): String = when (state) {
        is TileState.Live -> state.pendingCount.toString()
        is TileState.ServerDown, TileState.NeverSynced -> NO_VALUE
    }

    /** Spoken/description string — always explains the state honestly. */
    fun contentDescription(state: TileState): String = when (state) {
        is TileState.Live -> "$ASSISTANT_NAME: ${pendingPhrase(state.pendingCount)}"
        TileState.NeverSynced -> "$ASSISTANT_NAME: waiting for phone"
        is TileState.ServerDown -> "$ASSISTANT_NAME: server unreachable"
    }

    /** RANGED_VALUE value = the live count (>=0), or 0 for the fallbacks (paired with the "—" text). */
    fun rangedValue(state: TileState): Float = when (state) {
        is TileState.Live -> state.pendingCount.coerceAtLeast(0).toFloat()
        is TileState.ServerDown, TileState.NeverSynced -> 0f
    }

    /** RANGED_VALUE floor — always 0 (zero pending is the empty end of the arc). */
    fun rangedMin(): Float = 0f

    /**
     * RANGED_VALUE ceiling = max(count, 5). The arc fills toward a soft ceiling of 5 pending so a
     * typical backlog reads as a partial arc; a larger backlog simply tops the arc out (count == max)
     * rather than compressing every realistic value into a tiny sliver. Fallbacks use the same 5.
     */
    fun rangedMax(state: TileState): Float = when (state) {
        is TileState.Live -> maxOf(state.pendingCount, DEFAULT_MAX).toFloat()
        is TileState.ServerDown, TileState.NeverSynced -> DEFAULT_MAX.toFloat()
    }

    private fun pendingPhrase(count: Int): String = when (count) {
        0 -> "no pending approvals"
        1 -> "1 pending approval"
        else -> "$count pending approvals"
    }

    private const val DEFAULT_MAX = 5
}
