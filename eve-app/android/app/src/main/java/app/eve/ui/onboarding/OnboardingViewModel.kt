package app.eve.ui.onboarding

import app.eve.ASSISTANT_NAME
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.onboarding.OnboardingState
import app.eve.onboarding.VoiceRecorder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The four wizard steps, in order. [Done] is the terminal confirmation. */
enum class OnboardingStep { Welcome, Name, Voice, Why, Done }

/** Per-clip recording status for the Voice step's three sentences. */
enum class ClipStatus { Empty, Recording, Recorded }

/**
 * The three short, phonetically-varied sentences the owner reads aloud. Neutral product copy —
 * nothing user-specific — so this stays multi-tenant clean.
 */
val ENROLL_SENTENCES: List<String> = listOf(
    "The quiet morning light feels brand new.",
    "I'm building something that lasts.",
    "Let's get to work and make today count.",
)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    // Name step
    val name: String = "",
    val nick: String = "",
    // Voice step — one status per sentence; the WAV bytes live in the VM until enroll.
    val clipStatus: List<ClipStatus> = List(ENROLL_SENTENCES.size) { ClipStatus.Empty },
    val micPermissionDenied: Boolean = false,
    // Why step
    val whys: List<String> = listOf("", "", ""),
    // Cross-cutting
    val busy: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Drives the first-run wizard. Calls the (already-live) server contract:
 *  - Name step  -> POST /v1/identity {user, nick}
 *  - Voice step -> records 3 mic clips, wraps each in WAV, base64, POST /v1/enroll
 *  - Why step   -> POST /v1/identity {whys}
 * then flips the local [OnboardingState] flag so the gate never shows again.
 *
 * Every network call goes through ApiResult and is reflected honestly: a failed write keeps the
 * user on the step with an error rather than advancing on a lie. The recorded WAVs are held only
 * in memory ([clipsWav]); nothing user-specific is hardcoded.
 */
class OnboardingViewModel(
    private val api: ApiClient,
    private val onboardingState: OnboardingState,
    appContext: Context,
    private val recorder: VoiceRecorder = VoiceRecorder(appContext),
    injectedScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = injectedScope ?: viewModelScope

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Recorded WAV bytes per sentence (null = not yet recorded). */
    private val clipsWav = arrayOfNulls<ByteArray>(ENROLL_SENTENCES.size)

    // ---- Navigation -------------------------------------------------------

    fun toWelcome() = goto(OnboardingStep.Welcome)
    fun toName() = goto(OnboardingStep.Name)

    fun onNameChange(value: String) = _state.update { it.copy(name = value, errorMessage = null) }
    fun onNickChange(value: String) = _state.update { it.copy(nick = value, errorMessage = null) }

    /** Welcome -> Name has no network; just advance. */
    fun fromWelcome() = goto(OnboardingStep.Name)

    /** Name -> Voice: persist the name/nick first, advance only on success. */
    fun submitName() {
        val name = _state.value.name.trim()
        if (name.isBlank()) {
            _state.update { it.copy(errorMessage = "Tell me your name so I know who I'm talking to.") }
            return
        }
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(busy = true, errorMessage = null) }
            when (val r = api.setIdentity(user = name, nick = _state.value.nick.trim().ifBlank { null })) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, step = OnboardingStep.Voice) }
                is ApiResult.Err -> _state.update {
                    it.copy(busy = false, errorMessage = "Couldn't save your name — ${describe(r.error)}")
                }
            }
        }
    }

    // ---- Voice step -------------------------------------------------------

    fun onMicPermissionResult(granted: Boolean) {
        _state.update { it.copy(micPermissionDenied = !granted) }
    }

    /** Record (or re-record) the clip for [index]. Captures from the mic, then wraps to WAV. */
    fun recordClip(index: Int) {
        if (index !in ENROLL_SENTENCES.indices) return
        if (_state.value.clipStatus.any { it == ClipStatus.Recording }) return // one at a time
        scope.launch(CRASH_GUARD) {
            setClipStatus(index, ClipStatus.Recording)
            _state.update { it.copy(errorMessage = null) }
            val wav = try {
                recorder.record()
            } catch (t: Throwable) {
                Log.w("OnboardingViewModel", "record failed: ${t.message}")
                null
            }
            if (wav == null) {
                clipsWav[index] = null
                setClipStatus(index, ClipStatus.Empty)
                _state.update { it.copy(errorMessage = "Didn't catch that — hold while you read, then tap again.") }
            } else {
                clipsWav[index] = wav
                setClipStatus(index, ClipStatus.Recorded)
            }
        }
    }

    /** Whether all three clips are captured and enroll can run. */
    fun allClipsRecorded(): Boolean = clipsWav.all { it != null }

    /** Voice -> Why: enroll the three WAV clips, advance only on success. */
    fun submitVoice() {
        val clips = clipsWav.toList()
        if (clips.any { it == null }) {
            _state.update { it.copy(errorMessage = "Read all three lines so I can learn your voice.") }
            return
        }
        val name = _state.value.name.trim().ifBlank { _state.value.nick.trim() }
        if (name.isBlank()) {
            _state.update { it.copy(errorMessage = "Go back and tell me your name first.") }
            return
        }
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(busy = true, errorMessage = null) }
            val b64 = clips.map { Base64.encodeToString(it, Base64.NO_WRAP) }
            when (val r = api.enroll(name = name, tier = "owner", clipsB64 = b64)) {
                is ApiResult.Ok -> _state.update { it.copy(busy = false, step = OnboardingStep.Why) }
                is ApiResult.Err -> _state.update {
                    it.copy(busy = false, errorMessage = "Couldn't enroll your voice — ${describe(r.error)}")
                }
            }
        }
    }

    // ---- Why step ---------------------------------------------------------

    fun onWhyChange(index: Int, value: String) {
        if (index !in _state.value.whys.indices) return
        _state.update { st ->
            st.copy(whys = st.whys.toMutableList().also { it[index] = value }, errorMessage = null)
        }
    }

    /** Why -> Done: persist whys (optional) then finish. Empty whys still finishes cleanly. */
    fun submitWhys() {
        val lines = _state.value.whys.map { it.trim() }.filter { it.isNotBlank() }
        scope.launch(CRASH_GUARD) {
            _state.update { it.copy(busy = true, errorMessage = null) }
            if (lines.isEmpty()) {
                // Nothing to save — just finish.
                finish()
                return@launch
            }
            when (val r = api.setIdentity(whys = lines)) {
                is ApiResult.Ok -> finish()
                is ApiResult.Err -> _state.update {
                    it.copy(busy = false, errorMessage = "Couldn't save that — ${describe(r.error)}")
                }
            }
        }
    }

    /** Skip the whys entirely (they're optional) and finish. */
    fun skipWhys() {
        scope.launch(CRASH_GUARD) { finish() }
    }

    private fun finish() {
        _state.update { it.copy(busy = false, step = OnboardingStep.Done) }
    }

    /** Called from the Done screen's CTA — flip the persisted gate, then the host swaps in the app. */
    fun complete() {
        onboardingState.markComplete()
    }

    // ---- internals --------------------------------------------------------

    private fun goto(step: OnboardingStep) = _state.update { it.copy(step = step, errorMessage = null) }

    private fun setClipStatus(index: Int, status: ClipStatus) {
        _state.update { st ->
            st.copy(clipStatus = st.clipStatus.toMutableList().also { it[index] = status })
        }
    }

    private fun describe(error: ApiError): String = when (error) {
        is ApiError.NotConfigured -> "not connected to $ASSISTANT_NAME yet"
        is ApiError.Offline -> "you're off the tailnet"
        is ApiError.Unauthorized -> "the app token is invalid"
        is ApiError.NotFound -> "the server route is missing"
        is ApiError.AlreadyResolved -> "it was already set"
        is ApiError.Http -> "server error ${error.status}"
        is ApiError.Decode -> "the server sent something unexpected"
        is ApiError.Unknown -> error.message
    }

    private companion object {
        val CRASH_GUARD = CoroutineExceptionHandler { _, t ->
            Log.e("OnboardingViewModel", "uncaught in scope; suppressed", t)
        }
    }
}
