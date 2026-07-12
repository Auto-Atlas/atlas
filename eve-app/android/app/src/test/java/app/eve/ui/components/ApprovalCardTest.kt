package app.eve.ui.components

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.eve.data.ApiClient
import app.eve.data.models.Approval
import app.eve.data.models.ApprovalsResponse
import app.eve.ui.approvals.ApprovalCardState
import app.eve.ui.approvals.CardPhase
import app.eve.ui.theme.EveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for ApprovalCard — EVE's money-bearing hero (compose-ui-testing-patterns:
 * smallest state-driven tests; assert text/semantics, not pixels). reducedMotion = true makes the
 * animateContentSize / AnimatedVisibility / Crossfade deterministic. The approval is decoded from
 * the real test fixture (mirrors ApprovalsViewModelTest.fixtureApprovals): first row is an invoice
 * for The Browns totalling $1,200.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureApproval(): Approval {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json"))
            .bufferedReader().use { it.readText() }
        return ApiClient.DEFAULT_JSON.decodeFromString<ApprovalsResponse>(text).approvals.first()
    }

    private fun state(expanded: Boolean, secondsLeft: Long = 600): ApprovalCardState {
        val ap = fixtureApproval()
        return ApprovalCardState(
            approval = ap,
            phase = CardPhase.Pending(secondsLeft),
            expanded = expanded,
            secondsLeft = secondsLeft,
        )
    }

    @Test
    fun collapsed_online_shows_amount_and_review_and_hides_deny() {
        rule.setContent {
            EveTheme {
                ApprovalCard(
                    state = state(expanded = false),
                    online = true,
                    reducedMotion = true,
                    onToggleExpand = {},
                    onApprove = {},
                    onDeny = {},
                )
            }
        }

        // Amount is rendered from frozen args (2x$480 + $240 = $1,200), carried as a semantics
        // contentDescription "Amount $1,200" on the money Text.
        rule.onNodeWithContentDescription("Amount $1,200").assertIsDisplayed()
        // Collapsed => the expand affordance reads "Review".
        rule.onNodeWithText("Review").assertIsDisplayed()
        // Detail (and thus the Deny action) is not in the tree while collapsed.
        rule.onNodeWithContentDescription("Deny this request").assertDoesNotExist()
    }

    @Test
    fun offline_expanded_disables_actions_and_shows_stale_banner() {
        rule.setContent {
            EveTheme {
                ApprovalCard(
                    state = state(expanded = true),
                    online = false,
                    reducedMotion = true,
                    onToggleExpand = {},
                    onApprove = {},
                    onDeny = {},
                )
            }
        }

        // Offline => actions disabled. The disabled EveButton omits its clickable modifier
        // entirely (EveButton: disabled => no clickable), so the Deny node has no click action.
        rule.onNodeWithText("Deny").assertHasNoClickAction()
        // The honest stale banner is present (never silently failing). assertExists rather than
        // assertIsDisplayed: in the headless Robolectric window the expanded card can push the
        // banner below the visible bounds, but its presence in the semantics tree is the signal.
        rule.onNode(hasText("Stale — can't reach EVE", substring = true)).assertExists()
    }

    @Test
    fun expanded_shows_requested_by_trust_context() {
        rule.setContent {
            EveTheme {
                ApprovalCard(
                    state = state(expanded = true),
                    online = true,
                    reducedMotion = true,
                    onToggleExpand = {},
                    onApprove = {},
                    onDeny = {},
                )
            }
        }

        // Trust context: the expanded detail surfaces WHO asked, from the real
        // Approval.requester field ("Jamie" in approvals_sample.json), before the owner
        // commits the money action. assertExists (not assertIsDisplayed): the expanded card
        // can push this below the headless Robolectric window bounds.
        rule.onNodeWithText("Requested by Jamie").assertExists()
    }

    @Test
    fun tapping_review_invokes_on_toggle_expand() {
        var toggled = false
        rule.setContent {
            EveTheme {
                ApprovalCard(
                    state = state(expanded = false),
                    online = true,
                    reducedMotion = true,
                    onToggleExpand = { toggled = true },
                    onApprove = {},
                    onDeny = {},
                )
            }
        }

        rule.onNodeWithText("Review").performClick()
        assertTrue("Tapping Review must fire onToggleExpand", toggled)
    }
}
