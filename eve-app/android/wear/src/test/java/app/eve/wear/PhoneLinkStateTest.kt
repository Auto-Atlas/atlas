package app.eve.wear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure JVM guard on the phone-link reducer — all four honest states, no Android/Play-Services
 * runtime. This is the contract the real NodeClient query is mapped through in MainActivity.
 */
class PhoneLinkStateTest {

    @Test
    fun null_result_is_checking() {
        assertEquals(PhoneLinkState.Checking, phoneLinkStateFrom(null))
    }

    @Test
    fun empty_success_is_not_reachable() {
        assertEquals(PhoneLinkState.NotReachable, phoneLinkStateFrom(Result.success(emptyList())))
    }

    @Test
    fun non_empty_success_is_connected_with_count() {
        assertEquals(
            PhoneLinkState.Connected(nodeCount = 2),
            phoneLinkStateFrom(Result.success(listOf("Pixel Watch host", "companion"))),
        )
    }

    @Test
    fun failure_surfaces_the_real_message() {
        val state = phoneLinkStateFrom(Result.failure(IllegalStateException("Play services unavailable")))
        assertEquals(PhoneLinkState.Failed("Play services unavailable"), state)
    }

    @Test
    fun failure_with_blank_message_falls_back_to_a_readable_reason() {
        // A GMS exception with no message must not render an empty "failed: " line.
        val state = phoneLinkStateFrom(Result.failure(RuntimeException()))
        assertEquals(PhoneLinkState.Failed("Play services unavailable"), state)
    }
}
