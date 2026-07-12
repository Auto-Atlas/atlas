package app.eve.wear.complication

import app.eve.ASSISTANT_NAME
import app.eve.wear.tile.TileState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure guards on the complication mapping — SHORT_TEXT + RANGED_VALUE for every honest branch. */
class ComplicationCopyTest {

    private fun live(count: Int) = TileState.Live(pendingCount = count, desktopOnline = true, ageMs = 0L)
    private val down = TileState.ServerDown(detail = "cannot reach $ASSISTANT_NAME", pendingCountFromStale = null, ageMs = 0L)
    private val never = TileState.NeverSynced

    @Test
    fun short_text_shows_the_count_only_when_live() {
        assertEquals("3", ComplicationCopy.shortText(live(3)))
        assertEquals("0", ComplicationCopy.shortText(live(0)))
        assertEquals(ComplicationCopy.NO_VALUE, ComplicationCopy.shortText(down))
        assertEquals(ComplicationCopy.NO_VALUE, ComplicationCopy.shortText(never))
    }

    @Test
    fun title_is_eve() {
        assertEquals("$ASSISTANT_NAME", ComplicationCopy.TITLE)
    }

    @Test
    fun content_description_explains_each_state_honestly() {
        assertEquals("$ASSISTANT_NAME: no pending approvals", ComplicationCopy.contentDescription(live(0)))
        assertEquals("$ASSISTANT_NAME: 1 pending approval", ComplicationCopy.contentDescription(live(1)))
        assertEquals("$ASSISTANT_NAME: 3 pending approvals", ComplicationCopy.contentDescription(live(3)))
        assertEquals("$ASSISTANT_NAME: waiting for phone", ComplicationCopy.contentDescription(never))
        assertEquals("$ASSISTANT_NAME: server unreachable", ComplicationCopy.contentDescription(down))
    }

    @Test
    fun ranged_value_is_the_count_or_zero_for_fallbacks() {
        assertEquals(3f, ComplicationCopy.rangedValue(live(3)))
        assertEquals(0f, ComplicationCopy.rangedValue(live(0)))
        assertEquals(0f, ComplicationCopy.rangedValue(down))
        assertEquals(0f, ComplicationCopy.rangedValue(never))
    }

    @Test
    fun ranged_min_is_zero() {
        assertEquals(0f, ComplicationCopy.rangedMin())
    }

    @Test
    fun ranged_max_is_a_soft_ceiling_of_five_or_the_count_when_larger() {
        assertEquals(5f, ComplicationCopy.rangedMax(live(3)))
        assertEquals(5f, ComplicationCopy.rangedMax(live(5)))
        assertEquals(8f, ComplicationCopy.rangedMax(live(8)))
        assertEquals(5f, ComplicationCopy.rangedMax(down))
        assertEquals(5f, ComplicationCopy.rangedMax(never))
    }
}
