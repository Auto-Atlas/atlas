package app.eve.wear.ui

import app.eve.wear.approvals.TestApprovals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure guards on the row display helpers — money is always from frozen args, never the summary. */
class ApprovalFormattingTest {

    @Test
    fun invoice_title_is_the_computed_dollar_total() {
        // quantity 2 * rate 600 = $1,200 (whole dollars drop the cents).
        assertEquals("$1,200 invoice", ApprovalFormatting.title(TestApprovals.invoice("a1")))
    }

    @Test
    fun channel_title_names_the_channel() {
        assertEquals("Message to telegram", ApprovalFormatting.title(TestApprovals.channel("c1")))
    }

    @Test
    fun invoice_amount_label_is_the_total_channel_has_none() {
        assertEquals("$1,200", ApprovalFormatting.amountLabel(TestApprovals.invoice("a1")))
        assertNull(ApprovalFormatting.amountLabel(TestApprovals.channel("c1")))
    }

    @Test
    fun money_drops_cents_on_whole_dollars_keeps_them_otherwise() {
        assertEquals("$1,200", ApprovalFormatting.money(1200.0))
        assertEquals("$1,234.56", ApprovalFormatting.money(1234.56))
    }

    @Test
    fun requester_line_reads_naturally() {
        assertEquals("Requested by Jamie", ApprovalFormatting.requesterLine(TestApprovals.invoice("a1")))
    }

    @Test
    fun relative_age_is_honest_across_ranges() {
        assertEquals("30s ago", ApprovalFormatting.relativeAge(0, 30_000))
        assertEquals("5m ago", ApprovalFormatting.relativeAge(0, 5 * 60_000))
        assertEquals("2h ago", ApprovalFormatting.relativeAge(0, 2 * 3_600_000))
    }
}
