package app.eve.ui.talk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiResult
import app.eve.di.AppContainer
import app.eve.voice.VoiceControls
import app.eve.voice.VoiceController
import app.eve.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the Talk session. Builds the native [app.eve.voice.WebRtcVoiceClient] (Context comes from
 * the [AppContainer]) lazily on the first start, wraps it in a [VoiceController], and re-exposes
 * the controller's [VoiceState] plus the host string and the "ends your other session" notice.
 *
 * The mic-permission flow is owned by the screen (Android permission APIs need an Activity); the
 * VM only starts the session once permission is granted.
 */
class TalkViewModel(
    private val container: AppContainer,
    injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** In-call controls (mic mute / speakerphone) the Talk screen renders while a session is live. */
    private val _controls = MutableStateFlow(VoiceControls())
    val controls: StateFlow<VoiceControls> = _controls.asStateFlow()

    private val _host = MutableStateFlow<String?>(null)
    val host: StateFlow<String?> = _host.asStateFlow()

    /** Null until a session is created; surfaces the "not configured" case to the screen. */
    private val _configured = MutableStateFlow(true)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    val endsOtherSessionNotice: String =
        "Connecting will end your active phone or desktop voice session."

    /** The current voice-URL override (e.g. `http://10.0.2.2:8789` for the emulator), so the
     *  Talk screen can show + edit it. Empty = derive from the approval base URL. */
    private val _voiceUrlOverride = MutableStateFlow("")
    val voiceUrlOverride: StateFlow<String> = _voiceUrlOverride.asStateFlow()

    /**
     * The live tool-call / delegation activity, folded from the republished /v1/stream feed. Drives
     * the transformative UI (orb "working" morph, the delegation ticker, the tool-status line) —
     * the Android analogue of the desktop NeuralBrain's delegation state.
     */
    private val _activity = MutableStateFlow(LiveActivity.IDLE)
    val activity: StateFlow<LiveActivity> = _activity.asStateFlow()

    /**
     * The latest surfaced visual card (surface_visual) — a desktop screenshot, an image, or a note
     * EVE chose to SHOW. Owned by the container's [app.eve.visual.VisualHub] (fed by the
     * StreamService), re-exposed here so the Talk screen renders it without owning the fetch.
     */
    val visual: StateFlow<app.eve.visual.VisualCard?> = container.visualHub.state

    /** Dismiss the current visual card (also frees its bitmap). */
    fun dismissVisual() = container.visualHub.dismiss()

    private var controller: VoiceController? = null

    init {
        scope.launch {
            _voiceUrlOverride.value = container.settings.voiceUrlOverride.first()
            _host.value = container.voiceUrl()
            _configured.value = container.voiceUrl() != null
        }
        observeActivity()
    }

    /**
     * Subscribe to the live event feed and fold it into [_activity]. The feed shares the app's
     * `/v1/stream` socket (the same one approvals use); `approval_api` republishes the voice loop's
     * tool/delegation events onto it. The cold flow completes when the socket drops or isn't
     * configured yet — so we reconnect on a fixed backoff for as long as the screen's VM is alive.
     */
    private fun observeActivity() {
        scope.launch {
            while (isActive) {
                try {
                    container.streamClient.events().collect { e ->
                        _activity.value = reduceActivity(_activity.value, e, System.currentTimeMillis())
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // dropped / not-configured — fall through to the backoff and retry
                }
                if (!isActive) break
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    /** Persist the voice-URL override and refresh the resolved host (drives "configured"). */
    fun saveVoiceUrlOverride(url: String) {
        scope.launch {
            val v = url.trim()
            container.settings.setVoiceUrlOverride(v)
            _voiceUrlOverride.value = v
            _host.value = container.voiceUrl()
            _configured.value = container.voiceUrl() != null
        }
    }

    /** Called by the screen after RECORD_AUDIO is granted (and on retry from Error). */
    fun start() {
        scope.launch {
            val existing = controller
            if (existing != null) {
                existing.start()
                return@launch
            }
            val client = container.newVoiceClient()
            if (client == null) {
                _configured.value = false
                return@launch
            }
            _configured.value = true
            _host.value = container.voiceUrl()
            val c = VoiceController(client, scope)
            controller = c
            // Mirror the controller's state + controls into our flows.
            scope.launch { c.state.collect { _state.value = it } }
            scope.launch { c.controls.collect { _controls.value = it } }
            c.start()
        }
    }

    fun hangUp() {
        controller?.hangUp()
        controller = null
        _state.value = VoiceState.Idle
        _controls.value = VoiceControls()
        _activity.value = LiveActivity.IDLE
    }

    fun interrupt() = controller?.interrupt()

    /** Mute/unmute your mic during a call. */
    fun toggleMute() = controller?.toggleMute()

    /** Switch between loudspeaker and earpiece during a call. */
    fun toggleSpeakerphone() = controller?.toggleSpeakerphone()

    // Thinking toggle (Epic T) — same shared setting as the Status screen + voice, surfaced here
    // on the Talk screen so you can flip EVE's reasoning right where you're talking to her.
    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    fun refreshThinking() {
        scope.launch {
            when (val r = container.statusRepository.health()) {
                is ApiResult.Ok -> _thinkingEnabled.value = r.value.thinkingEnabled
                is ApiResult.Err -> {}
            }
        }
    }

    fun setThinking(on: Boolean) {
        scope.launch {
            when (val r = container.statusRepository.setThinking(on)) {
                is ApiResult.Ok -> _thinkingEnabled.value = r.value
                is ApiResult.Err -> {}
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        controller?.hangUp()
    }

    private companion object {
        /** Backoff between live-feed reconnect attempts (the socket drops or starts unconfigured). */
        const val RECONNECT_DELAY_MS = 3_000L
    }
}
