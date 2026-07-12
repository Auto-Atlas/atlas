package app.eve.wear.livevoice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric render of [WearLiveVoiceScreen] pinning the 2026-07-11 wrist UX: the orb is the whole
 * screen on the happy path (no labels, no buttons, no status text), while the honesty spine keeps
 * text on the states the owner must act on (no door / a named error). Mirrors the module's
 * compose-test convention (RobolectricTestRunner + @Config(sdk=34) + NATIVE + composeRule).
 *
 * Idle is deliberately not rendered here — it triggers the auto-start permission effect, which is
 * exercised on hardware and in the ViewModel's pure tests, not in a Robolectric render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearLiveVoiceScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(
        state: VoiceState,
        controls: VoiceControls = VoiceControls(),
        onOrbTap: () -> Unit = {},
        onOrbLongPress: () -> Unit = {},
    ) {
        rule.setContent {
            WearLiveVoiceScreen(
                state = state,
                controls = controls,
                transcript = emptyList(),
                reducedMotion = true,
                onOrbTap = onOrbTap,
                onOrbLongPress = onOrbLongPress,
                onScreenEntry = {},
                onAutoStart = {},
                onMicPermissionDenied = {},
            )
        }
    }

    @Test
    fun happy_path_is_the_orb_alone_no_label_no_buttons() {
        render(VoiceState.YourTurn)
        rule.onNodeWithTag("liveOrb").assertIsDisplayed()
        // The state phrase is the orb's spoken description only — never visible chrome on the happy path.
        rule.onNodeWithText("Go ahead, I'm listening").assertDoesNotExist()
        rule.onNodeWithText("End").assertDoesNotExist()
        rule.onNodeWithText("Mute mic").assertDoesNotExist()
    }

    @Test
    fun speaking_is_the_orb_alone() {
        render(VoiceState.Speaking)
        rule.onNodeWithTag("liveOrb").assertIsDisplayed()
        rule.onNodeWithText("EVE is speaking").assertDoesNotExist()
    }

    @Test
    fun not_configured_keeps_its_honest_text() {
        render(VoiceState.NotConfigured)
        rule.onNodeWithText(WearLiveVoiceCopy.NOT_CONFIGURED).assertIsDisplayed()
    }

    @Test
    fun a_named_error_keeps_its_exact_copy() {
        render(VoiceState.Error(WearLiveVoiceCopy.CONNECTION_LOST))
        rule.onNodeWithText(WearLiveVoiceCopy.CONNECTION_LOST).assertIsDisplayed()
    }

    @Test
    fun no_audio_names_the_problem_so_the_owner_knows() {
        render(VoiceState.NoAudio)
        rule.onNodeWithText("Connected, but no audio is getting through").assertIsDisplayed()
    }

    // ---- long-press = END, quick tap = talk/mute — same frozen-clock technique as
    // HoldToApproveWearTest (drive synthetic touch, never real pointer timing) ----------------

    @Test
    fun full_hold_on_the_orb_fires_the_long_press_and_never_the_tap() {
        rule.mainClock.autoAdvance = false
        var taps = 0
        var longPresses = 0
        render(VoiceState.YourTurn, onOrbTap = { taps++ }, onOrbLongPress = { longPresses++ })

        rule.onNodeWithTag("liveOrb").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS / 2)
        assertEquals("Half the hold must not end the call", 0, longPresses)
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS)
        assertEquals("A full continuous hold ends the call", 1, longPresses)
        rule.onNodeWithTag("liveOrb").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS)
        assertEquals("The release after a fired hold must never also tap", 0, taps)
        assertEquals(1, longPresses)
    }

    @Test
    fun quick_tap_on_the_orb_fires_the_tap_and_never_the_long_press() {
        rule.mainClock.autoAdvance = false
        var taps = 0
        var longPresses = 0
        render(VoiceState.YourTurn, onOrbTap = { taps++ }, onOrbLongPress = { longPresses++ })

        rule.onNodeWithTag("liveOrb").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(ORB_TAP_MAX_MS / 2)
        rule.onNodeWithTag("liveOrb").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS * 2)
        assertEquals("A quick tap is the talk/mute toggle", 1, taps)
        assertEquals(0, longPresses)
    }

    @Test
    fun a_cancelled_hold_fires_nothing_at_all() {
        // The disambiguation rule: a press past the tap window but released before the hold
        // threshold is a CANCELLED hold — it must not end the call AND must not mute either.
        rule.mainClock.autoAdvance = false
        var taps = 0
        var longPresses = 0
        render(VoiceState.YourTurn, onOrbTap = { taps++ }, onOrbLongPress = { longPresses++ })

        rule.onNodeWithTag("liveOrb").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500) // past ORB_TAP_MAX_MS, before ORB_HOLD_TO_END_MS
        rule.onNodeWithTag("liveOrb").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS * 2)
        assertFalse("A 500ms press-then-release is a cancelled hold, not a mute toggle", taps > 0)
        assertEquals(0, longPresses)
    }

    @Test
    fun long_press_works_while_connecting_the_deliberate_warmup_abort() {
        rule.mainClock.autoAdvance = false
        var taps = 0
        var longPresses = 0
        render(VoiceState.Connecting, onOrbTap = { taps++ }, onOrbLongPress = { longPresses++ })

        rule.onNodeWithTag("liveOrb").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS + 100)
        rule.onNodeWithTag("liveOrb").performTouchInput { up() }
        assertEquals("Long-press must abort a warm-up", 1, longPresses)
        assertEquals(0, taps)
    }

    @Test
    fun long_press_at_rest_fires_nothing_there_is_nothing_to_end() {
        rule.mainClock.autoAdvance = false
        var longPresses = 0
        render(VoiceState.NotConfigured, onOrbLongPress = { longPresses++ })

        rule.onNodeWithTag("liveOrb").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(ORB_HOLD_TO_END_MS * 2)
        rule.onNodeWithTag("liveOrb").performTouchInput { up() }
        assertEquals(0, longPresses)
    }

    // ---- the pure hold-eligibility contract (which states a long-press can end) ---------------

    @Test
    fun hold_ends_call_only_in_live_states() {
        assertTrue(holdEndsCall(VoiceState.Connecting))
        assertTrue(holdEndsCall(VoiceState.YourTurn))
        assertTrue(holdEndsCall(VoiceState.Hearing(0.4f)))
        assertTrue(holdEndsCall(VoiceState.Thinking))
        assertTrue(holdEndsCall(VoiceState.Speaking))
        assertTrue(holdEndsCall(VoiceState.Reconnecting))
        assertTrue(holdEndsCall(VoiceState.NoAudio))
        assertFalse(holdEndsCall(VoiceState.Idle))
        assertFalse(holdEndsCall(VoiceState.NotConfigured))
        assertFalse(holdEndsCall(VoiceState.Error("x")))
    }
}
