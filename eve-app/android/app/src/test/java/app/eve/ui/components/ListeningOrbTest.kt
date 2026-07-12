package app.eve.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.eve.ui.theme.EveTheme
import app.eve.voice.VoiceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for the ListeningOrb (compose-ui-testing-patterns: smallest plain state-driven
 * tests; assert semantics, not pixels). In voice mode the orb's [orbContentDescription] is the ONLY
 * a11y signal (design rule: never motion/color alone), so each VoiceState's spoken label is the
 * thing worth regression-testing. reducedMotion = true kills the infiniteRepeatable animations so
 * the tree is deterministic. Robolectric JVM harness mirrors EveButtonComposeTest exactly.
 *
 * createComposeRule allows setContent only once per test → one @Test per representative state.
 * The orb merges semantics (it carries a liveRegion), so onNodeWithContentDescription reads the
 * unmerged tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningOrbTest {

    @get:Rule
    val rule = createComposeRule()

    private fun assertOrbAnnounces(state: VoiceState) {
        rule.setContent {
            EveTheme {
                ListeningOrb(state = state, reducedMotion = true)
            }
        }
        rule.onNodeWithContentDescription(orbContentDescription(state), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun idle_announces_tap_to_talk() = assertOrbAnnounces(VoiceState.Idle)

    @Test
    fun your_turn_announces_listening() = assertOrbAnnounces(VoiceState.YourTurn)

    @Test
    fun thinking_announces_thinking() = assertOrbAnnounces(VoiceState.Thinking)

    @Test
    fun speaking_announces_speaking() = assertOrbAnnounces(VoiceState.Speaking)

    @Test
    fun no_audio_announces_honest_no_audio() = assertOrbAnnounces(VoiceState.NoAudio)

    @Test
    fun hearing_announces_hearing_you() = assertOrbAnnounces(VoiceState.Hearing(0.5f))

    @Test
    fun error_interpolates_its_message_into_the_description() {
        val state = VoiceState.Error("boom")
        rule.setContent {
            EveTheme {
                ListeningOrb(state = state, reducedMotion = true)
            }
        }
        // The cause-specific message must be spoken, not swallowed.
        rule.onNodeWithContentDescription("Connection problem: boom", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
