package app.eve.wear.ui

import app.eve.ASSISTANT_NAME
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.eve.wear.approvals.TestApprovals
import app.eve.wear.approvals.WearActionState
import app.eve.wear.approvals.WearApprovalsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric render tests for the watch approval screens — each honest UI state renders its REAL
 * copy (no filler), and a pending row/detail shows the four W's (requester, amount, risk). Mirrors
 * the :app compose-test convention (RobolectricTestRunner + @Config(sdk=34) + NATIVE + composeRule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearApprovalsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- list-screen states -------------------------------------------------

    @Test
    fun empty_state_shows_no_pending_approvals() {
        rule.setContent {
            WearApprovalsListScreen(
                state = WearApprovalsUiState.Empty,
                onSelect = {},
                onRetryLink = {},
            )
        }
        rule.onNodeWithText("No pending approvals").assertIsDisplayed()
    }

    @Test
    fun no_phone_state_shows_the_data_layer_leg_and_retry() {
        rule.setContent {
            WearApprovalsListScreen(
                state = WearApprovalsUiState.NoPhone("Phone unreachable — Data Layer down"),
                onSelect = {},
                onRetryLink = {},
            )
        }
        rule.onNodeWithText("Phone unreachable — Data Layer down").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun server_down_with_stale_list_is_labelled_stale_with_real_detail() {
        val fetchedAt = 1_000_000L
        rule.setContent {
            WearApprovalsListScreen(
                state = WearApprovalsUiState.ServerDown(
                    detail = "cannot reach $ASSISTANT_NAME: connection refused",
                    staleApprovals = listOf(TestApprovals.invoice("a1")),
                    fetchedAtEpochMs = fetchedAt,
                ),
                onSelect = {},
                onRetryLink = {},
                nowMs = { fetchedAt + 120_000 }, // 2 minutes later
            )
        }
        rule.onNodeWithText("Stale — showing list from 2m ago").assertIsDisplayed()
        rule.onNodeWithText("cannot reach $ASSISTANT_NAME: connection refused").assertIsDisplayed()
    }

    @Test
    fun server_down_without_stale_shows_the_real_detail() {
        rule.setContent {
            WearApprovalsListScreen(
                state = WearApprovalsUiState.ServerDown(
                    detail = "phone not connected to $ASSISTANT_NAME",
                    staleApprovals = null,
                    fetchedAtEpochMs = 0L,
                ),
                onSelect = {},
                onRetryLink = {},
            )
        }
        rule.onNodeWithText("phone not connected to $ASSISTANT_NAME").assertIsDisplayed()
    }

    // ---- a pending row (the four W's) --------------------------------------

    @Test
    fun approval_chip_shows_title_requester_and_risk() {
        rule.setContent {
            ApprovalChip(approval = TestApprovals.invoice("a1"), stale = false, onClick = {})
        }
        rule.onNodeWithText("\$1,200 invoice").assertIsDisplayed()
        rule.onNodeWithText("Requested by Jamie").assertIsDisplayed()
        rule.onNodeWithText("High risk").assertIsDisplayed()
    }

    // ---- detail screen ------------------------------------------------------

    @Test
    fun detail_shows_request_facts_and_the_actions() {
        rule.setContent {
            WearApprovalDetailScreen(
                approval = TestApprovals.invoice("a1"),
                actionState = WearActionState.Idle,
                reducedMotion = true,
                onApprove = {},
                onDeny = {},
                nowMs = { 1_750_000_000_000L }, // well before expiry
            )
        }
        rule.onNodeWithText("\$1,200 invoice").assertIsDisplayed()
        rule.onNodeWithText("Jamie").assertIsDisplayed()
        rule.onNodeWithText("High risk").assertIsDisplayed()
        rule.onNodeWithText("\$1,200").assertIsDisplayed() // the amount, distinct from the title
        rule.onNodeWithText("Hold to approve").assertIsDisplayed()
        rule.onNodeWithText("Deny").assertIsDisplayed()
    }

    @Test
    fun detail_in_flight_shows_sending() {
        rule.setContent {
            WearApprovalDetailScreen(
                approval = TestApprovals.invoice("a1"),
                actionState = WearActionState.InFlight("r"),
                reducedMotion = true,
                onApprove = {},
                onDeny = {},
                nowMs = { 1_750_000_000_000L },
            )
        }
        rule.onNodeWithText("Sending…").assertIsDisplayed()
    }

    @Test
    fun detail_resolved_shows_honest_copy_and_hides_actions() {
        rule.setContent {
            WearApprovalDetailScreen(
                approval = TestApprovals.invoice("a1"),
                actionState = WearActionState.Resolved(
                    "Approved — invoice released",
                    WearActionState.Tone.Positive,
                ),
                reducedMotion = true,
                onApprove = {},
                onDeny = {},
                nowMs = { 1_750_000_000_000L },
            )
        }
        rule.onNodeWithText("Approved — invoice released").assertIsDisplayed()
        rule.onNodeWithText("Hold to approve").assertDoesNotExist()
    }
}
