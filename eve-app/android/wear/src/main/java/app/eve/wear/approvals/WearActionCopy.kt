package app.eve.wear.approvals

import app.eve.ASSISTANT_NAME
import app.eve.data.wear.Outcome
import app.eve.data.wear.WearActionResult

/**
 * The SINGLE source of the approve/deny outcome -> user copy mapping. Both the in-app
 * [WearApprovalsViewModel] (the detail-screen banner) and the notification-side deny flow
 * ([app.eve.wear.notify.WearDenyReceiver]) render from here, so the honest per-outcome wording
 * exists exactly ONCE and the wrist notification can never drift from the in-app banner.
 *
 * Named-leg honesty (house rule): nothing is swallowed into a fake success. A phone that reached the
 * watch but not Atlas is [Outcome.SERVER_UNREACHABLE] with the phone's real detail; a partial
 * "approved but the tool didn't fire" arrives as [Outcome.ERROR] with detail — never APPROVED.
 */
object WearActionCopy {

    /**
     * The watch<->phone Data Layer down phrase, shared so the in-app banner and the notification say
     * the SAME thing. The VM appends the transport reason for a SendFailed; the notification uses the
     * bare phrase.
     */
    const val DATA_LAYER_DOWN = "Phone unreachable — Data Layer down"

    /**
     * Map one phone [WearActionResult] to its terminal banner. [isInvoice] only distinguishes the
     * APPROVED wording ("invoice released" vs "sent"); every other outcome is identical regardless.
     */
    fun forResult(result: WearActionResult, isInvoice: Boolean): WearActionState.Resolved =
        when (result.outcome) {
            Outcome.APPROVED -> WearActionState.Resolved(
                if (isInvoice) "Approved — invoice released" else "Approved — sent",
                WearActionState.Tone.Positive,
            )
            Outcome.DENIED -> WearActionState.Resolved("Denied", WearActionState.Tone.Neutral)
            // Resolved on another surface before this tap landed.
            Outcome.ALREADY_RESOLVED ->
                WearActionState.Resolved("Already handled elsewhere", WearActionState.Tone.Neutral)
            // Phone reached the watch but not Atlas — show the phone's real detail.
            Outcome.SERVER_UNREACHABLE -> WearActionState.Resolved(
                "Phone can't reach $ASSISTANT_NAME: ${result.detail ?: "unreachable"}",
                WearActionState.Tone.Negative,
            )
            // These carry a real, specific detail from the phone — render it verbatim.
            Outcome.UNAUTHORIZED ->
                WearActionState.Resolved(result.detail ?: "Unauthorized", WearActionState.Tone.Negative)
            Outcome.NOT_FOUND ->
                WearActionState.Resolved(result.detail ?: "Approval no longer exists", WearActionState.Tone.Negative)
            Outcome.ERROR ->
                WearActionState.Resolved(result.detail ?: "Something went wrong", WearActionState.Tone.Negative)
            // OK is the talk leg's success outcome — it never rides an approve/deny result. If one
            // ever arrives here it's a wire/protocol fault, surfaced loudly (never a fake approval).
            Outcome.OK ->
                WearActionState.Resolved(result.detail ?: "Unexpected reply from $ASSISTANT_NAME", WearActionState.Tone.Negative)
        }
}
