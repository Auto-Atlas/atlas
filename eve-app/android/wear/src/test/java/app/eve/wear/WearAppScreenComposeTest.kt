package app.eve.wear

import app.eve.ASSISTANT_NAME
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric render of [WearAppScreen] for each [PhoneLinkState], asserting the Atlas wordmark and
 * the exact status line. Mirrors the phone app's compose-test convention
 * (RobolectricTestRunner + @Config(sdk=34) + NATIVE graphics + createComposeRule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearAppScreenComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun always_shows_the_eve_wordmark() {
        rule.setContent { WearAppScreen(PhoneLinkState.Checking) }
        rule.onNodeWithText("$ASSISTANT_NAME").assertIsDisplayed()
    }

    @Test
    fun checking_state_shows_checking_line() {
        rule.setContent { WearAppScreen(PhoneLinkState.Checking) }
        rule.onNodeWithText("Checking phone link…").assertIsDisplayed()
    }

    @Test
    fun connected_state_shows_connected_line() {
        rule.setContent { WearAppScreen(PhoneLinkState.Connected(nodeCount = 1)) }
        rule.onNodeWithText("Phone: connected").assertIsDisplayed()
    }

    @Test
    fun not_reachable_state_shows_not_reachable_line() {
        rule.setContent { WearAppScreen(PhoneLinkState.NotReachable) }
        rule.onNodeWithText("Phone: not reachable").assertIsDisplayed()
    }

    @Test
    fun failed_state_shows_the_real_reason() {
        rule.setContent { WearAppScreen(PhoneLinkState.Failed("Play services unavailable")) }
        rule.onNodeWithText("Phone link failed: Play services unavailable").assertIsDisplayed()
    }
}
