package app.eve.ui.talk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eve.ui.components.EveSwitch
import app.eve.ui.components.NeuralBrain
import app.eve.ui.components.orbContentDescription
import app.eve.ui.theme.EveTheme
import app.eve.voice.VoiceState

/**
 * The Talk screen — a tap-to-toggle conversational voice session over native WebRTC (spec §3).
 * Tap the orb to connect; the floor is governed by phone_bot's server-side VAD; tap again to hang
 * up; while EVE speaks, tap to interrupt (barge-in). The orb never animates speech over silence —
 * a MediaStalled → NoAudio shows the honest "connected but no audio" state.
 *
 * RECORD_AUDIO is requested on the first tap with a verbatim rationale; soft-deny re-asks in-app;
 * permanent-deny deep-links to Settings.
 */
@Composable
fun TalkScreen(viewModel: TalkViewModel, modifier: Modifier = Modifier, autoStart: Boolean = false) {
    val colors = EveTheme.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val configured by viewModel.configured.collectAsStateWithLifecycle()
    val savedOverride by viewModel.voiceUrlOverride.collectAsStateWithLifecycle()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val visual by viewModel.visual.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshThinking() }

    var showRationale by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showRationale = false
            viewModel.start()
        } else {
            // We can't reliably distinguish soft vs permanent deny without the Activity's
            // shouldShowRationale; surface the Settings path as the recoverable route.
            permanentlyDenied = true
            showRationale = true
        }
    }

    // Haptic turn-boundary cues (cross-modal, survives muted audio / reduced motion).
    HapticTurnCues(state, haptics)

    // Morning-ritual auto-connect: the 5 AM alarm opens straight onto Talk and we connect once,
    // through the SAME mic-permission gate as a tap. If the mic isn't granted yet, surface the
    // rationale instead of failing silently. Guarded so it only fires on the initial idle entry.
    androidx.compose.runtime.LaunchedEffect(autoStart) {
        if (autoStart && state == VoiceState.Idle && configured) {
            if (hasMic()) viewModel.start() else showRationale = true
        }
    }

    fun onOrbTap() {
        when (state) {
            VoiceState.Idle, is VoiceState.Error -> {
                if (hasMic()) {
                    viewModel.start()
                } else {
                    showRationale = true
                }
            }
            VoiceState.Speaking, VoiceState.NoAudio -> viewModel.interrupt()
            else -> viewModel.hangUp()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .padding(EveTheme.spacing.gutterScreen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Talk to EVE", style = EveTheme.type.titleXl.copy(color = colors.textPrimary))
        Spacer(Modifier.height(EveTheme.spacing.s2))

        // Owner-tier banner — phone_bot asserts owner for the phone session (spec §3/§6).
        OwnerBanner()

        Spacer(Modifier.height(EveTheme.spacing.s3))

        // Thinking toggle (Epic T) — flip EVE's reasoning right here on the Talk screen.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
        ) {
            Text("Thinking", style = EveTheme.type.bodySm.copy(color = colors.textSecondary))
            EveSwitch(
                checked = thinkingEnabled,
                onCheckedChange = { viewModel.setThinking(it) },
                enabled = true,
            )
        }

        Spacer(Modifier.height(EveTheme.spacing.s8))

        Box(
            modifier = Modifier.clickable(enabled = configured) { onOrbTap() },
            contentAlignment = Alignment.Center,
        ) {
            NeuralBrain(state = state, working = activity.isWorking)
        }

        Spacer(Modifier.height(EveTheme.spacing.s6))

        // State label + icon carry 100% of the signal (never color/motion alone). While a tool is
        // running, the label defers to the tool-status line below so it isn't redundant.
        Text(
            text = if (activity.isWorking) "Working on it…" else orbContentDescription(state),
            style = EveTheme.type.headline.copy(
                color = if (activity.isWorking) colors.success else labelColor(state, colors),
            ),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(EveTheme.spacing.s3))

        // ---- Surfaced visual card (surface_visual): EVE SHOWS something. Sits above the tool/
        // delegation surfaces and never blocks the voice controls; dismissible. ----
        visual?.let { card ->
            VisualCardView(card = card, onDismiss = { viewModel.dismissVisual() })
            Spacer(Modifier.height(EveTheme.spacing.s3))
        }

        // ---- The transformative tool-call surface (Android port of the desktop stage) ----
        activity.tool?.let { tool ->
            ToolStatusLine(tool)
            Spacer(Modifier.height(EveTheme.spacing.s3))
        }
        activity.delegation?.let { delegation ->
            DelegationTicker(delegation)
            Spacer(Modifier.height(EveTheme.spacing.s3))
        }

        // In-call controls (mic mute / speakerphone) — only while a live session is up.
        if (isSessionActive(state)) {
            InCallControls(
                controls = controls,
                onToggleMute = { viewModel.toggleMute() },
                onToggleSpeakerphone = { viewModel.toggleSpeakerphone() },
                onHangUp = { viewModel.hangUp() },
            )
            Spacer(Modifier.height(EveTheme.spacing.s3))
        }

        // Honest connection status line.
        when {
            !configured -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Voice isn't configured. Set the voice URL (e.g. https://host.ts.net:8444 " +
                        "on the tailnet, or http://10.0.2.2:8789 on the emulator).",
                    style = EveTheme.type.bodySm.copy(color = colors.warning),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(EveTheme.spacing.s3))
                var override by remember(savedOverride) { mutableStateOf(savedOverride) }
                OutlinedTextField(
                    value = override,
                    onValueChange = { override = it },
                    label = { Text("Voice URL override") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(EveTheme.spacing.s2))
                Button(
                    onClick = { viewModel.saveVoiceUrlOverride(override) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save voice URL") }
            }
            state == VoiceState.NoAudio -> Text(
                "Connected, but no audio is getting through — check your network.",
                style = EveTheme.type.bodySm.copy(color = colors.warning),
                textAlign = TextAlign.Center,
            )
            state is VoiceState.Error -> Text(
                (state as VoiceState.Error).message + "  •  Tap the orb to retry.",
                style = EveTheme.type.bodySm.copy(color = colors.danger),
                textAlign = TextAlign.Center,
            )
            host != null -> Text(
                "Connected to EVE",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(EveTheme.spacing.s3))

        // The single-session notice (connecting ends your other voice session).
        if (state == VoiceState.Idle) {
            Text(
                viewModel.endsOtherSessionNotice,
                style = EveTheme.type.caption.copy(color = colors.textTertiary),
                textAlign = TextAlign.Center,
            )
        }

        // Interrupt affordance while EVE speaks.
        if (state == VoiceState.Speaking) {
            Spacer(Modifier.height(EveTheme.spacing.s2))
            Text(
                "Tap the orb to interrupt.",
                style = EveTheme.type.caption.copy(color = colors.textSecondary),
                textAlign = TextAlign.Center,
            )
        }

        if (showRationale) {
            Spacer(Modifier.height(EveTheme.spacing.s5))
            MicRationale(
                permanentlyDenied = permanentlyDenied,
                onAllow = {
                    if (permanentlyDenied) {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
    }
}

/** True while a voice session is live (connected through to teardown), so controls are actionable. */
private fun isSessionActive(state: VoiceState): Boolean = when (state) {
    VoiceState.Idle, VoiceState.Connecting, is VoiceState.Error -> false
    else -> true
}

/** Mic-mute + speakerphone toggles, shown only during a live call (spec: hands-free + privacy).
 *  Round icon buttons (call-UI style): a red mic-off when muted, an accent-lit volume icon for
 *  loudspeaker vs an earpiece icon. The caption under each carries the state for a11y / non-color. */
@Composable
internal fun InCallControls(
    controls: app.eve.voice.VoiceControls,
    onToggleMute: () -> Unit,
    onToggleSpeakerphone: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        ControlIconButton(
            // "Local silence" — this only gates YOUR mic locally (EVE stops hearing you). It is NOT
            // an interrupt/barge-in (that real-time floor-reclaim is a later phase). Naming it "mute"
            // overclaimed; the label reflects what the control actually does today.
            icon = if (controls.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = if (controls.micMuted) "Silenced" else "Local silence",
            iconTint = if (controls.micMuted) colors.danger else colors.textSecondary,
            background = if (controls.micMuted) colors.dangerSoft else colors.surfaceRaised,
            contentDescription = if (controls.micMuted) {
                "Mic silenced locally. Tap to let EVE hear you again."
            } else {
                "Mic on. Tap to silence your mic locally."
            },
            onClick = onToggleMute,
        )
        Spacer(Modifier.width(EveTheme.spacing.s6))
        // Always-visible kill switch: ends the session instantly if EVE ever loops or misbehaves.
        ControlIconButton(
            icon = Icons.Filled.CallEnd,
            label = "End",
            iconTint = colors.danger,
            background = colors.dangerSoft,
            contentDescription = "End the conversation now.",
            onClick = onHangUp,
        )
        Spacer(Modifier.width(EveTheme.spacing.s6))
        ControlIconButton(
            icon = if (controls.speakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PhoneInTalk,
            label = if (controls.speakerphoneOn) "Speaker" else "Earpiece",
            iconTint = if (controls.speakerphoneOn) colors.accent else colors.textSecondary,
            background = if (controls.speakerphoneOn) colors.accentSoft else colors.surfaceRaised,
            contentDescription =
                if (controls.speakerphoneOn) "Loudspeaker on. Tap for earpiece." else "Earpiece. Tap for loudspeaker.",
            onClick = onToggleSpeakerphone,
        )
    }
}

@Composable
private fun ControlIconButton(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    background: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClickLabel = contentDescription) { onClick() }
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(EveTheme.spacing.s1))
        Text(label, style = EveTheme.type.caption.copy(color = colors.textSecondary), textAlign = TextAlign.Center)
    }
}

@Composable
private fun OwnerBanner() {
    val colors = EveTheme.colors
    Box(
        Modifier
            .clip(EveTheme.shape.pill)
            .background(colors.tier.owner.soft)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "Owner — full access",
            style = EveTheme.type.micro.copy(color = colors.tier.owner.fg),
        )
    }
}

@Composable
private fun MicRationale(permanentlyDenied: Boolean, onAllow: () -> Unit) {
    val colors = EveTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .padding(EveTheme.spacing.padCard),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "EVE needs your mic to hear you. Your audio goes only to your EVE on your private " +
                "tailnet — never to the cloud.",
            style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(EveTheme.spacing.s3))
        Text(
            text = if (permanentlyDenied) "Open Settings to enable the microphone" else "Allow microphone",
            style = EveTheme.type.label.copy(color = colors.accent),
            modifier = Modifier
                .clip(EveTheme.shape.pill)
                .background(colors.accentSoft)
                .clickable { onAllow() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Fires a haptic tick when the floor changes hands (you ⇄ EVE) — spec §3 a11y. */
@Composable
private fun HapticTurnCues(state: VoiceState, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    val key = when (state) {
        VoiceState.YourTurn -> "you"
        VoiceState.Speaking -> "eve"
        else -> null
    }
    androidx.compose.runtime.LaunchedEffect(key) {
        when (key) {
            "you" -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            "eve" -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

private fun labelColor(state: VoiceState, colors: app.eve.ui.theme.EveColorScheme) = when (state) {
    is VoiceState.Error -> colors.danger
    VoiceState.NoAudio -> colors.warning
    else -> colors.textPrimary
}
