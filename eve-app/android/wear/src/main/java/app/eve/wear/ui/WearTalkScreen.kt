package app.eve.wear.ui

import app.eve.ASSISTANT_NAME
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.eve.wear.talk.TalkTurn
import app.eve.wear.talk.VoiceState
import app.eve.wear.talk.WearTalkCopy
import app.eve.wear.talk.WearTalkPhase

/**
 * The push-to-talk screen. In v2 the big round mic button drives the NATIVE path: tap to record on the
 * wrist ([onStartRecording]), tap again (or hit the 15s cap) to stop and send; Atlas answers in her own
 * voice, played by the VM's [app.eve.wear.talk.PcmPlayer]. The reply TEXT always renders from [phase]
 * regardless of [voiceState] (voice failure never hides text). The old RecognizerIntent path stays as
 * an explicit, honestly-labelled fallback chip ([WearTalkCopy.FALLBACK_LABEL]) whose text-only reply
 * is spoken via the on-watch TTS ([onSpeakReply]) — only when [WearTalkPhase.Replied.spokenOnWatch] is
 * false, so a native reply is never double-spoken.
 *
 * RECORD_AUDIO is requested inline the first time the mic is tapped; a denial is a named, visible
 * failure ([onMicPermissionDenied]). The fallback path pre-checks
 * [SpeechRecognizer.isRecognitionAvailable] and catches [ActivityNotFoundException]
 * ([onSpeechUnavailable]).
 */
@Composable
fun WearTalkScreen(
    phase: WearTalkPhase,
    transcript: List<TalkTurn>,
    voiceState: VoiceState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMicPermissionDenied: () -> Unit,
    onAsk: (String) -> Unit,
    onSpeechUnavailable: () -> Unit,
    onSpeakReply: (String) -> Unit,
    onPrewarmVoice: () -> Unit,
    onShutdownVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isRecording = phase is WearTalkPhase.Recording

    // Warm the FALLBACK voice engine on entry (Wear cold-boot TTS can take ~10s); release on disposal.
    DisposableEffect(Unit) {
        onPrewarmVoice()
        onDispose { onShutdownVoice() }
    }

    // Speak each fallback (Google) reply exactly once. A native reply is already spoken as PCM on the
    // wrist (spokenOnWatch=true) — never TTS it again.
    val replied = phase as? WearTalkPhase.Replied
    LaunchedEffect(replied) {
        if (replied != null && !replied.spokenOnWatch) onSpeakReply(replied.text)
    }

    // RECORD_AUDIO: request inline on the first mic tap; a denial is a named failure, never silent.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartRecording() else onMicPermissionDenied()
    }

    fun onMicTap() {
        if (isRecording) {
            onStopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStartRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Fallback (Google) STT launcher.
    val sttLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            onAsk(spoken) // blank is rejected by the VM ("Didn't catch that")
        }
    }

    fun launchFallbackStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onSpeechUnavailable()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to $ASSISTANT_NAME")
        }
        try {
            sttLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            onSpeechUnavailable()
        }
    }

    Scaffold(
        timeText = { TimeText() },
        modifier = modifier.fillMaxSize().background(WearEveColors.background),
    ) {
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Mic is disabled only while the audio is mid-flight to the phone / brain (Sending/Thinking).
            val micEnabled = phase !is WearTalkPhase.Sending && phase !is WearTalkPhase.ThinkingAwaitingReply
            item { MicButton(isRecording = isRecording, enabled = micEnabled, onClick = ::onMicTap) }
            item { PhaseLine(phase) }
            item { VoiceNote(voiceState) }
            item { FallbackChip(onClick = ::launchFallbackStt) }
            transcript.reversed().forEach { turn ->
                item(key = "${turn.speaker}-${turn.atMs}-${turn.text.hashCode()}") { TranscriptBubble(turn) }
            }
        }
    }
}

@Composable
private fun MicButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (isRecording) WearEveColors.danger else WearEveColors.accent,
        ),
        modifier = Modifier.size(72.dp).clip(CircleShape).testTag("talkMic"),
    ) {
        Text(
            // A stop glyph while recording makes tap-to-stop obvious; the mic otherwise.
            text = if (isRecording) "◼" else "🎙",
            color = WearEveColors.background,
            style = MaterialTheme.typography.title1,
        )
    }
}

/** The current turn's honest headline: recording countdown, in-flight, the reply, or a named failure. */
@Composable
private fun PhaseLine(phase: WearTalkPhase) {
    when (phase) {
        is WearTalkPhase.Idle ->
            CenterText("Tap the mic to talk to $ASSISTANT_NAME", WearEveColors.textSecondary)
        is WearTalkPhase.Recording -> {
            // Visible countdown in the final 5s; "Listening…" before that.
            val label = if (phase.remainingSeconds <= COUNTDOWN_FROM_SECONDS) {
                WearTalkCopy.countdown(phase.remainingSeconds)
            } else {
                WearTalkCopy.RECORDING
            }
            CenterText(label, WearEveColors.danger)
        }
        is WearTalkPhase.Sending ->
            InlineSpinner(WearTalkCopy.SENDING)
        is WearTalkPhase.ThinkingAwaitingReply ->
            InlineSpinner(WearTalkCopy.THINKING)
        is WearTalkPhase.Replied ->
            CenterText(phase.text, WearEveColors.textPrimary, weight = FontWeight.Normal)
        is WearTalkPhase.TalkFailure ->
            CenterText(phase.message, WearEveColors.danger)
    }
}

/** The small voice-output note. Never the reply itself — the reply always renders in [PhaseLine]. */
@Composable
private fun VoiceNote(voiceState: VoiceState) {
    val note = when (voiceState) {
        is VoiceState.WarmingUp -> WearTalkCopy.WARMING_UP_VOICE
        is VoiceState.Failed -> voiceState.message
        VoiceState.Idle, VoiceState.Speaking -> null
    }
    if (note != null) {
        Text(
            text = note,
            color = WearEveColors.textTertiary,
            style = MaterialTheme.typography.caption3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The explicit, honestly-labelled fallback to the old Google recognizer path. */
@Composable
private fun FallbackChip(onClick: () -> Unit) {
    CompactChip(
        onClick = onClick,
        label = { Text(WearTalkCopy.FALLBACK_LABEL) },
        colors = ChipDefaults.chipColors(
            backgroundColor = WearEveColors.accentSoft,
            contentColor = WearEveColors.textSecondary,
        ),
        modifier = Modifier.padding(top = 4.dp).testTag("talkFallback"),
    )
}

@Composable
private fun TranscriptBubble(turn: TalkTurn) {
    val isYou = turn.speaker == TalkTurn.Speaker.You
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (isYou) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = if (isYou) "You" else "$ASSISTANT_NAME",
            color = if (isYou) WearEveColors.textTertiary else WearEveColors.accent,
            style = MaterialTheme.typography.caption3,
        )
        Text(
            text = turn.text,
            color = WearEveColors.textSecondary,
            style = MaterialTheme.typography.caption1,
        )
    }
}

@Composable
private fun InlineSpinner(label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = WearEveColors.accent,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterText(text: String, color: Color, weight: FontWeight = FontWeight.SemiBold) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.caption1,
            fontWeight = weight,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The two voice entry chips for the approvals list top: "Live" (the v3 real call — orb + streaming
 * voice) and "Voice note" (the v2 push-to-talk turn, kept as the explicit fallback). [onOpenLive]
 * navigates to the live route; [onOpenTalk] to the PTT route.
 */
@Composable
internal fun VoiceEntryChips(
    onOpenLive: () -> Unit,
    onOpenTalk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        // "Live" — the primary real-call entry (filled accent so it reads as the headline action).
        CompactChip(
            onClick = onOpenLive,
            label = { Text("Live") },
            colors = ChipDefaults.chipColors(
                backgroundColor = WearEveColors.accent,
                contentColor = WearEveColors.background,
            ),
            modifier = Modifier.testTag("liveEntry"),
        )
        // "Voice note" — the v2 push-to-talk fallback (renamed from "Talk to Atlas").
        CompactChip(
            onClick = onOpenTalk,
            label = { Text("Voice note") },
            colors = ChipDefaults.chipColors(
                backgroundColor = WearEveColors.accentSoft,
                contentColor = WearEveColors.accent,
            ),
            modifier = Modifier.testTag("talkEntry"),
        )
    }
}

/** The final N seconds of a recording show a visible countdown. */
private const val COUNTDOWN_FROM_SECONDS = 5
