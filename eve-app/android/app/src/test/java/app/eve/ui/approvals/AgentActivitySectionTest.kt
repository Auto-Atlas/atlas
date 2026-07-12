package app.eve.ui.approvals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.eve.ui.theme.EveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live Agent Activity section (live-delegation-approvals): state is visually obvious,
 * controls reflect truth (disabled Redirect carries its reason), empty is plain — never fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentActivitySectionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(
        id: String = "t1",
        state: AgentTaskState = AgentTaskState.Working,
        canRedirect: Boolean = true,
        redirectReason: String? = null,
    ) = AgentTaskCard(
        id = id, agent = "hermes", taskText = "audit the shop site",
        state = state, feed = listOf("crawling the site", "found 3 broken links"),
        canRedirect = canRedirect, redirectReason = redirectReason,
    )

    @Test
    fun working_card_shows_state_agent_task_and_live_feed() {
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(card()), streamHealthy = true,
                    onCancel = {}, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Working").assertIsDisplayed()
        compose.onNodeWithText("hermes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("audit the shop site", substring = true).assertIsDisplayed()
        compose.onNodeWithText("found 3 broken links", substring = true).assertIsDisplayed()
    }

    @Test
    fun waiting_state_is_visibly_distinct_with_the_question() {
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(card(state = AgentTaskState.WaitingOnYou).copy(question = "which env?")),
                    streamHealthy = true, onCancel = {}, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Waiting on you").assertIsDisplayed()
        compose.onNodeWithText("which env?", substring = true).assertIsDisplayed()
    }

    @Test
    fun unsupported_redirect_is_disabled_with_a_visible_reason() {
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(card(canRedirect = false,
                                        redirectReason = "hermes runs without a talk-back channel")),
                    streamHealthy = true, onCancel = {}, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Redirect").assertIsNotEnabled()
        compose.onNodeWithText("hermes runs without a talk-back channel", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun cancel_button_fires_callback() {
        var cancelled: String? = null
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(card()), streamHealthy = true,
                    onCancel = { cancelled = it }, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        kotlin.test.assertEquals("t1", cancelled)
    }

    @Test
    fun empty_section_says_so_plainly() {
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = emptyList(), streamHealthy = true,
                    onCancel = {}, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("No agents working right now").assertIsDisplayed()
    }

    @Test
    fun dropped_stream_shows_reconnecting_badge_not_a_frozen_live_feed() {
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(card()), streamHealthy = false,
                    onCancel = {}, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("RECONNECTING", substring = true).assertIsDisplayed()
    }

    @Test
    fun tapping_a_card_opens_the_full_detail_view() {
        val longFeed = (1..9).map { "step number $it happened" }
        val fullResult = "FINAL ANSWER: everything about the login bug, explained at length."
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(
                        card().copy(
                            state = AgentTaskState.Done,
                            feed = longFeed,
                            fullResult = fullResult,
                            canCancel = false, canRedirect = false,
                        ),
                    ),
                    streamHealthy = true, onCancel = {}, onRedirect = { _, _ -> },
                )
            }
        }
        // The compact card shows only the tail — the earliest step is NOT on screen.
        compose.onNodeWithText("step number 1", substring = true).assertDoesNotExist()
        // Tap the card body -> full detail: every step line AND the untruncated result.
        compose.onNodeWithText("audit the shop site", substring = true).performClick()
        compose.onNodeWithText("step number 1", substring = true).assertIsDisplayed()
        // The newest line is legitimately on BOTH the compact card and the detail view.
        compose.onAllNodesWithText("step number 9", substring = true).onLast().assertIsDisplayed()
        compose.onNodeWithText("FINAL ANSWER", substring = true).assertIsDisplayed()
    }

    @Test
    fun detail_view_of_a_running_fabric_task_still_offers_cancel() {
        var cancelled: String? = null
        compose.setContent {
            EveTheme {
                AgentActivitySection(
                    cards = listOf(card(state = AgentTaskState.Working)),
                    streamHealthy = true, onCancel = { cancelled = it }, onRedirect = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("audit the shop site", substring = true).performClick()
        // Two Cancel buttons may exist (card + detail) — press the last one (detail).
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        kotlin.test.assertEquals("t1", cancelled)
    }
}
