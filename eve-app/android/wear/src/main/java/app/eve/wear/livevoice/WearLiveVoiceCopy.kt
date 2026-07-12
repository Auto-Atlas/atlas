package app.eve.wear.livevoice

import app.eve.ASSISTANT_NAME

/**
 * The SINGLE source of the watch LIVE-VOICE experience's user copy. Every state label and every
 * failure leg is named here exactly once, so the screen, the ViewModel, the controller and the codec
 * can never drift on wording (the same discipline as [app.eve.wear.talk.WearTalkCopy] for the PTT
 * screen and [app.eve.wear.approvals.WearActionCopy] for approvals). House rule — no silent fallback:
 * each failure maps to a specific, honest string; nothing is swallowed into a fake OK.
 */
object WearLiveVoiceCopy {

    // ---- the honest failure surface (all named, shown on the orb screen) ----

    /** No door URL has been written to this watch yet — the config-missing state's exact copy. */
    const val NOT_CONFIGURED = "No voice door configured — set it in phone Settings."

    /** The connect/reconnect window elapsed with no door answer — can't reach the voice door. */
    const val CONNECT_TIMED_OUT = "Can't reach $ASSISTANT_NAME — the voice door didn't answer."

    /** The server never produced a reply within the think window. */
    const val THINK_TIMED_OUT = "$ASSISTANT_NAME isn't responding."

    /** Bounded reconnects exhausted — the session is genuinely lost. */
    const val CONNECTION_LOST = "Lost connection to $ASSISTANT_NAME."

    /** The bearer token was rejected by the door / server (fail-closed auth). */
    const val UNAUTHORIZED = "Voice door rejected the token — re-pair the watch in phone Settings."

    /** The device has no network at all (offline) — named, never a silent hang. */
    const val NO_NETWORK = "No network — the watch can't reach the voice door."

    /** The mic could not be opened (permission / busy) — the live path can't stream you. */
    const val MIC_UNAVAILABLE = "Microphone unavailable — check permission and try again."

    /** A malformed control frame from the door/server — surfaced loudly, never ignored into silence. */
    const val BAD_CONTROL_FRAME = "$ASSISTANT_NAME sent something the watch couldn't read."

    /** The socket dropped and could not be re-established (used as the reconnect-exhausted detail). */
    fun socketError(reason: String): String = "Voice door connection error: $reason"

    /** A server "error" control frame carries its own message; render it verbatim behind a stable lead. */
    fun serverError(message: String): String = "$ASSISTANT_NAME reported a problem: $message"
}

/**
 * Per-state spoken label for the orb (also the live-region announcement). Ported from :app
 * ui/components/ListeningOrb.orbContentDescription, plus the wear-only [VoiceState.NotConfigured].
 */
fun orbContentDescription(state: VoiceState): String = when (state) {
    VoiceState.NotConfigured -> WearLiveVoiceCopy.NOT_CONFIGURED
    VoiceState.Idle -> "Tap to talk to $ASSISTANT_NAME"
    VoiceState.Connecting -> "Connecting to $ASSISTANT_NAME"
    VoiceState.YourTurn -> "Go ahead, I'm listening"
    is VoiceState.Hearing -> "Hearing you"
    VoiceState.Thinking -> "$ASSISTANT_NAME is thinking"
    VoiceState.Speaking -> "$ASSISTANT_NAME is speaking"
    VoiceState.Reconnecting -> "Reconnecting"
    VoiceState.NoAudio -> "Connected, but no audio is getting through"
    is VoiceState.Error -> "Connection problem: ${state.message}"
}
