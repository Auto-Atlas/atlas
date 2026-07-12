package app.eve.wear.tile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure guards on the Tile's user-visible copy — pluralization, freshness buckets, truncation. */
class TileCopyTest {

    @Test
    fun count_text_is_the_number() {
        assertEquals("3", TileCopy.pendingCountText(3))
        assertEquals("0", TileCopy.pendingCountText(0))
    }

    @Test
    fun label_is_singular_only_at_one() {
        assertEquals("pending approvals", TileCopy.pendingLabel(0))
        assertEquals("pending approval", TileCopy.pendingLabel(1))
        assertEquals("pending approvals", TileCopy.pendingLabel(2))
    }

    @Test
    fun desktop_line_reflects_presence() {
        assertEquals("EVE desktop online", TileCopy.desktopLine(true))
        assertEquals("EVE desktop offline", TileCopy.desktopLine(false))
    }

    @Test
    fun freshness_buckets_by_magnitude() {
        assertEquals("updated 5s ago", TileCopy.freshness(5_000L))
        assertEquals("updated 2m ago", TileCopy.freshness(120_000L))
        assertEquals("updated 2h ago", TileCopy.freshness(7_200_000L))
        assertEquals("updated 2d ago", TileCopy.freshness(2L * 86_400_000L))
    }

    @Test
    fun freshness_clamps_negative_age_to_zero() {
        assertEquals("updated 0s ago", TileCopy.freshness(-5_000L))
    }

    @Test
    fun server_down_headline_is_fixed() {
        assertEquals("Phone can't reach EVE", TileCopy.serverDownHeadline())
    }

    @Test
    fun server_down_detail_passes_short_through_and_stays_null_for_null() {
        assertNull(TileCopy.serverDownDetail(null))
        assertEquals("cannot reach EVE: timeout", TileCopy.serverDownDetail("cannot reach EVE: timeout"))
    }

    @Test
    fun server_down_detail_truncates_overlong_reasons_with_an_ellipsis() {
        val long = "x".repeat(200)
        val out = TileCopy.serverDownDetail(long)!!
        assertTrue(out.length <= 80, "expected <=80 chars, got ${out.length}")
        assertTrue(out.endsWith("…"), "expected an ellipsis, got: $out")
    }

    @Test
    fun server_down_age_reads_as_of() {
        assertEquals("as of 5m ago", TileCopy.serverDownAge(300_000L))
    }

    @Test
    fun stale_line_is_null_or_pluralized() {
        assertNull(TileCopy.staleLine(null))
        assertEquals("last known: 1 pending approval", TileCopy.staleLine(1))
        assertEquals("last known: 3 pending approvals", TileCopy.staleLine(3))
    }

    @Test
    fun never_synced_is_the_honest_waiting_line() {
        assertEquals("Waiting for phone", TileCopy.neverSynced())
    }
}
