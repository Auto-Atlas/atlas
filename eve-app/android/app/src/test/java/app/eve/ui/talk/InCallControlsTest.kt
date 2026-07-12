package app.eve.ui.talk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.eve.ui.theme.EveTheme
import app.eve.voice.VoiceControls
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * In-call mic-mute / speakerphone controls (compose-ui-testing-patterns: assert semantics +
 * callbacks). [InCallControls] is internal so it can be driven directly from the JVM unit gate.
 * Each round button carries its action in its contentDescription, which flips with state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InCallControlsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun muted_state_shows_unmute_affordance_and_tap_fires_toggle() {
        var toggledMute = false
        rule.setContent {
            EveTheme {
                InCallControls(
                    controls = VoiceControls(micMuted = true, speakerphoneOn = true),
                    onToggleMute = { toggledMute = true },
                    onToggleSpeakerphone = {},
                    onHangUp = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Mic silenced locally. Tap to let EVE hear you again.", useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithContentDescription("Mic silenced locally. Tap to let EVE hear you again.", useUnmergedTree = true)
            .performClick()

        assertTrue("Tapping the mute control must invoke onToggleMute", toggledMute)
    }

    @Test
    fun tapping_speaker_control_fires_toggle() {
        var toggledSpeaker = false
        rule.setContent {
            EveTheme {
                InCallControls(
                    controls = VoiceControls(micMuted = false, speakerphoneOn = true),
                    onToggleMute = {},
                    onToggleSpeakerphone = { toggledSpeaker = true },
                    onHangUp = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Loudspeaker on. Tap for earpiece.", useUnmergedTree = true)
            .performClick()

        assertTrue("Tapping the speaker control must invoke onToggleSpeakerphone", toggledSpeaker)
    }

    @Test
    fun mute_content_description_flips_with_state() {
        // micMuted = false → "Mic on. Tap to silence your mic locally." (the unmuted affordance shown).
        rule.setContent {
            EveTheme {
                InCallControls(
                    controls = VoiceControls(micMuted = false, speakerphoneOn = true),
                    onToggleMute = {},
                    onToggleSpeakerphone = {},
                    onHangUp = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Mic on. Tap to silence your mic locally.", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
