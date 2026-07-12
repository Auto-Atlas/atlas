package app.eve.ui.components

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import app.eve.ui.theme.EveTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Money-safety regression guard for [HoldToApproveButton] (compose-ui-testing-patterns: assert
 * semantics + callbacks, never pixels or raw pointer timing).
 *
 * The contract under test is the SAFETY one: an instantaneous TAP / click must NOT approve. Only a
 * completed press-and-hold (real pointer timing, deliberately NOT exercised here because it is
 * flaky) may fire onApprove. Proving "a tap is insufficient" is the valuable, reliable assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldToApproveButtonTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun renders_its_label() {
        rule.setContent {
            EveTheme {
                HoldToApproveButton(
                    label = "Hold to approve",
                    consequence = "Sends \$42 to Acme",
                    onApprove = {},
                    reducedMotion = true,
                )
            }
        }

        rule.onNodeWithText("Hold to approve").assertIsDisplayed()
    }

    @Test
    fun disabled_button_has_no_click_action_and_never_approves() {
        // When disabled the gesture pointerInput is omitted entirely, so the node exposes no click
        // action. Assert absence directly — no performClick that could throw.
        var approved = false
        rule.setContent {
            EveTheme {
                HoldToApproveButton(
                    label = "Hold to approve",
                    consequence = "Sends \$42 to Acme",
                    onApprove = { approved = true },
                    enabled = false,
                    reducedMotion = true,
                )
            }
        }

        rule.onNodeWithText("Hold to approve").assertHasNoClickAction()
        assertFalse("onApprove must never fire while disabled", approved)
    }

    @Test
    fun enabled_button_exposes_no_click_action_only_a_hold_gesture() {
        // THE money-safety guard, asserted WITHOUT flaky pointer/clock timing: even when enabled the
        // control NEVER installs a clickable/click action — it listens only via pointerInput /
        // detectTapGestures and fires onApprove solely from a completed 520ms press-and-hold. Proving
        // the node has no click action proves there is no tap affordance an accidental click handler
        // could ever invoke. (A raw performClick here is unreliable: Robolectric's virtual clock can
        // fast-forward the synthetic press through the whole hold animation, so it does not model an
        // instantaneous tap.)
        rule.setContent {
            EveTheme {
                HoldToApproveButton(
                    label = "Hold to approve",
                    consequence = "Sends \$42 to Acme",
                    onApprove = {},
                    enabled = true,
                    reducedMotion = false,
                )
            }
        }

        rule.onNodeWithText("Hold to approve").assertHasNoClickAction()
    }

    /**
     * THE regression guard for the reduced-motion safety bug, made deterministic with the Compose
     * test clock (compose-ui-testing-patterns: freeze the clock, drive synthetic touch, assert the
     * callback — never real timing). Under reducedMotion the GATE TIMING must be unchanged: a press
     * held for only half the hold duration must NOT approve, but a press held past the full duration
     * must. This is exactly the case the bug collapsed to tap-to-approve.
     */
    @Test
    fun reduced_motion_requires_full_hold_before_approving() {
        rule.mainClock.autoAdvance = false
        var approved = false
        rule.setContent {
            EveTheme {
                HoldToApproveButton(
                    label = "Hold to approve",
                    consequence = "Sends \$42 to Acme",
                    onApprove = { approved = true },
                    reducedMotion = true,
                    holdDurationMs = HOLD_MS,
                )
            }
        }

        // Press and HOLD (do not release).
        rule.onNodeWithTag("holdApprove").performTouchInput { down(center) }

        // Half the hold has elapsed: even under reducedMotion the gate must not fire yet.
        rule.mainClock.advanceTimeBy(HOLD_MS / 2L)
        assertFalse("Half-hold must NOT approve, even with reducedMotion", approved)

        // Advance past the full hold threshold: the gate completes and fires.
        rule.mainClock.advanceTimeBy(HOLD_MS.toLong())
        assertTrue("Full continuous hold must approve", approved)
    }

    @Test
    fun releasing_before_full_hold_cancels_without_approving() {
        rule.mainClock.autoAdvance = false
        var approved = false
        rule.setContent {
            EveTheme {
                HoldToApproveButton(
                    label = "Hold to approve",
                    consequence = "Sends \$42 to Acme",
                    onApprove = { approved = true },
                    reducedMotion = true,
                    holdDurationMs = HOLD_MS,
                )
            }
        }

        rule.onNodeWithTag("holdApprove").performTouchInput { down(center) }
        // Release before the hold threshold, then let time pass.
        rule.mainClock.advanceTimeBy(HOLD_MS / 2L)
        rule.onNodeWithTag("holdApprove").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(HOLD_MS.toLong())

        assertFalse("Releasing before the full hold must never approve", approved)
    }

    private companion object {
        // A known hold duration passed explicitly so the test is independent of token changes.
        const val HOLD_MS = 520
    }
}
