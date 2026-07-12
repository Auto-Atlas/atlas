package app.eve.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watch hold-to-approve. Ports the :app HoldToApproveButton CONTRACT VERBATIM (money-safety gate):
 * the owner must press and hold for [holdDurationMs] (520ms, ported from :app Motion.kt
 * durDeliberate) before onApprove fires; releasing early CANCELS. On Wear the long-press-hold IS the
 * affordance — rotary confirm is intentionally out of scope this increment (noted, not faked).
 *
 * Gesture core uses the RAW pointer primitives (awaitEachGesture/waitForUpOrCancellation), which
 * resume INLINE during MotionEvent dispatch — plus the gate re-checks [PressedHolder.value] at fire
 * time. The earlier detectTapGestures/tryAwaitRelease structure had a real race caught by the
 * on-device gesture test (2026-07-10): the release-cancel resumed through the frame-gated
 * dispatcher while the gate's delay() fired off the real-time main handler, so on a frame-starved
 * UI a 200ms press could approve. Never again: release flips the flag synchronously with the event,
 * and a late gate dispatch finds pressed=false and does nothing.
 *
 * The GATE TIMING stays decoupled from the visual: under reducedMotion the fill snaps to 1f
 * (visual only) — the 520ms gate is NEVER shortened, so reduced-motion can never collapse to
 * tap-to-approve (the exact bug the phone test guards).
 */
@Composable
fun HoldToApproveWear(
    label: String,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reducedMotion: Boolean = false,
    holdDurationMs: Int = HOLD_TO_APPROVE_MS,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    // Written ONLY inside the gesture handler (synchronously with the pointer event, main thread);
    // read by the gate at fire time. Plain holder, not snapshot state — no recomposition needed.
    val pressed = remember { PressedHolder() }

    // Released early → snap the fill back to 0 (cancel), matching the phone control.
    LaunchedEffect(holding) {
        if (!holding && progress.value > 0f && progress.value < 1f) {
            progress.animateTo(0f, animationSpec = tween(FILL_CANCEL_MS))
        }
    }

    Box(
        modifier = modifier
            .testTag("holdApproveWear")
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (enabled) WearEveColors.accentSoft else WearEveColors.surface2)
            .drawBehind {
                if (!reducedMotion && progress.value > 0f) {
                    drawFill(progress.value, WearEveColors.accent.copy(alpha = 0.35f), size)
                }
            }
            .then(
                if (enabled) {
                    Modifier.pointerInput(reducedMotion, holdDurationMs) {
                        awaitEachGesture {
                            awaitFirstDown()
                            pressed.value = true
                            holding = true
                            // GATE: fires onApprove only after a full continuous holdDurationMs
                            // press, re-checked against pressed at fire time. Decoupled from the
                            // fill so reducedMotion can't shorten it.
                            val gate = scope.launch {
                                delay(holdDurationMs.toLong())
                                if (pressed.value) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onApprove()
                                }
                            }
                            // VISUAL only: snap under reducedMotion, sweep otherwise.
                            val visual = scope.launch {
                                if (reducedMotion) {
                                    progress.snapTo(1f)
                                } else {
                                    progress.animateTo(1f, tween(holdDurationMs))
                                }
                            }
                            // Up OR cancellation (finger drift, scroll steal) — both kill the gate,
                            // synchronously with the event. Releasing before holdDurationMs never
                            // approves.
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) WearEveColors.accent else WearEveColors.textTertiary,
        )
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

/** 520ms — ported from :app ui/theme/Motion.kt durDeliberate (the deliberate-commit beat). */
const val HOLD_TO_APPROVE_MS = 520
private const val FILL_CANCEL_MS = 140 // :app Motion.durFast
