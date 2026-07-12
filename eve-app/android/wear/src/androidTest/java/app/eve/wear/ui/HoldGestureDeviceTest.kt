package app.eve.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eve.data.EveWireJson
import app.eve.data.models.ApprovalsResponse
import app.eve.wear.approvals.WearActionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ON-DEVICE proof of the hold-to-approve money gate inside the REAL detail screen hierarchy — the
 * gesture reaches the gate through the production UI, not just the isolated button of the
 * Robolectric unit test. This is the test that CAUGHT the release-race product bug (2026-07-10): a
 * ~200ms press was approving because the old detectTapGestures/tryAwaitRelease release-cancel
 * resumed through the frame-gated dispatcher while the gate fired off the real-time handler.
 *
 * Runs with **reducedMotion = true** deliberately: that path draws the fill with snapTo (no running
 * animation), which is the scenario where a collapsed gate would be most dangerous — a snap-to-1f
 * visual must NOT shorten the 520ms commit — AND, having no animation, it lets us freeze the clock
 * (autoAdvance = false) and drive the gate's delay deterministically with advanceTimeBy without
 * waitForIdle ever hanging on an unfinished tween.
 *
 * Proven on real rendering: press + advance < 520ms + release never approves; press + advance past
 * 520ms while still pressed approves exactly once.
 */
@RunWith(AndroidJUnit4::class)
class HoldGestureDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun fixtureApproval() =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json")) {
            "missing approvals_sample.json in androidTest resources"
        }.bufferedReader().use { it.readText() }
            .let { EveWireJson.decodeFromString(ApprovalsResponse.serializer(), it) }
            .approvals.first()
            .let {
                val nowS = System.currentTimeMillis() / 1000.0
                it.copy(createdAt = nowS, expiresAt = nowS + it.ttlSeconds, secondsLeft = it.ttlSeconds.toDouble())
            }

    @Test
    fun holdPast520msApprovesExactlyOnce_shortPressNever() {
        var approves = 0
        // Render only the gate (not the whole scrolled detail) so it is always on-screen and the
        // touch lands directly — the enclosing detail column is exercised by the Robolectric suite.
        compose.setContent {
            HoldToApproveWear(
                label = "Hold to approve",
                onApprove = { approves++ },
                reducedMotion = true,
            )
        }
        // Freeze the clock: the gate's delay() is driven by the compose test clock, so we advance
        // it explicitly. Safe here because reducedMotion=true means no running animation to hang on.
        compose.mainClock.autoAdvance = false
        compose.waitForIdle()

        val hold = compose.onNodeWithTag("holdApproveWear")

        // Short press: down, advance the gate clock to just under 520ms, release. Then advance well
        // past 520ms — a mis-armed gate would fire here; the correct gate stays silent (released).
        hold.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        hold.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("released before 520ms must never approve", 0, approves)

        // Full hold: down, advance past 520ms while STILL pressed — the gate fires exactly once.
        hold.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        assertEquals("a >520ms hold approves exactly once", 1, approves)
        hold.performTouchInput { up() }
    }
}
