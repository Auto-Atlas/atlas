package app.eve.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eve.ui.components.EveButton
import app.eve.ui.components.EveButtonStyle
import app.eve.ui.theme.EveTheme

/**
 * The first-run wizard. Four calm steps (Welcome → Name → Voice → Why) then Done; all colors come
 * from EveTheme tokens. [onFinished] runs after the owner taps through the Done screen — the host
 * swaps in the real app.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EveTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .verticalScroll(rememberScrollState())
            .padding(EveTheme.spacing.gutterScreen),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        StepDots(current = state.step)

        when (state.step) {
            OnboardingStep.Welcome -> WelcomeStep(busy = state.busy) { viewModel.fromWelcome() }
            OnboardingStep.Name -> NameStep(state, viewModel)
            OnboardingStep.Voice -> VoiceStep(state, viewModel)
            OnboardingStep.Why -> WhyStep(state, viewModel)
            OnboardingStep.Done -> DoneStep(state) {
                viewModel.complete()
                onFinished()
            }
        }

        state.errorMessage?.let {
            Text(it, style = EveTheme.type.bodySm.copy(color = colors.danger))
        }
    }
}

@Composable
private fun StepDots(current: OnboardingStep) {
    val colors = EveTheme.colors
    // Done isn't a progress dot — it's the destination; show the 4 input steps.
    val steps = listOf(
        OnboardingStep.Welcome,
        OnboardingStep.Name,
        OnboardingStep.Voice,
        OnboardingStep.Why,
    )
    val activeIndex = steps.indexOf(current).let { if (it < 0) steps.size else it }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        steps.forEachIndexed { i, _ ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(EveTheme.shape.md)
                    .background(if (i <= activeIndex) colors.accent else colors.borderDefault),
            )
        }
    }
    Spacer(Modifier.height(EveTheme.spacing.s2))
}

@Composable
private fun StepHeader(title: String, subtitle: String?) {
    val colors = EveTheme.colors
    Text(title, style = EveTheme.type.titleXl.copy(color = colors.textPrimary))
    if (subtitle != null) {
        Text(subtitle, style = EveTheme.type.body.copy(color = colors.textSecondary))
    }
}

@Composable
private fun WelcomeStep(busy: Boolean, onContinue: () -> Unit) {
    StepHeader(
        title = "Hi, I'm EVE.",
        subtitle = "I'm your private chief-of-staff. Let's spend two minutes so I get to know you — " +
            "your name, your voice, and what matters to you.",
    )
    Spacer(Modifier.height(EveTheme.spacing.s2))
    EveButton(
        text = "Let's begin",
        onClick = onContinue,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NameStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeader(
        title = "What should I call you?",
        subtitle = "Your name — and a shorter nickname if you'd like me to use one.",
    )
    EveOnboardingField(
        value = state.name,
        onValueChange = viewModel::onNameChange,
        label = "Your name",
    )
    EveOnboardingField(
        value = state.nick,
        onValueChange = viewModel::onNickChange,
        label = "Nickname (optional)",
    )
    Spacer(Modifier.height(EveTheme.spacing.s2))
    EveButton(
        text = if (state.busy) "Saving…" else "Continue",
        onClick = viewModel::submitName,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VoiceStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val colors = EveTheme.colors
    val context = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onMicPermissionResult(granted) }

    fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    StepHeader(
        title = "Read these three lines so I know your voice.",
        subtitle = "Tap a line, read it out loud, and I'll capture it. Re-tap any line to record it again.",
    )

    if (state.micPermissionDenied) {
        Text(
            "I need microphone access to learn your voice. Enable it in Settings, then tap a line again.",
            style = EveTheme.type.bodySm.copy(color = colors.warning),
        )
    }

    ENROLL_SENTENCES.forEachIndexed { index, sentence ->
        val status = state.clipStatus.getOrElse(index) { ClipStatus.Empty }
        SentenceRow(
            sentence = sentence,
            status = status,
            onRecord = {
                if (hasMic()) {
                    viewModel.recordClip(index)
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }

    Spacer(Modifier.height(EveTheme.spacing.s2))
    EveButton(
        text = if (state.busy) "Learning your voice…" else "Continue",
        onClick = viewModel::submitVoice,
        enabled = !state.busy && viewModel.allClipsRecorded(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SentenceRow(sentence: String, status: ClipStatus, onRecord: () -> Unit) {
    val colors = EveTheme.colors
    val (ringColor, iconTint) = when (status) {
        ClipStatus.Recorded -> colors.accent to colors.accent
        ClipStatus.Recording -> colors.warning to colors.warning
        ClipStatus.Empty -> colors.borderStrong to colors.textSecondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .padding(EveTheme.spacing.padCard),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s4),
    ) {
        Text(
            sentence,
            style = EveTheme.type.body.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    when (status) {
                        ClipStatus.Recorded -> colors.accentSoft
                        ClipStatus.Recording -> colors.warningSoft
                        ClipStatus.Empty -> colors.surfaceRaised2
                    },
                )
                .border(EveTheme.layout.borderHairline, ringColor, CircleShape)
                .then(
                    if (status == ClipStatus.Recording) Modifier
                    else Modifier.clickable(onClickLabel = "Record", onClick = onRecord),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                ClipStatus.Recording -> CircularProgressIndicator(
                    color = iconTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                ClipStatus.Recorded -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "Recorded",
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
                ClipStatus.Empty -> Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Record this line",
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun WhyStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeader(
        title = "What gets you up in the morning?",
        subtitle = "Up to three lines. I'll keep them close and remind you when it counts. You can skip this.",
    )
    state.whys.forEachIndexed { index, line ->
        EveOnboardingField(
            value = line,
            onValueChange = { viewModel.onWhyChange(index, it) },
            label = "Why #${index + 1}",
            singleLine = false,
        )
    }
    Spacer(Modifier.height(EveTheme.spacing.s2))
    EveButton(
        text = if (state.busy) "Saving…" else "Save & finish",
        onClick = viewModel::submitWhys,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    EveButton(
        text = "Skip for now",
        style = EveButtonStyle.Subtle,
        onClick = viewModel::skipWhys,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DoneStep(state: OnboardingUiState, onEnter: () -> Unit) {
    val colors = EveTheme.colors
    val who = state.nick.trim().ifBlank { state.name.trim() }
    StepHeader(
        title = if (who.isNotBlank()) "You're all set, $who." else "You're all set.",
        subtitle = "I know your name, your voice, and what matters to you. Let's get to work.",
    )
    Spacer(Modifier.height(EveTheme.spacing.s2))
    EveButton(
        text = "Enter EVE",
        onClick = onEnter,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Token-styled text field shared by the Name / Why steps. */
@Composable
private fun EveOnboardingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
) {
    val colors = EveTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.accent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.borderDefault,
            focusedLabelColor = colors.textSecondary,
            unfocusedLabelColor = colors.textTertiary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
