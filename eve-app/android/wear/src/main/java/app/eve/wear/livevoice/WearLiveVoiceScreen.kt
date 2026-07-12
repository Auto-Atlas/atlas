package app.eve.wear.livevoice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.eve.wear.ui.WearEveColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The LIVE-VOICE screen — the real call, and the whole screen is the orb (2026-07-11 wrist UX:
 * "app opens → call auto-starts → the orb is the screen; it doesn't even have to say it"). When a
 * door is configured and the mic is granted the call dials itself on entry ([onAutoStart]); the orb
 * then morphs through EVE's REAL server states with NO labels, buttons, or status text on the happy
 * path.
 *
 * The orb's press contract, disambiguated by DURATION (one press fires at most one action):
 *  - quick tap (released inside [ORB_TAP_MAX_MS]) → [onOrbTap]: the talk/mute toggle (mute turns
 *    the orb RED — red = muted, blue = live — truthfully gating the outbound mic; while she speaks
 *    it interrupts her and leaves the mic live);
 *  - full [ORB_HOLD_TO_END_MS] hold (every live state, Connecting included) → [onOrbLongPress]: the
 *    deliberate END CALL, with a haptic tick at the threshold; the ring eases down while held;
 *  - released between the two windows → a CANCELLED hold: NOTHING fires, the ring springs back.
 *
 * The gesture core is HoldToApproveWear's, ported: raw pointer primitives
 * (awaitEachGesture/waitForUpOrCancellation) resume INLINE during MotionEvent dispatch, and the gate
 * re-checks the pressed flag at fire time — the exact structure that closed the frame-starved race
 * where a released short press could still fire a delayed gate (found on device 2026-07-10). The
 * tap-window timer errs the OPPOSITE, benign way: on a starved UI a slightly-long press may still
 * count as the (reversible) mute toggle — but a released press can never end the call.
 *
 * The honesty spine holds: states the owner must ACT on — no door, a named error, or "connected but
 * no audio" — still show their exact copy (and NoAudio still shows EVE's transcript, so her words
 * reach the wrist even when her voice can't). Nothing is faked, no state is silent.
 *
 * Stateless over the ViewModel: it renders the handed [state] / [controls] / [transcript] and calls
 * back. RECORD_AUDIO is requested on auto-start (and inline on a connect tap); a denial is a NAMED
 * failure ([onMicPermissionDenied]), never a silent dead mic.
 */
@Composable
fun WearLiveVoiceScreen(
    state: VoiceState,
    controls: VoiceControls,
    transcript: List<LiveTranscriptLine>,
    reducedMotion: Boolean,
    onOrbTap: () -> Unit,
    onOrbLongPress: () -> Unit,
    onScreenEntry: () -> Unit,
    onAutoStart: () -> Unit,
    onMicPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
    // Deferred read: EVE's real output level for the ring's Speaking pulse (see JarvisRing).
    botLevel: () -> Float = { 0f },
) {
    val context = LocalContext.current

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onOrbTap() else onMicPermissionDenied() }

    // Auto-start permission: on app open we get the mic ONCE, then dial. A denial is the honest
    // named failure (never a fake-armed call). Separate launcher from the tap one so their result
    // callbacks stay unambiguous.
    val autoStartPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onAutoStart() else onMicPermissionDenied() }

    // FRESH ENTRY re-arms the one-shot auto-start — declared BEFORE the auto-start effect so the
    // re-arm lands first on entry. Deliberately not tied to hangUp: a deliberate end rests at Idle
    // and the effect below refires there, so a hangUp-side re-arm would instantly redial the call
    // the owner just ended.
    LaunchedEffect(Unit) { onScreenEntry() }

    // AUTO-START: the moment a configured door leaves the machine at rest (Idle), dial with no tap.
    // Keyed on that transition so it fires once per entry; the controller's own guard makes a repeat
    // (config redelivery / recomposition / post-hang-up Idle) a no-op regardless. NotConfigured
    // stays honest — no door, no dial.
    val readyToAutoStart = state is VoiceState.Idle
    LaunchedEffect(readyToAutoStart) {
        if (!readyToAutoStart) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onAutoStart() else autoStartPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun tap() {
        // Live states (mute toggle / interrupt) need no permission — the mic is already open. Only a
        // connect transition (Idle / NotConfigured / Error) needs RECORD_AUDIO gated first.
        val connecting = state is VoiceState.Idle || state is VoiceState.NotConfigured || state is VoiceState.Error
        if (!connecting) { onOrbTap(); return }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onOrbTap() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The long-press ease the ring renders (0..1). Animatable so an early release springs back.
    val holdProgress = remember { Animatable(0f) }
    // Written ONLY inside the gesture handler (synchronously with the pointer event, main thread);
    // read by the gate at fire time. Plain holder, not snapshot state — HoldToApproveWear's core.
    val pressed = remember { OrbPressHolder() }
    // The pointerInput lambda is captured once; these carry the CURRENT state/callbacks into it.
    val stateNow = rememberUpdatedState(state)
    val tapNow = rememberUpdatedState<() -> Unit>({ tap() })
    val longPressNow = rememberUpdatedState(onOrbLongPress)

    Scaffold(
        timeText = { TimeText() },
        modifier = modifier.fillMaxSize().background(WearEveColors.background),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    // Accessibility actions mirror the two gestures (the raw pointer core below
                    // exposes no click semantics of its own).
                    .semantics {
                        onClick(label = orbContentDescription(state)) { tapNow.value(); true }
                        onLongClick(label = "End the call") { longPressNow.value(); true }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            pressed.value = true
                            var holdFired = false
                            var tapWindowOpen = true
                            val endsCall = holdEndsCall(stateNow.value)
                            // GATE: ends the call only after a full continuous hold, re-checked
                            // against pressed at fire time (the ported race fix). Armed only in
                            // live states — at rest there is nothing to end.
                            val gate = if (endsCall) {
                                scope.launch {
                                    delay(ORB_HOLD_TO_END_MS)
                                    if (pressed.value) {
                                        holdFired = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        longPressNow.value()
                                    }
                                }
                            } else {
                                null
                            }
                            // VISUAL only, decoupled from the gate: the ring eases down over the
                            // hold window. Skipped under reducedMotion (the haptic tick + the teal
                            // rest state carry the feedback) — never a per-press flash.
                            val visual = if (endsCall && !reducedMotion) {
                                scope.launch {
                                    holdProgress.animateTo(1f, tween(ORB_HOLD_TO_END_MS.toInt()))
                                }
                            } else {
                                null
                            }
                            // TAP WINDOW: a release counts as the tap only while this is open — a
                            // 500ms press-then-release is a cancelled hold, NOT a mute toggle.
                            val tapWindow = scope.launch {
                                delay(ORB_TAP_MAX_MS)
                                tapWindowOpen = false
                            }
                            // Up OR cancellation (finger drift, swipe steal) — both kill the gate
                            // synchronously with the event; a released press can never end the call.
                            val up = waitForUpOrCancellation()
                            pressed.value = false
                            gate?.cancel()
                            visual?.cancel()
                            tapWindow.cancel()
                            scope.launch {
                                if (holdProgress.value > 0f) {
                                    holdProgress.animateTo(0f, tween(HOLD_RELEASE_EASE_MS))
                                }
                            }
                            if (up != null && !holdFired && tapWindowOpen) tapNow.value()
                        }
                    }
                    .testTag("liveOrb"),
                contentAlignment = Alignment.Center,
            ) {
                JarvisRing(
                    state = state,
                    size = 150.dp,
                    reducedMotion = reducedMotion,
                    micMuted = controls.micMuted,
                    botLevel = botLevel,
                    holdProgress = { holdProgress.value },
                )
            }

            // Honesty spine: only states the owner must act on carry text — the happy path is the
            // orb alone. NoAudio also shows EVE's latest words so her reply reaches the wrist when
            // her voice can't.
            if (showsHonestText(state)) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = labelFor(state),
                    color = labelColor(state),
                    style = MaterialTheme.typography.caption1,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                if (state == VoiceState.NoAudio) {
                    transcript.lastOrNull()?.let { line ->
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = (if (line.speaker == LiveTranscriptLine.Speaker.You) "You: " else "EVE: ") + line.text,
                            color = WearEveColors.textSecondary,
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pure: which states a long-press can END. Every live state — Connecting included (the long-press is
 * the deliberate warm-up abort the stray-tap protection blocks). At rest there is nothing to end.
 */
internal fun holdEndsCall(state: VoiceState): Boolean = when (state) {
    VoiceState.Idle, VoiceState.NotConfigured, is VoiceState.Error -> false
    else -> true
}

/**
 * States the owner must ACT on keep their honest text (no door / a named error / connected-but-no-
 * audio); every happy-path state is the orb alone. Never a silent fake-OK, never chrome on the
 * happy path.
 */
private fun showsHonestText(state: VoiceState): Boolean = when (state) {
    VoiceState.NotConfigured, VoiceState.NoAudio, is VoiceState.Error -> true
    else -> false
}

/** The honest headline for each text-bearing state — failures show their exact centralized copy. */
private fun labelFor(state: VoiceState): String = when (state) {
    is VoiceState.Error -> state.message
    VoiceState.NotConfigured -> WearLiveVoiceCopy.NOT_CONFIGURED
    else -> orbContentDescription(state)
}

private fun labelColor(state: VoiceState): Color = when (state) {
    is VoiceState.Error -> WearEveColors.danger
    VoiceState.NotConfigured, VoiceState.NoAudio -> WearEveColors.warning
    else -> WearEveColors.textPrimary
}

/** Mutable press flag shared by the gesture handler and the gate (main-thread only). */
private class OrbPressHolder(var value: Boolean = false)

/** ~700ms deliberate hold ends the call (the agreed long-press-to-end beat). */
const val ORB_HOLD_TO_END_MS = 700L

/** A release inside this window is the TAP; between here and the hold threshold = cancelled hold. */
const val ORB_TAP_MAX_MS = 300L

/** Spring-back after an early release — matches the approve gate's cancel (:app Motion.durFast). */
private const val HOLD_RELEASE_EASE_MS = 140
