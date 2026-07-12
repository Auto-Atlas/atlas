package app.eve.wear.ui

import app.eve.data.models.Approval
import java.text.NumberFormat
import java.util.Locale

/**
 * Pure display helpers for a watch approval row — no Compose, so they unit-test directly. The money
 * total is ALWAYS computed from frozen args ([Approval.totalDollars]), NEVER parsed from the summary
 * (the same design contract the phone enforces).
 */
object ApprovalFormatting {

    /** Same currency format as :app ApprovalCard.money — whole dollars drop the cents. */
    fun money(dollars: Double): String =
        NumberFormat.getCurrencyInstance(Locale.US)
            .apply { maximumFractionDigits = if (dollars % 1.0 == 0.0) 0 else 2 }
            .format(dollars)

    /**
     * Short row title: an invoice reads "$1,200 invoice"; a channel message reads "Message to
     * <channel>"; anything else falls back to the summary's first line (never blank/placeholder).
     */
    fun title(approval: Approval): String {
        approval.totalDollars?.let { return "${money(it)} invoice" }
        approval.channelArgs?.let { return "Message to ${it.channel}" }
        return approval.summary.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: approval.summary
    }

    /** Trust line under the title, e.g. "Requested by Jamie". */
    fun requesterLine(approval: Approval): String = "Requested by ${approval.requester ?: "someone"}"

    /** The invoice/channel amount for the row's right-hand column, or null (no amount). */
    fun amountLabel(approval: Approval): String? = approval.totalDollars?.let { money(it) }

    /**
     * Coarse "how long ago" label for a stale list (ServerDown). Kept dependency-free (no
     * java.time formatter locale surprises) and honest: it states the age, never hides it.
     */
    fun relativeAge(fetchedAtEpochMs: Long, nowMs: Long): String {
        val deltaS = ((nowMs - fetchedAtEpochMs) / 1000L).coerceAtLeast(0)
        return when {
            deltaS < 60 -> "${deltaS}s ago"
            deltaS < 3600 -> "${deltaS / 60}m ago"
            deltaS < 86_400 -> "${deltaS / 3600}h ago"
            else -> "${deltaS / 86_400}d ago"
        }
    }
}
