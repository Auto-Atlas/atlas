package app.eve.voice

import app.eve.ASSISTANT_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates a [VoiceClient] (the V6 seam — production: [WebRtcVoiceClient]; test: synthetic
 * events) + the pure [reduce] machine, exposing a [StateFlow] of [VoiceState] the UI observes.
 *
 * Responsibilities the pure reducer deliberately does NOT own (BMAD: Winston/Amelia):
 *  - **Connect timeout ≥15s** — must EXCEED phone_bot's ≤10s single-session teardown so a
 *    legitimate eviction-reconnect (connecting from the app ends an active PWA/desktop session)
 *    doesn't spuriously Error.
 *  - **Think timeout** → Failed("Atlas isn't responding").
 *  - **Reconnect backoff** — the ONLY storm-preventer (server is ConnectionMode.MULTIPLE, it
 *    won't reject a 2nd connect), so it's a tested unit.
 *
 * Marshalling: the client already re-emits its events off the webrtc signaling thread; this
 * controller collects them on [scope] and writes the StateFlow there — Compose only ever reads
 * the flow, never the webrtc thread (spec §1).
 */
class VoiceController(
    private val client: VoiceClient,
    private val scope: CoroutineScope,
    private val connectTimeoutMs: Long = 15_000,
    private val thinkTimeoutMs: Long = 20_000,
    private val reconnectBaseDelayMs: Long = 1_000,
    private val reconnectMaxDelayMs: Long = 16_000,
    private val maxReconnects: Int = 5,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** In-call device controls (mic mute / speakerphone), orthogonal to the conversation state. */
    private val _controls = MutableStateFlow(VoiceControls())
    val controls: StateFlow<VoiceControls> = _controls.asStateFlow()

    /**
     * The single largest authority grant in the system has a UX corollary: connecting from the
     * app ends an active phone/desktop voice session (single-session task-cancel, spec §7). The
     * UI shows this before/with connect.
     */
    val endsOtherSessionNotice: String =
        "Connecting will end your active phone or desktop voice session."

    private var connectTimeoutJob: Job? = null
    private var thinkTimeoutJob: Job? = null
    private var reconnectAttempts = 0

    init {
        scope.launch {
            client.events.collect { onEvent(it) }
        }
    }

    /** User tapped the orb to start. */
    fun start() {
        if (_state.value != VoiceState.Idle && _state.value !is VoiceState.Error) return
        reconnectAttempts = 0
        dispatch(VoiceEvent.StartRequested)
        armConnectTimeout()
        scope.launch { client.connect() }
    }

    /** User tapped to hang up. */
    fun hangUp() {
        cancelTimers()
        reconnectAttempts = 0
        client.hangUp()
        // Reflect immediately; the client's own HangUp event will also arrive and is idempotent.
        _state.value = VoiceState.Idle
    }

    /** Toggle the outbound mic. Updates the controls flow and the live client immediately. */
    fun toggleMute() {
        val muted = !_controls.value.micMuted
        _controls.value = _controls.value.copy(micMuted = muted)
        client.setMicMuted(muted)
    }

    /** Toggle loudspeaker vs earpiece. Updates the controls flow and the live client immediately. */
    fun toggleSpeakerphone() {
        val on = !_controls.value.speakerphoneOn
        _controls.value = _controls.value.copy(speakerphoneOn = on)
        client.setSpeakerphone(on)
    }

    /** Re-assert the user's controls onto the client — the local track + audio routing are rebuilt
     *  on every (re)connect, so a chosen mute/earpiece would otherwise silently reset. */
    private fun applyControls() {
        client.setMicMuted(_controls.value.micMuted)
        client.setSpeakerphone(_controls.value.speakerphoneOn)
    }

    /** User tapped to interrupt Atlas while it speaks. */
    fun interrupt() {
        if (_state.value == VoiceState.Speaking || _state.value == VoiceState.NoAudio) {
            client.interrupt()
            // Reflect immediately (the client's own Interrupt event also arrives, idempotently).
            dispatch(VoiceEvent.Interrupt)
        }
    }

    /** Folds one event through the reducer + manages the timers/backoff around the transition. */
    private fun onEvent(event: VoiceEvent) {
        val before = _state.value
        dispatch(event)
        val after = _state.value
        manageSideEffects(before, after, event)
    }

    private fun dispatch(event: VoiceEvent) {
        _state.update { reduce(it, event) }
    }

    private fun manageSideEffects(before: VoiceState, after: VoiceState, event: VoiceEvent) {
        // Clear the connect timeout once we leave Connecting/Reconnecting successfully.
        if (after == VoiceState.YourTurn) {
            connectTimeoutJob?.cancel()
            reconnectAttempts = 0
            // (Re)connect rebuilds the mic track + audio routing — re-assert the user's controls.
            if (before == VoiceState.Connecting || before == VoiceState.Reconnecting) applyControls()
        }

        // Arm the think timeout while Atlas is thinking; clear it once it speaks/leaves.
        if (after == VoiceState.Thinking) armThinkTimeout() else thinkTimeoutJob?.cancel()

        // A mid-session drop → controller-driven reconnect with backoff (the storm-preventer).
        if (after == VoiceState.Reconnecting && before != VoiceState.Reconnecting) {
            scheduleReconnect()
        }

        if (after is VoiceState.Error || after == VoiceState.Idle) cancelTimers()
    }

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(connectTimeoutMs)
            if (_state.value == VoiceState.Connecting || _state.value == VoiceState.Reconnecting) {
                dispatch(VoiceEvent.Failed("Can't reach $ASSISTANT_NAME — connection timed out."))
            }
        }
    }

    private fun armThinkTimeout() {
        thinkTimeoutJob?.cancel()
        thinkTimeoutJob = scope.launch {
            delay(thinkTimeoutMs)
            if (_state.value == VoiceState.Thinking) {
                dispatch(VoiceEvent.Failed("$ASSISTANT_NAME isn't responding."))
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnects) {
            dispatch(VoiceEvent.Failed("Lost connection to $ASSISTANT_NAME."))
            return
        }
        val attempt = reconnectAttempts
        reconnectAttempts++
        val backoff = (reconnectBaseDelayMs shl attempt).coerceAtMost(reconnectMaxDelayMs)
        scope.launch {
            delay(backoff)
            if (_state.value == VoiceState.Reconnecting && isActive) {
                armConnectTimeout()
                // Reconnect = a fresh offer (restart_pc reserved/unused in v1).
                client.connect()
            }
        }
    }

    private fun cancelTimers() {
        connectTimeoutJob?.cancel()
        thinkTimeoutJob?.cancel()
    }
}
