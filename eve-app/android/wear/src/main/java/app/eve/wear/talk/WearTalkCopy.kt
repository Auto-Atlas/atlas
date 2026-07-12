package app.eve.wear.talk

import app.eve.ASSISTANT_NAME
import app.eve.data.wear.Outcome
import app.eve.data.wear.TalkReply
import app.eve.data.wear.VoiceTurnReply
import app.eve.wear.approvals.WearActionCopy

/**
 * The SINGLE source of the watch talk experience's user copy. Every in-flight line and every failure
 * leg is named here exactly once, so the screen, the ViewModel and the voice speaker can never drift
 * on wording (the same discipline as [WearActionCopy] for approvals). House rule — no silent
 * fallback: each failure maps to a specific, honest string; nothing is swallowed into a fake OK.
 */
object WearTalkCopy {

    /** The watch<->phone Data Layer down phrase — shared verbatim with the approvals leg. */
    val DATA_LAYER_DOWN: String = WearActionCopy.DATA_LAYER_DOWN

    // ---- v2 native path (wrist mic -> Atlas's own voice) ----

    /** The wrist mic is capturing (native path). */
    const val RECORDING = "Listening…"

    /** After tap-stop / cap, before the reply: the recorded audio is on its way to the phone. */
    const val SENDING = "Sending…"

    /** The final-5s countdown readout while recording. [secondsLeft] is whole seconds to the cap. */
    fun countdown(secondsLeft: Int): String = "${secondsLeft}s left"

    /** RECORD_AUDIO not granted — the native path can't open the mic (offer the permission again). */
    const val MIC_PERMISSION = "Microphone permission needed"

    /** The mic is held by another app / could not be opened. */
    const val MIC_BUSY = "Microphone is busy — try again"

    /** The capture yielded nothing usable (zero-length) — a named failure, never sent onward. */
    const val RECORDING_EMPTY = "Didn't hear anything — tap to retry."

    /** Server delivered her answer TEXT but her voice leg failed — shown as a small note, text stays. */
    const val VOICE_UNAVAILABLE = "$ASSISTANT_NAME's voice is unavailable — text only"

    /** Playing her PCM reply on the wrist failed — a small note; the reply text is still shown above. */
    const val PLAYBACK_FAILED = "Couldn't play $ASSISTANT_NAME's voice — reply shown above"

    /** The explicit, honest label for the old RecognizerIntent path (now a fallback, not the default). */
    const val FALLBACK_LABEL = "Google voice (fallback)"

    /** In-flight, after the request left the watch: Atlas's brain is working. */
    const val THINKING = "$ASSISTANT_NAME is thinking…"

    /** The phone never answered within the watch's await window — honest failure, never a fake reply. */
    const val NO_REPLY = "No reply from phone"

    /** RESULT_OK but the STT transcript was blank — rejected, never sent downstream. */
    const val DIDNT_CATCH = "Didn't catch that — tap to retry."

    /** No on-watch recognizer (SpeechRecognizer unavailable / ActivityNotFoundException). */
    const val NO_SPEECH_SERVICE = "No speech service on this watch — enable Speech Services by Google."

    /** Atlas answered OK but with empty text — a broken contract, surfaced loudly (never a blank reply). */
    const val EMPTY_REPLY = "$ASSISTANT_NAME returned an empty reply"

    /** Voice output is warming up (Wear cold-boot TTS can take ~10s). The reply TEXT still shows regardless. */
    const val WARMING_UP_VOICE = "warming up voice…"

    /** Voice output failed entirely — shown as a small note; NEVER hides the reply text. */
    const val VOICE_FAILED = "voice unavailable — reply shown above"

    /** A [SendOutcome.SendFailed]: the Data-Layer leg named, plus the real transport reason. */
    fun sendFailed(reason: String): String = "$DATA_LAYER_DOWN: $reason"

    /** The channel opened and the audio was sent, but no usable reply came back — the honest reason. */
    fun channelNoReply(reason: String): String = "$NO_REPLY: $reason"

    /**
     * Map one non-OK phone [TalkReply] to its honest failure copy. Returns null for [Outcome.OK] (the
     * caller renders the reply text instead). Mirrors [WearActionCopy.forResult]'s named-leg vocabulary.
     */
    fun failureFor(reply: TalkReply): String? = failureFor(reply.outcome, reply.detail)

    /** Same named-leg mapping for the v2 native voice reply — one vocabulary for both talk paths. */
    fun failureForVoice(reply: VoiceTurnReply): String? = failureFor(reply.outcome, reply.detail)

    private fun failureFor(outcome: Outcome, detail: String?): String? = when (outcome) {
        Outcome.OK -> null
        // Phone reached the watch but not Atlas — show the phone's real detail.
        Outcome.SERVER_UNREACHABLE -> "Phone can't reach $ASSISTANT_NAME: ${detail ?: "unreachable"}"
        // These carry a real, specific detail from the phone — render it verbatim.
        Outcome.UNAUTHORIZED -> detail ?: "Unauthorized"
        Outcome.ERROR -> detail ?: "Something went wrong"
        // Outcomes the talk leg never emits (approve/deny vocabulary). Surface honestly, never crash.
        Outcome.APPROVED, Outcome.DENIED, Outcome.ALREADY_RESOLVED, Outcome.NOT_FOUND ->
            detail ?: "Unexpected reply from $ASSISTANT_NAME ($outcome)"
    }
}
