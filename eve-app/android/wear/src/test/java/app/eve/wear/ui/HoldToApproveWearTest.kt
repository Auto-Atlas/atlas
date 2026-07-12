package app.eve.wear.ui

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Money-safety regression guard for [HoldToApproveWear] — the watch port of the phone's
 * HoldToApproveButton contract. Same deterministic technique as the phone test
 * (compose-ui-testing-patterns): freeze the Compose clock, drive synthetic touch, assert the
 * callback — never real pointer timing.
 *
 * THE contract: an instant tap must NOT approve; only a completed 520ms press-and-hold fires
 * onApprove — and reducedMotion must NOT shorten that gate (the exact bug that once collapsed the
 * money gate to tap-to-approve).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldToApproveWearTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun renders_its_label() {
        rule.setContent {
            HoldToApproveWear(label = "Hold to approve", onApprove = {}, reducedMotion = true)
        }
        rule.onNodeWithText("Hold to approve").assertIsDisplayed()
    }

    @Test
    fun exposes_no_click_action_only_a_hold_gesture() {
        // Even enabled, the control never installs a click action — it fires only from a completed
        // press-and-hold. No tap affordance an accidental click could invoke.
        rule.setContent {
            HoldToApproveWear(label = "Hold to approve", onApprove = {}, reducedMotion = false)
        }
        rule.onNodeWithTag("holdApproveWear").assertHasNoClickAction()
    }

    @Test
    fun reduced_motion_requires_full_hold_before_approving() {
        rule.mainClock.autoAdvance = false
        var approved = false
        rule.setContent {
            HoldToApproveWear(
                label = "Hold to approve",
                onApprove = { approved = true },
                reducedMotion = true,
                holdDurationMs = HOLD_MS,
            )
        }

        rule.onNodeWithTag("holdApproveWear").performTouchInput { down(center) }

        // Half the hold: even under reducedMotion the gate must not fire yet.
        rule.mainClock.advanceTimeBy(HOLD_MS / 2L)
        assertFalse("Half-hold must NOT approve, even with reducedMotion", approved)

        // Past the full hold threshold: the gate completes and fires exactly once.
        rule.mainClock.advanceTimeBy(HOLD_MS.toLong())
        assertTrue("Full continuous hold must approve", approved)
    }

    @Test
    fun releasing_before_full_hold_cancels_without_approving() {
        rule.mainClock.autoAdvance = false
        var approved = false
        rule.setContent {
            HoldToApproveWear(
                label = "Hold to approve",
                onApprove = { approved = true },
                reducedMotion = true,
                holdDurationMs = HOLD_MS,
            )
        }

        rule.onNodeWithTag("holdApproveWear").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_MS / 2L)
        rule.onNodeWithTag("holdApproveWear").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(HOLD_MS.toLong())

        assertFalse("Releasing before the full hold must never approve", approved)
    }

    private companion object {
        const val HOLD_MS = 520
    }
}
