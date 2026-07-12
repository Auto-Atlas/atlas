package app.eve.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.eve.ui.theme.EveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for StatusTile — a plain state composable (compose-ui-testing-patterns: smallest
 * state-driven test; assert text/semantics, not pixels). The tile shows a large value verbatim and
 * the label uppercased; the icon + status glow-dot are optional decoration that must not crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusTileComponentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun renders_value_and_uppercased_label() {
        rule.setContent {
            EveTheme {
                StatusTile(label = "Sidecar", value = "Up")
            }
        }

        // Value is shown verbatim; the label is uppercased by the composable.
        rule.onNodeWithText("Up").assertIsDisplayed()
        rule.onNodeWithText("SIDECAR").assertIsDisplayed()
    }

    @Test
    fun renders_with_icon_and_status_without_crashing() {
        rule.setContent {
            EveTheme {
                StatusTile(
                    label = "Sidecar",
                    value = "Up",
                    icon = Icons.Filled.Cloud,
                    status = TileStatus.Ok,
                )
            }
        }

        // The icon (contentDescription = null) and status glow-dot are decoration; the value still
        // renders alongside them.
        rule.onNodeWithText("Up").assertIsDisplayed()
    }
}
