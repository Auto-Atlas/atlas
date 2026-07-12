package app.eve.wear.livevoice

/**
 * The watch LIVE-VOICE conversation state machine — a near-verbatim port of the phone's
 * app.eve.voice.VoiceState reducer (copied, not shared: :app's file lives in the phone module and
 * carries no dependency the watch can pull in). Pure data with ZERO Android / OkHttp imports so it is
 * fully JVM-unit-testable. [WsVoiceClient] produces [VoiceEvent]s; [LiveVoiceController] folds them
 * through [reduce] onto a StateFlow the orb observes.
 *
 * Two wear-specific additions over the phone's set, both documented where they appear:
 *  - [VoiceState.NotConfigured] — the honest "no voice door on this watch yet" state (the phone
 *    hasn't written a URL); the phone has no such state because it is always self-configured.
 *  - [VoiceEvent.UserTranscript] / [VoiceEvent.BotTranscript] — the server streams real transcript
 *    text; these carry it. They never change the conversation state (the reducer holds), they are
 *    lifted to the transcript surface by the ViewModel. Reply/state TEXT thus renders regardless of
 *    audio state (house rule).
 *
 * NOTE: this file DELIBERATELY does not import app.eve.wear.talk.VoiceState (the PcmPlayer output
 * state used by the v2 PTT screen). Same simple name, different package — the live path is separate.
 */

/**
 * In-call device controls, orthogonal to the conversation [VoiceState] (so muting never perturbs the
 * turn-taking machine). Defaults match what the client applies on connect: mic live. Speakerphone is
 * omitted — the wrist has one speaker.
 */
data class VoiceControls(
    val micMuted: Boolean = false,
)

sealed interface VoiceState {
    /** No voice door configured on this watch yet — set the URL in phone Settings (wear-specific). */
    data object NotConfigured : VoiceState

    /** "Tap to talk" — no session. */
    data object Idle : VoiceState

    /** Dialing the door; handshake in flight. The controller owns the connect timeout. */
    data object Connecting : VoiceState

    /** Mic open, waiting for you ("Go ahead, I'm listening"). */
    data object YourTurn : VoiceState

    /** Capturing you now; [level] (0..1) drives the live input meter / orb lean. */
    data class Hearing(val level: Float) : VoiceState

    /** Atlas is thinking (the brain form). The controller owns the think timeout. */
    data object Thinking : VoiceState

    /** Atlas is speaking (the genie form). Tap-to-interrupt returns the floor. */
    data object Speaking : VoiceState

    /** Mid-session drop / network change → auto-retry with backoff (controller-driven). */
    data object Reconnecting : VoiceState

    /** Connected but no audio bytes flowing — honest "connected, but no audio". */
    data object NoAudio : VoiceState

    /** Terminal failure with a cause-specific message. */
    data class Error(val message: String) : VoiceState
}

/**
 * Inputs to the reducer. Most are produced by [WsVoiceClient] (mapped from server control frames by
 * [LiveVoiceCodec]); [StartRequested], [HangUp] and [Interrupt] are user-driven; timeouts are emitted
 * by the controller as [Failed].
 */
sealed interface VoiceEvent {
    /** User tapped the orb to start a session (Idle → Connecting). */
    data object StartRequested : VoiceEvent

    /** Socket open + server "connected" → the floor is yours. */
    data object IceConnected : VoiceEvent

    /** Server VAD: you started speaking. */
    data object VadUserStart : VoiceEvent

    /** Server VAD: you stopped (end of turn) → Atlas thinks. */
    data object VadUserEnd : VoiceEvent

    data object BotThinking : VoiceEvent

    /** Atlas began speaking. */
    data object BotSpeaking : VoiceEvent

    /** Atlas finished speaking / server went idle → back to your turn (loop closed). */
    data object BotDone : VoiceEvent

    /** Audio bytes are flowing again. */
    data object MediaFlowing : VoiceEvent

    /** Connected but no audio bytes after the grace window. */
    data object MediaStalled : VoiceEvent

    /** Connection dropped mid-session → reconnect. */
    data object Dropped : VoiceEvent

    /** Hard failure (connect timeout, think timeout, socket error, server error frame). */
    data class Failed(val message: String) : VoiceEvent

    /** User tapped to hang up. */
    data object HangUp : VoiceEvent

    /** User tapped to interrupt Atlas (barge-in) and reclaim the floor. */
    data object Interrupt : VoiceEvent

    /** Server transcript of what Atlas HEARD (display only — never changes the machine). */
    data class UserTranscript(val text: String) : VoiceEvent

    /** Server transcript of what Atlas SAID (display only — never changes the machine). */
    data class BotTranscript(val text: String) : VoiceEvent
}

/**
 * The pure transition function. Total over (state, event): unhandled pairs hold the current state (a
 * stray event never corrupts the machine). HangUp from anywhere → Idle. Transcript events never move
 * the machine — they are carried to the transcript surface by the ViewModel.
 */
fun reduce(state: VoiceState, event: VoiceEvent): VoiceState {
    // HangUp from any state ends the session.
    if (event is VoiceEvent.HangUp) return VoiceState.Idle

    // A hard failure from any active state is terminal (the controller decides when to emit it).
    if (event is VoiceEvent.Failed) return VoiceState.Error(event.message)

    // Transcript frames are display-only: they never advance the conversation machine.
    if (event is VoiceEvent.UserTranscript || event is VoiceEvent.BotTranscript) return state

    return when (state) {
        // NotConfigured behaves like Idle for a start attempt — the controller re-checks the door and
        // either dials or drops back to NotConfigured (never a hardcoded fallback).
        VoiceState.NotConfigured -> when (event) {
            VoiceEvent.StartRequested -> VoiceState.Connecting
            else -> state
        }

        VoiceState.Idle -> when (event) {
            VoiceEvent.StartRequested -> VoiceState.Connecting
            else -> state
        }

        VoiceState.Connecting -> when (event) {
            VoiceEvent.IceConnected -> VoiceState.YourTurn
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.YourTurn -> when (event) {
            VoiceEvent.VadUserStart -> VoiceState.Hearing(0f)
            VoiceEvent.BotThinking -> VoiceState.Thinking
            VoiceEvent.BotSpeaking -> VoiceState.Speaking
            VoiceEvent.MediaStalled -> VoiceState.NoAudio
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        is VoiceState.Hearing -> when (event) {
            VoiceEvent.VadUserEnd -> VoiceState.Thinking
            VoiceEvent.BotThinking -> VoiceState.Thinking
            VoiceEvent.BotSpeaking -> VoiceState.Speaking
            VoiceEvent.MediaStalled -> VoiceState.NoAudio
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.Thinking -> when (event) {
            VoiceEvent.BotSpeaking -> VoiceState.Speaking
            VoiceEvent.BotDone -> VoiceState.YourTurn
            VoiceEvent.MediaStalled -> VoiceState.NoAudio
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.Speaking -> when (event) {
            VoiceEvent.BotDone -> VoiceState.YourTurn
            VoiceEvent.Interrupt -> VoiceState.YourTurn
            VoiceEvent.VadUserStart -> VoiceState.Hearing(0f) // barge-in via VAD
            VoiceEvent.MediaStalled -> VoiceState.NoAudio
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.NoAudio -> when (event) {
            // NoAudio is not a sink: audio resuming returns to speaking.
            VoiceEvent.MediaFlowing -> VoiceState.Speaking
            VoiceEvent.BotDone -> VoiceState.YourTurn
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.Reconnecting -> when (event) {
            VoiceEvent.IceConnected -> VoiceState.YourTurn
            else -> state
        }

        is VoiceState.Error -> when (event) {
            VoiceEvent.StartRequested -> VoiceState.Connecting // retry from the error screen
            else -> state
        }
    }
}
