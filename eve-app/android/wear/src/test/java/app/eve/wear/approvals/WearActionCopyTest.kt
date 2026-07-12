package app.eve.wear.approvals

import app.eve.ASSISTANT_NAME
import app.eve.data.wear.Outcome
import app.eve.data.wear.WearActionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the SINGLE-source outcome -> copy mapping ([WearActionCopy.forResult]) that both the in-app
 * banner ([WearApprovalsViewModel], asserted separately) and the wrist Deny notification render from.
 * The VM's own tests exercise the SAME strings through the VM, unchanged — this guards the extracted
 * object directly so the two can never diverge.
 */
class WearActionCopyTest {

    private fun result(outcome: Outcome, detail: String? = null) =
        WearActionResult(requestId = "r", approvalId = "a", outcome = outcome, detail = detail)

    @Test
    fun approved_invoice_vs_channel_wording() {
        assertEquals(
            "Approved — invoice released",
            WearActionCopy.forResult(result(Outcome.APPROVED), isInvoice = true).message,
        )
        assertEquals(
            "Approved — sent",
            WearActionCopy.forResult(result(Outcome.APPROVED), isInvoice = false).message,
        )
    }

    @Test
    fun denied_and_already_resolved_are_neutral() {
        val denied = WearActionCopy.forResult(result(Outcome.DENIED), isInvoice = false)
        assertEquals("Denied", denied.message)
        assertEquals(WearActionState.Tone.Neutral, denied.tone)

        val already = WearActionCopy.forResult(result(Outcome.ALREADY_RESOLVED), isInvoice = false)
        assertEquals("Already handled elsewhere", already.message)
        assertEquals(WearActionState.Tone.Neutral, already.tone)
    }

    @Test
    fun server_unreachable_shows_phone_detail() {
        val r = WearActionCopy.forResult(result(Outcome.SERVER_UNREACHABLE, "connection refused"), isInvoice = false)
        assertEquals("Phone can't reach $ASSISTANT_NAME: connection refused", r.message)
        assertEquals(WearActionState.Tone.Negative, r.tone)
    }

    @Test
    fun detail_carrying_outcomes_render_verbatim_with_fallbacks() {
        assertEquals("bad token", WearActionCopy.forResult(result(Outcome.UNAUTHORIZED, "bad token"), false).message)
        assertEquals("Unauthorized", WearActionCopy.forResult(result(Outcome.UNAUTHORIZED, null), false).message)
        assertEquals(
            "Approval no longer exists",
            WearActionCopy.forResult(result(Outcome.NOT_FOUND, null), false).message,
        )
        assertEquals("boom", WearActionCopy.forResult(result(Outcome.ERROR, "boom"), false).message)
        assertEquals("Something went wrong", WearActionCopy.forResult(result(Outcome.ERROR, null), false).message)
    }

    @Test
    fun data_layer_down_phrase_is_the_shared_constant() {
        assertEquals("Phone unreachable — Data Layer down", WearActionCopy.DATA_LAYER_DOWN)
    }
}
