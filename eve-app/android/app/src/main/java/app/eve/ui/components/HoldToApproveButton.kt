package app.eve.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hold-to-approve. The owner must press and hold for the deliberate-commit duration (520ms,
 * tokens.json motion.durDeliberate) before the action fires. Releasing early CANCELS — no fire.
 * This is the deliberate friction gate for a high-stakes, irreversible action.
 *
 * Reduced-motion: no sweeping fill — a static ring + a haptic pulse at completion instead.
 */
@Composable
fun HoldToApproveButton(
    label: String,
    consequence: String,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reducedMotion: Boolean = false,
    holdDurationMs: Int = EveTheme.motion.durDeliberateMs,
) {
    val colors = EveTheme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    // Written ONLY inside the gesture handler (synchronously with the pointer event, main thread);
    // read by the gate at fire time. Plain holder, not snapshot state — no recomposition needed.
    val pressed = remember { PressedHolder() }
    // Hoist the @Composable motion-token reads; the coroutine/gesture lambdas below run
    // outside composition and cannot call EveTheme.motion directly.
    val durFastMs = EveTheme.motion.durFastMs
    val easeEmphasized = EveTheme.motion.easeEmphasized

    // When released early, snap the fill back to 0 (cancel).
    LaunchedEffect(holding) {
        if (!holding && progress.value > 0f && progress.value < 1f) {
            progress.animateTo(0f, animationSpec = tween(durFastMs))
        }
    }

    Box(
        modifier = modifier
            .testTag("holdApprove")
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(EveTheme.shape.md)
            .background(if (enabled) colors.accentSoft else colors.surfaceRaised2)
            .then(if (progress.value >= 1f) Modifier.eveGlow(EveTheme.elevation.glowAccentStrong) else Modifier)
            .drawBehind {
                if (!reducedMotion && progress.value > 0f) {
                    // Commit fill sweeps left->right as the hold completes.
                    drawFill(progress.value, colors.accent.copy(alpha = 0.35f), size)
                }
            }
            .then(
                if (enabled) {
                    Modifier.pointerInput(reducedMotion, holdDurationMs) {
                        // Raw gesture primitives, NOT detectTapGestures: awaitEachGesture handlers
                        // resume INLINE during MotionEvent dispatch, so `pressed` flips false the
                        // instant the finger lifts. The old tryAwaitRelease() path resumed through
                        // the frame-gated dispatcher, and the gate's delay() fires off the real-time
                        // main handler — on a frame-starved UI (idle screen, reduced-motion snapTo)
                        // the gate could fire AFTER an early release. Caught by the on-device
                        // gesture test (2026-07-10): a 200ms press approved. The fix is two-fold:
                        // synchronous release handling + the gate re-checking `pressed` at fire time.
                        awaitEachGesture {
                            awaitFirstDown()
                            pressed.value = true
                            holding = true
                            // GATE TIMING is decoupled from the animation: completion is driven
                            // off elapsed press time, never off how the visual fills. This must
                            // hold for reducedMotion too — otherwise a snap-to-1f visual would
                            // collapse the money-safety gate to tap-to-approve.
                            val gate = scope.launch {
                                delay(holdDurationMs.toLong())
                                // Re-check at fire time: releasing before holdDurationMs never
                                // approves, no matter how late this dispatch runs.
                                if (pressed.value) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onApprove()
                                }
                            }
                            // VISUALS only differ by mode: reduced-motion gets a static fill
                            // (no smooth sweep), full-motion gets the emphasized tween. Neither
                            // path is what decides whether onApprove fires.
                            val visual = scope.launch {
                                if (reducedMotion) {
                                    progress.snapTo(1f)
                                } else {
                                    progress.animateTo(1f, tween(holdDurationMs, easing = easeEmphasized))
                                }
                            }
                            // Up OR cancellation (finger drift, parent scroll steal) — both must
                            // kill the gate. Resumes synchronously with the event.
                            waitForUpOrCancellation()
                            pressed.value = false
                            gate.cancel()
                            visual.cancel()
                            holding = false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = EveTheme.spacing.s5, vertical = EveTheme.spacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        // The label speaks the consequence for screen readers (a11y spec).
        Text(
            text = label,
            style = EveTheme.type.label.copy(color = colors.accent.copy(alpha = alpha)),
        )
        // consequence is exposed via the semantics content description on the parent at call site;
        // it is referenced here to keep it part of the API contract.
        if (consequence.isBlank()) Unit
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFill(
    progress: Float,
    color: androidx.compose.ui.graphics.Color,
    size: Size,
) {
    drawRect(
        color = color,
        topLeft = Offset.Zero,
        size = Size(width = size.width * progress.coerceIn(0f, 1f), height = size.height),
    )
}

/** Mutable press flag shared by the gesture handler and the gate (main-thread only). */
private class PressedHolder(var value: Boolean = false)
