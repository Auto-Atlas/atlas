package app.eve.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * First Compose UI test — proves the Robolectric + ui-test-junit4 harness in the JVM unit gate.
 * Smallest plain state+callback test (compose-ui-testing-patterns): EveButton renders text, fires
 * its click callback when enabled, and exposes no click action when disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EveButtonComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun shows_text() {
        rule.setContent {
            EveTheme {
                EveButton(text = "Approve", onClick = {})
            }
        }

        rule.onNodeWithText("Approve").assertIsDisplayed()
    }

    @Test
    fun click_fires_callback() {
        var clicked = false
        rule.setContent {
            EveTheme {
                EveButton(text = "Approve", onClick = { clicked = true })
            }
        }

        rule.onNodeWithText("Approve").assertHasClickAction()
        rule.onNodeWithText("Approve").performClick()

        assertTrue("onClick should fire for an enabled button", clicked)
    }

    @Test
    fun disabled_button_does_not_fire() {
        // A disabled EveButton omits the clickable modifier entirely, so the node has no click
        // action. Assert absence directly rather than forcing a performClick that would throw.
        var clicked = false
        rule.setContent {
            EveTheme {
                EveButton(text = "Approve", onClick = { clicked = true }, enabled = false)
            }
        }

        rule.onNodeWithText("Approve").assertHasNoClickAction()
        assertFalse("onClick must not fire for a disabled button", clicked)
    }
}
