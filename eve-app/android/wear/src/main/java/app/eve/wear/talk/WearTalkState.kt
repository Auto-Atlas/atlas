package app.eve.wear.talk

/**
 * The honest phases of ONE push-to-talk turn. Each names a real condition — there is no optimistic or
 * placeholder phase. The failure phase carries the EXACT user copy (from [WearTalkCopy]); the text
 * always carries the meaning so color is never the sole signal (a11y).
 */
sealed interface WearTalkPhase {
    /** Nothing in flight — the mic is ready. */
    data object Idle : WearTalkPhase

    /**
     * v2 native path: the wrist mic is capturing. [elapsedMs] drives the elapsed readout and the
     * final-5s countdown; [capMs] is the hard cap at which the VM auto-stops and sends. Tap-to-stop or
     * the cap both end this phase.
     */
    data class Recording(val elapsedMs: Long, val capMs: Long) : WearTalkPhase {
        /** Milliseconds remaining before the hard cap (never negative). */
        val remainingMs: Long get() = (capMs - elapsedMs).coerceAtLeast(0L)

        /** Whole seconds remaining, for the final-5s countdown readout. */
        val remainingSeconds: Int get() = ((remainingMs + 999L) / 1000L).toInt()
    }

    /** The audio is captured; it is being sent to the phone (native path) or STT produced a transcript (fallback). */
    data object Sending : WearTalkPhase

    /** The request left the watch; awaiting EVE's reply ("EVE is thinking…"). */
    data object ThinkingAwaitingReply : WearTalkPhase

    /**
     * EVE answered. [text] is the reply, rendered immediately (independent of any voice state).
     * [spokenOnWatch] is true when the audio was already played on the wrist by the native path's
     * [PcmPlayer] — the screen then must NOT also TTS-speak it (that would double her voice); false for
     * the fallback (Google) path, whose text-only reply is spoken via the on-watch TTS.
     */
    data class Replied(val text: String, val spokenOnWatch: Boolean = false) : WearTalkPhase

    /** A named, user-visible failure on some leg. [message] is the exact copy from [WearTalkCopy]. */
    data class TalkFailure(val message: String) : WearTalkPhase
}

/** One line of the session transcript (no persistence in v1). */
data class TalkTurn(val speaker: Speaker, val text: String, val atMs: Long) {
    enum class Speaker { You, Eve }
}
