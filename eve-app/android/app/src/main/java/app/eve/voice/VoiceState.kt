package app.eve.voice

/**
 * The conversation state machine — the CONVERSATION, not just the connection. Pure data with
 * ZERO `org.webrtc` imports so it is fully JVM-unit-testable. [WebRtcVoiceClient] produces
 * [VoiceEvent]s; [VoiceController] folds them through [reduce] onto a StateFlow.
 *
 * Turn-taking core (spec §3): YourTurn (mic open, waiting) vs Hearing (capturing you now, with
 * a live input level) — and the VAD end-of-turn handoff to Thinking → Speaking → back to
 * YourTurn (the closed loop that makes it a conversation, not a one-shot).
 *
 * Honesty (spec §3 / §5): MediaStalled drives a distinct NoAudio state (the orb never animates
 * speech over silence); NoAudio recovers when media resumes — it is NOT a sink.
 */
/**
 * In-call device controls, orthogonal to the conversation [VoiceState] (so muting never perturbs
 * the turn-taking machine). Two Booleans → inherently stable to the Compose compiler; kept here in
 * the org.webrtc-free file so it stays JVM-unit-testable. Defaults match what the client applies on
 * connect: speakerphone ON (hands-free), mic live.
 */
data class VoiceControls(
    val micMuted: Boolean = false,
    val speakerphoneOn: Boolean = true,
)

sealed interface VoiceState {
    /** "Tap to talk" — no session. */
    data object Idle : VoiceState

    /** Halo; handshake in flight. The controller owns the connect timeout. */
    data object Connecting : VoiceState

    /** Mic open, waiting for you ("Go ahead, I'm listening"). */
    data object YourTurn : VoiceState

    /** Capturing you now; [level] (0..1) drives the live input meter. */
    data class Hearing(val level: Float) : VoiceState

    /** EVE is thinking (shimmer). The controller owns the think timeout. */
    data object Thinking : VoiceState

    /** EVE is speaking (waveform). Tap-to-interrupt returns the floor. */
    data object Speaking : VoiceState

    /** Mid-session drop / network change → auto-retry with backoff (controller-driven). */
    data object Reconnecting : VoiceState

    /** Connected but no RTP bytes flowing — honest "connected, but no audio" (spec §3). */
    data object NoAudio : VoiceState

    /** Terminal failure with a cause-specific message. */
    data class Error(val message: String) : VoiceState
}

/**
 * Inputs to the reducer. Most are produced by [WebRtcVoiceClient] off the webrtc signaling
 * thread and marshalled by the controller; [StartRequested], [HangUp] and [Interrupt] are
 * user-driven; timeouts are emitted by the controller as [Failed].
 */
sealed interface VoiceEvent {
    /** User tapped the orb to start a session (Idle → Connecting). */
    data object StartRequested : VoiceEvent

    /** ICE/peer connection established → the floor is yours. */
    data object IceConnected : VoiceEvent

    /** Server-side VAD: you started speaking. */
    data object VadUserStart : VoiceEvent

    /** Server-side VAD: you stopped (end of turn) → EVE thinks. */
    data object VadUserEnd : VoiceEvent

    data object BotThinking : VoiceEvent

    /** EVE began speaking. */
    data object BotSpeaking : VoiceEvent

    /** EVE finished speaking → back to your turn (loop closed). */
    data object BotDone : VoiceEvent

    /** RTP inbound bytes are flowing again. */
    data object MediaFlowing : VoiceEvent

    /** Connected but no RTP inbound bytes after the grace window. */
    data object MediaStalled : VoiceEvent

    /** Connection dropped mid-session → reconnect. */
    data object Dropped : VoiceEvent

    /** Hard failure (connect timeout, think timeout, ICE failed). */
    data class Failed(val message: String) : VoiceEvent

    /** User tapped to hang up. */
    data object HangUp : VoiceEvent

    /** User tapped to interrupt EVE (barge-in) and reclaim the floor. */
    data object Interrupt : VoiceEvent
}

/**
 * The pure transition function. Total over (state, event): unhandled pairs hold the current
 * state (a stray event never corrupts the machine). HangUp from anywhere → Idle.
 */
fun reduce(state: VoiceState, event: VoiceEvent): VoiceState {
    // HangUp from any state ends the session.
    if (event is VoiceEvent.HangUp) return VoiceState.Idle

    // A hard failure from any active state is terminal (the controller decides when to emit it).
    if (event is VoiceEvent.Failed) return VoiceState.Error(event.message)

    return when (state) {
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
            VoiceEvent.MediaStalled -> VoiceState.NoAudio
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.Thinking -> when (event) {
            VoiceEvent.BotSpeaking -> VoiceState.Speaking
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
            // NoAudio is not a sink: media resuming returns to speaking (BMAD: Winston).
            VoiceEvent.MediaFlowing -> VoiceState.Speaking
            VoiceEvent.BotDone -> VoiceState.YourTurn
            VoiceEvent.Dropped -> VoiceState.Reconnecting
            else -> state
        }

        VoiceState.Reconnecting -> when (event) {
            // Reconnecting exits to YourTurn on recovery, or Error on hard failure (handled above).
            VoiceEvent.IceConnected -> VoiceState.YourTurn
            else -> state
        }

        is VoiceState.Error -> when (event) {
            VoiceEvent.StartRequested -> VoiceState.Connecting // retry from the error screen
            else -> state
        }
    }
}
