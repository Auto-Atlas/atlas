package app.eve.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme
import app.eve.voice.VoiceState

/**
 * The signature ListeningOrb — a teal→indigo radial whose animation is driven by [VoiceState]
 * (design motion.css keyframes: eve-halo on connect, eve-listen breathing on YourTurn, eve-wave
 * reacting to live level while Hearing/Speaking, eve-think shimmer while Thinking).
 *
 * a11y (BMAD: Amelia + Sally — enforced by review, since UI isn't test-gated):
 *  (a) reduced-motion → static orb + the caller's mandatory label+icon carry 100% of the signal
 *      (the design system's "never motion/color alone" rule applied to voice);
 *  (b) per-state [contentDescription];
 *  (c) screen-reader live-region announce on every state transition (liveRegion = Polite).
 *  Haptic turn-boundary cues are fired by the screen (it owns the HapticFeedback handle).
 */
@Composable
fun ListeningOrb(
    state: VoiceState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    /** When EVE is running a tool / delegating, the orb shifts to the green "working" hue and an
     *  orbiting comet arc — the transformative "doing something" motion. Takes visual precedence
     *  over [state] (a tool can run across listening/thinking), mirroring the desktop avatar's
     *  `working` morph. */
    working: Boolean = false,
) {
    val colors = EveTheme.colors

    val effectiveReducedMotion = reducedMotion

    val transition = rememberInfiniteTransition(label = "orb")

    // eve-listen breathing (scale 1 → 1.04), eve-think shimmer (opacity 0.4 → 1),
    // eve-wave (scaleY 0.35 → 1). Amplitude is bound to Hearing.level / Speaking energy.
    // compose-state-deferred-reads: keep these as State<Float> (no `by`); their per-frame `.value`
    // is read ONLY inside the graphicsLayer / drawBehind lambdas below (layer/draw phase) so the
    // orb redraws every frame but never recomposes or relayouts.
    val breathe = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val shimmer = transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmer",
    )
    val wave = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "wave",
    )
    // eve-halo: expanding rings that radiate outward while EVE is connecting or listening — the
    // orb's signature "I'm here, the floor is yours" motion. 0→1 = one ring's outward sweep.
    val halo = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "halo",
    )
    // working: a comet arc that orbits the core (startAngle sweeps 0→360).
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    // Per-state Hearing amplitude — a stable parameter (not a frame-rate value), safe to read in
    // the body; the frame-rate `wave` it modulates is read inside graphicsLayer.
    val level: Float = when (state) {
        is VoiceState.Hearing -> state.level.coerceIn(0f, 1f)
        else -> 1f
    }

    // Per-state speaking color (ported from the desktop stage) — the orb reads its state through
    // hue: teal idle, sky-blue listening, purple thinking, amber speaking, red error. A running
    // tool/delegation takes precedence with the green "working" hue.
    val coreColor: Color = if (working) VoiceOrbPalette.Working else orbStateColor(state)

    // The spoken/announced label. While working it overrides the state label so the orb reads
    // "working on it" to a screen reader (the status line below carries the specific tool).
    val description = if (working) "EVE is working on it" else orbContentDescription(state)

    // Halo radiates only while connecting / waiting-for-you / hearing you — not while EVE thinks
    // (shimmer), speaks (wave), or works (the orbiting comet), and never under reduced-motion.
    val showHalo = !effectiveReducedMotion && !working && when (state) {
        VoiceState.Connecting, VoiceState.YourTurn, VoiceState.Reconnecting -> true
        is VoiceState.Hearing -> true
        else -> false
    }

    Box(
        modifier = modifier
            .size(220.dp)
            .drawBehind {
                val maxR = size.minDimension / 2f
                val coreR = maxR * 0.62f
                if (working && !effectiveReducedMotion) {
                    // A comet arc orbiting the core + a faint guide ring — the "I'm working" motion.
                    // `sweep` is read here in the draw phase (deferred) so the per-frame value
                    // redraws without recomposing.
                    val r = maxR * 0.82f
                    val box = Size(r * 2f, r * 2f)
                    val topLeft = Offset(size.width / 2f - r, size.height / 2f - r)
                    drawCircle(color = coreColor.copy(alpha = 0.12f), radius = r, style = Stroke(width = 2.dp.toPx()))
                    drawArc(
                        color = coreColor.copy(alpha = 0.9f),
                        startAngle = sweep,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = box,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                    return@drawBehind
                }
                if (!showHalo) return@drawBehind
                // Two phase-offset rings expand from the core to the edge, fading as they grow.
                // `halo` is read here in the draw phase (deferred) so the per-frame value never
                // recomposes the orb — only redraws.
                val haloPhase = halo.value
                for (i in 0..1) {
                    val p = (haloPhase + i * 0.5f) % 1f
                    drawCircle(
                        color = coreColor.copy(alpha = (1f - p) * 0.5f),
                        radius = coreR + (maxR - coreR) * p,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                // Fixed size — the pulse is applied as a layer transform, never a layout read, so
                // the per-frame scale redraws but never relayouts (compose-state-deferred-reads).
                .size(180.dp)
                .graphicsLayer {
                    // Per-state scale, computed from the frame-rate floats INSIDE the layer block
                    // (deferred = layer/draw phase). Reduced-motion stays static (scale 1).
                    val pulse: Float = when {
                        effectiveReducedMotion -> 1f
                        state is VoiceState.YourTurn -> breathe.value
                        state is VoiceState.Hearing ->
                            1f + 0.06f * wave.value * (0.4f + 0.6f * level)
                        state is VoiceState.Speaking -> 1f + 0.05f * wave.value
                        state is VoiceState.Connecting || state is VoiceState.Reconnecting ->
                            breathe.value
                        else -> 1f
                    }
                    scaleX = pulse
                    scaleY = pulse
                }
                .drawBehind {
                    // Per-state opacity, with the frame-rate `shimmer` read here in the draw phase.
                    val a: Float = when {
                        effectiveReducedMotion -> 1f
                        state is VoiceState.Thinking -> shimmer.value
                        state is VoiceState.NoAudio -> 0.5f
                        state is VoiceState.Idle -> 0.85f
                        else -> 1f
                    }
                    val radius = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            // Monochromatic glow in the state's own hue so the color reads cleanly
                            // (a mixed-in indigo muddied every state to the same look before).
                            colors = listOf(
                                coreColor.copy(alpha = a),
                                coreColor.copy(alpha = a * 0.45f),
                                colors.surfaceCanvas.copy(alpha = 0f),
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = radius,
                        ),
                        radius = radius,
                    )
                },
        )
    }
}

/** Per-state spoken label (also used as the live-region announcement). */
fun orbContentDescription(state: VoiceState): String = when (state) {
    VoiceState.Idle -> "Tap to talk to EVE"
    VoiceState.Connecting -> "Connecting to EVE"
    VoiceState.YourTurn -> "Go ahead, I'm listening"
    is VoiceState.Hearing -> "Hearing you"
    VoiceState.Thinking -> "EVE is thinking"
    VoiceState.Speaking -> "EVE is speaking"
    VoiceState.Reconnecting -> "Reconnecting"
    VoiceState.NoAudio -> "Connected, but no audio is getting through"
    is VoiceState.Error -> "Connection problem: ${state.message}"
}
