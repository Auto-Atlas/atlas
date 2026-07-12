package app.eve.wear.talk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Real [ReplySpeaker] over the on-watch [TextToSpeech] (default engine). Speaks with USAGE_ASSISTANT /
 * CONTENT_TYPE_SPEECH audio attributes and takes TRANSIENT_MAY_DUCK audio focus for the duration of a
 * reply, releasing it in onDone/onError.
 *
 * House rules honored here:
 *  - Voice failure never hides the text: [speak] only drives [state]; the SCREEN renders the reply
 *    text independently. Every failure (init failed, no language data, speak error) becomes a loud
 *    [VoiceState.Failed] with copy from [WearTalkCopy] — never a silent swallow.
 *  - Cold-boot warm-up: a reply requested before onInit lands is QUEUED ([pendingText]) and spoken
 *    once the engine is ready; the state is [VoiceState.WarmingUp] meanwhile.
 */
class TtsReplySpeaker(context: Context) : ReplySpeaker {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    /** A reply requested while the engine was still warming; spoken once ready. */
    private var pendingText: String? = null

    override fun prewarm() {
        if (tts != null) return
        _state.value = VoiceState.WarmingUp
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.e(TAG, "TTS init failed (status=$status)")
            _state.value = VoiceState.Failed(WearTalkCopy.VOICE_FAILED)
            return
        }
        val lang = engine.setLanguage(Locale.getDefault())
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "TTS language unavailable (result=$lang)")
            _state.value = VoiceState.Failed(WearTalkCopy.VOICE_FAILED)
            return
        }
        engine.setAudioAttributes(audioAttributes)
        engine.setOnUtteranceProgressListener(progressListener)
        ready = true
        // Flush a reply that arrived while warming up.
        val queued = pendingText
        pendingText = null
        if (queued != null) speakNow(engine, queued) else _state.value = VoiceState.Idle
    }

    override fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            // speak() before prewarm() — warm now and queue this reply.
            pendingText = text
            prewarm()
            return
        }
        if (!ready) {
            // Still initializing — queue; onInit will speak it.
            pendingText = text
            _state.value = VoiceState.WarmingUp
            return
        }
        speakNow(engine, text)
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        requestFocus()
        _state.value = VoiceState.Speaking
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR")
            abandonFocus()
            _state.value = VoiceState.Failed(WearTalkCopy.VOICE_FAILED)
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _state.value = VoiceState.Speaking
        }

        override fun onDone(utteranceId: String?) {
            abandonFocus()
            _state.value = VoiceState.Idle
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            abandonFocus()
            _state.value = VoiceState.Failed(WearTalkCopy.VOICE_FAILED)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            abandonFocus()
            _state.value = VoiceState.Failed(WearTalkCopy.VOICE_FAILED)
        }
    }

    override fun shutdown() {
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingText = null
        _state.value = VoiceState.Idle
    }

    // minSdk 26 — AudioFocusRequest is always available; no version guard needed.
    private fun requestFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = "TtsReplySpeaker"
        const val UTTERANCE_ID = "eve_talk_reply"
    }
}
