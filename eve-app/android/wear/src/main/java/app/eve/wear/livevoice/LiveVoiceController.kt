package app.eve.wear.livevoice

import app.eve.data.wear.VoiceDoorConfig
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
 * Orchestrates the [WsVoiceClient] seam + the pure [reduce] machine, exposing a [StateFlow] of
 * [VoiceState] the orb observes. A near-verbatim adaptation of the phone's app.eve.voice.VoiceController
 * (same timeout/backoff semantics), differing only where the wrist differs:
 *  - the door config is DATA-DRIVEN ([updateConfig]) — a blank/absent door sits the machine in
 *    [VoiceState.NotConfigured] until the phone writes a real URL (nothing hardcoded);
 *  - reconnect re-dials the SAME resolved URL/token (no signaling offer to rebuild);
 *  - every failure carries a centralized [WearLiveVoiceCopy] string.
 *
 * The client re-emits its events on the socket's callback thread; this controller collects them on
 * [scope] and writes the StateFlow there — Compose only ever reads the flow.
 */
class LiveVoiceController(
    private val client: WsVoiceClient,
    private val scope: CoroutineScope,
    private val connectTimeoutMs: Long = 15_000,
    private val thinkTimeoutMs: Long = 20_000,
    private val reconnectBaseDelayMs: Long = 1_000,
    private val reconnectMaxDelayMs: Long = 16_000,
    private val maxReconnects: Int = 5,
) {
    // Starts NotConfigured: nothing is dialable until the phone's door config arrives via updateConfig.
    private val _state = MutableStateFlow<VoiceState>(VoiceState.NotConfigured)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _controls = MutableStateFlow(VoiceControls())
    val controls: StateFlow<VoiceControls> = _controls.asStateFlow()

    /** The latest door the phone wrote; the dial source of truth. Null until the first read. */
    @Volatile private var latestConfig: VoiceDoorConfig? = null

    /** The URL/token a live session dialed with — reused verbatim on reconnect. */
    private var dialUrl: String = ""
    private var dialToken: String = ""

    private var connectTimeoutJob: Job? = null
    private var thinkTimeoutJob: Job? = null
    private var reconnectAttempts = 0

    // App-open auto-start fires at most ONCE per screen entry. Without this guard the door source's
    // repeated config emissions (initial read + listener redelivery ~1-3s later) each dialed,
    // and the second socket preempted and killed the first (hardware, 2026-07-11). Re-armed ONLY by
    // [rearmAutoStart] (a fresh screen entry / app open) and DISARMED by [hangUp]: the screen's
    // auto-start effect refires when a deliberate end rests the state at Idle, and a hangUp-side
    // re-arm would turn "end call" into "instantly redial".
    private var autoStarted = false

    init {
        scope.launch { client.events.collect { onEvent(it) } }
    }

    /**
     * Feed a fresh door config (from the Data Layer). When the machine is at rest (Idle/NotConfigured),
     * flip between those two so the orb honestly reflects whether there is a door — WITHOUT ever
     * disturbing a live session.
     */
    fun updateConfig(config: VoiceDoorConfig) {
        // De-dupe identical redeliveries: the real door source emits the SAME retained config more
        // than once (initial read, then a listener redelivery ~1-3s later). An unchanged door must
        // never re-touch the machine — that repeat is what let auto-start dial a second socket.
        if (config == latestConfig) return
        latestConfig = config
        val s = _state.value
        if (s == VoiceState.Idle || s == VoiceState.NotConfigured) {
            _state.value = if (config.isConfigured) VoiceState.Idle else VoiceState.NotConfigured
        }
    }

    /**
     * App-open auto-start: dial once when a door is configured and the machine is at rest. Idempotent
     * — a repeated call (config redelivery, screen recomposition, a stray double-fire) never opens a
     * second socket. Only [VoiceState.Idle] (a real door present) auto-dials; a missing door stays the
     * honest [VoiceState.NotConfigured], and an error is left for a deliberate tap to retry — never a
     * fake dial, never an auto-retry storm.
     */
    fun autoStartIfReady() {
        if (autoStarted) return
        if (_state.value != VoiceState.Idle) return
        autoStarted = true
        start()
    }

    /**
     * Fresh screen entry / app open: re-arm the one-shot auto-start. Deliberately NOT called from
     * [hangUp] — ending a call rests at Idle and the screen's auto-start effect refires there, so a
     * teardown-side re-arm would instantly redial the call the owner just ended.
     */
    fun rearmAutoStart() {
        autoStarted = false
    }

    /** User tapped the orb to start. Missing door → the named NotConfigured state (never a fake dial). */
    fun start() {
        val s = _state.value
        if (s != VoiceState.Idle && s != VoiceState.NotConfigured && s !is VoiceState.Error) return
        val config = latestConfig
        if (config == null || !config.isConfigured) {
            _state.value = VoiceState.NotConfigured
            return
        }
        reconnectAttempts = 0
        // A NEW call always starts with a live mic. Mute persists across MID-call reconnects
        // (the YourTurn re-assert below), but carrying it into the next user-initiated call
        // produced a deaf-looking Atlas on hardware (2026-07-10): mute tapped in call #1 silently
        // survived into call #2 and "it stopped responding".
        _controls.value = _controls.value.copy(micMuted = false)
        client.setMicMuted(false)
        dialUrl = config.wsUrl
        dialToken = config.token
        dispatch(VoiceEvent.StartRequested)
        armConnectTimeout()
        scope.launch { client.connect(dialUrl, dialToken) }
    }

    /** User deliberately ended the call (long-press / programmatic teardown). */
    fun hangUp() {
        cancelTimers()
        reconnectAttempts = 0
        // DISARM auto-start: the rest state below is Idle and the screen's auto-start effect refires
        // on it — without this, ending a call instantly redials it. Re-armed only on screen entry.
        autoStarted = true
        client.hangUp()
        // Reflect immediately; if a door is configured, rest at Idle, else NotConfigured.
        _state.value = if (latestConfig?.isConfigured == true) VoiceState.Idle else VoiceState.NotConfigured
    }

    /** Toggle the outbound mic. Updates the controls flow and the live client immediately. */
    fun toggleMute() {
        val muted = !_controls.value.micMuted
        _controls.value = _controls.value.copy(micMuted = muted)
        client.setMicMuted(muted)
    }

    /** User tapped to interrupt Atlas while it speaks. */
    fun interrupt() {
        if (_state.value == VoiceState.Speaking || _state.value == VoiceState.NoAudio) {
            client.interrupt()
            dispatch(VoiceEvent.Interrupt) // reflect immediately; the server's idle frame also arrives
        }
    }

    /**
     * Tap while Atlas is speaking: stop her playback AND hand the floor back with a LIVE mic. If the
     * user had muted himself, this unmutes first (truthfully — the client's outbound mic reopens) so
     * a mid-utterance tap is never a silent no-op that leaves a deaf Atlas.
     */
    fun interruptAndUnmute() {
        if (_controls.value.micMuted) {
            _controls.value = _controls.value.copy(micMuted = false)
            client.setMicMuted(false)
        }
        interrupt()
    }

    /** A named, visible failure raised OUTSIDE the socket (e.g. the screen denied mic permission). */
    fun fail(message: String) {
        cancelTimers()
        reconnectAttempts = 0
        client.hangUp()
        _state.value = VoiceState.Error(message)
    }

    private fun onEvent(event: VoiceEvent) {
        val before = _state.value
        dispatch(event)
        val after = _state.value
        manageSideEffects(before, after)
        // A retry attempt that itself drops leaves the state at Reconnecting (before == after), so
        // the transition-based scheduler below never re-fires — hardware-found 2026-07-10 as an
        // abandoned retry loop. A drop WHILE reconnecting explicitly schedules the next attempt
        // (still bounded: scheduleReconnect exhausts into the named CONNECTION_LOST failure).
        if (event == VoiceEvent.Dropped &&
            before == VoiceState.Reconnecting && after == VoiceState.Reconnecting
        ) {
            scheduleReconnect()
        }
    }

    private fun dispatch(event: VoiceEvent) {
        _state.update { reduce(it, event) }
    }

    private fun manageSideEffects(before: VoiceState, after: VoiceState) {
        if (after == VoiceState.YourTurn) {
            connectTimeoutJob?.cancel()
            reconnectAttempts = 0
            // (Re)connect rebuilds the mic stream — re-assert the user's mute choice.
            if (before == VoiceState.Connecting || before == VoiceState.Reconnecting) {
                client.setMicMuted(_controls.value.micMuted)
            }
        }

        if (after == VoiceState.Thinking) armThinkTimeout() else thinkTimeoutJob?.cancel()

        if (after == VoiceState.Reconnecting && before != VoiceState.Reconnecting) {
            scheduleReconnect()
        }

        if (after is VoiceState.Error || after == VoiceState.Idle || after == VoiceState.NotConfigured) {
            cancelTimers()
        }
    }

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(connectTimeoutMs)
            if (_state.value == VoiceState.Connecting || _state.value == VoiceState.Reconnecting) {
                dispatch(VoiceEvent.Failed(WearLiveVoiceCopy.CONNECT_TIMED_OUT))
            }
        }
    }

    private fun armThinkTimeout() {
        thinkTimeoutJob?.cancel()
        thinkTimeoutJob = scope.launch {
            delay(thinkTimeoutMs)
            if (_state.value == VoiceState.Thinking) {
                dispatch(VoiceEvent.Failed(WearLiveVoiceCopy.THINK_TIMED_OUT))
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnects) {
            dispatch(VoiceEvent.Failed(WearLiveVoiceCopy.CONNECTION_LOST))
            return
        }
        val attempt = reconnectAttempts
        reconnectAttempts++
        val backoff = (reconnectBaseDelayMs shl attempt).coerceAtMost(reconnectMaxDelayMs)
        scope.launch {
            delay(backoff)
            if (_state.value == VoiceState.Reconnecting && isActive) {
                armConnectTimeout()
                client.connect(dialUrl, dialToken)
            }
        }
    }

    private fun cancelTimers() {
        connectTimeoutJob?.cancel()
        thinkTimeoutJob?.cancel()
    }
}
