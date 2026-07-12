package app.eve.wear.livevoice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * JARVIS RING — the wrist call visual. An arc-reactor orb (core disc + 3 concentric arc-ring layers,
 * additively glowing like NeuralBrain's BlendMode.Plus discs) that MOVES when EVE talks, replacing the
 * 128-particle morphing NeuralBrain on the watch. The owner's hardware note: "the swirling thing is a
 * little weird — more of a Jarvis circle, or anything that moves when it talks." This is that circle.
 *
 * SAME palette hues as NeuralBrain's PALETTES so the state language stays consistent across the app:
 *
 *   idle / not-configured      → TEAL   slow calm spin, gentle breathing glow
 *   connecting / reconnecting  → SLATE  dim rings + one bright arc sweeping like a radar scan
 *   your-turn / hearing        → SKY    radius + glow pulse with the REAL mic level; segments tighten
 *   thinking                   → PURPLE counter-rotating arc layers, speed gently ramping
 *   speaking                   → AMBER  rings pulse rhythmically (SYNTHETIC envelope — see note)
 *   no-audio / error           → DANGER frozen dim ring (the screen's danger/dim language)
 *
 * SPEAKING is REAL: the pulse follows [botLevel] — the smoothed RMS of EVE's actual downlink PCM
 * ([PcmLevel] + [SpeakingEnvelope] published by the socket client). It is read as a lambda inside the
 * frame loop (deferred read — level ticks never recompose). When EVE is quiet the ring rests at its
 * base glow; it never invents motion. (The synthetic sine stand-in died 2026-07-10.)
 *
 * Under reduced-motion the ring renders a single deterministic static frame per state (the caller's
 * label carries the signal). Geometry (radii, segment start angles, sweeps) is precomputed into
 * top-level arrays; the draw loop matches NeuralBrain's cheap discipline (target <1.5ms on a 480px
 * round face) — no per-frame simulation arrays, no heavy allocation.
 */

// ---- pure state -> ring behaviour mapping (JVM-unit-tested in JarvisRingSpecTest) ----------------

/** The six visual moods the ring collapses the conversation machine into. */
internal enum class RingMood { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, DEAD }

/** Pure: fold the live-voice [VoiceState] onto a [RingMood]. Total over the sealed set. */
internal fun ringMoodOf(state: VoiceState): RingMood = when (state) {
    VoiceState.Idle, VoiceState.NotConfigured -> RingMood.IDLE
    VoiceState.Connecting, VoiceState.Reconnecting -> RingMood.CONNECTING
    VoiceState.YourTurn, is VoiceState.Hearing -> RingMood.LISTENING
    VoiceState.Thinking -> RingMood.THINKING
    VoiceState.Speaking -> RingMood.SPEAKING
    VoiceState.NoAudio, is VoiceState.Error -> RingMood.DEAD
}

/**
 * The static visual recipe for a mood. Colours are RGB 0..255 triples reusing NeuralBrain's exact
 * PALETTES hues (DANGER is the screen's [app.eve.wear.ui.WearEveColors.danger] 0xFFF87171). Pure data
 * so the state->look contract is asserted with exact values off the device.
 */
internal data class RingSpec(
    val core: IntArray, // core disc + centre dot
    val ring: IntArray, // arc-ring stroke
    val glow: IntArray, // additive halo accent
    val baseRotDegPerSec: Float, // primary ring spin
    val counterRotate: Boolean, // odd ring layers reverse (thinking) + spin ramps
    val radarSweep: Boolean, // one bright arc scans over dim rings (connecting)
    val pulseWithMic: Boolean, // radius/glow track REAL mic level; segments tighten (listening)
    val speakPulse: Boolean, // radius/glow track EVE's REAL output level (speaking)
    val breathe: Boolean, // slow glow breathing (idle / listening)
    val frozen: Boolean, // no motion at all (dead states)
    val dim: Float, // overall brightness multiplier
) {
    // Array fields make the generated equals/hashCode reference-based; tests assert fields, never
    // whole-object equality. Overrides omitted deliberately (the class is an internal value bag).
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Pure: the exact recipe per mood. Hues match NeuralBrain PALETTES so the state language is one. */
internal fun specForMood(mood: RingMood): RingSpec = when (mood) {
    RingMood.IDLE -> RingSpec(
        core = intArrayOf(56, 195, 215), ring = intArrayOf(45, 165, 190), glow = intArrayOf(80, 200, 220),
        baseRotDegPerSec = 12f, counterRotate = false, radarSweep = false, pulseWithMic = false,
        speakPulse = false, breathe = true, frozen = false, dim = 1.0f,
    )
    RingMood.CONNECTING -> RingSpec(
        core = intArrayOf(96, 104, 118), ring = intArrayOf(100, 108, 122), glow = intArrayOf(110, 118, 132),
        baseRotDegPerSec = 0f, counterRotate = false, radarSweep = true, pulseWithMic = false,
        speakPulse = false, breathe = false, frozen = false, dim = 0.6f,
    )
    RingMood.LISTENING -> RingSpec(
        core = intArrayOf(90, 210, 255), ring = intArrayOf(56, 189, 248), glow = intArrayOf(165, 235, 255),
        baseRotDegPerSec = 18f, counterRotate = false, radarSweep = false, pulseWithMic = true,
        speakPulse = false, breathe = true, frozen = false, dim = 1.0f,
    )
    RingMood.THINKING -> RingSpec(
        core = intArrayOf(192, 132, 252), ring = intArrayOf(167, 139, 250), glow = intArrayOf(221, 200, 255),
        baseRotDegPerSec = 28f, counterRotate = true, radarSweep = false, pulseWithMic = false,
        speakPulse = false, breathe = false, frozen = false, dim = 1.0f,
    )
    RingMood.SPEAKING -> RingSpec(
        core = intArrayOf(251, 191, 36), ring = intArrayOf(245, 178, 64), glow = intArrayOf(255, 226, 150),
        baseRotDegPerSec = 16f, counterRotate = false, radarSweep = false, pulseWithMic = false,
        speakPulse = true, breathe = false, frozen = false, dim = 1.0f,
    )
    RingMood.DEAD -> RingSpec(
        core = intArrayOf(248, 113, 113), ring = intArrayOf(180, 90, 90), glow = intArrayOf(200, 110, 110),
        baseRotDegPerSec = 0f, counterRotate = false, radarSweep = false, pulseWithMic = false,
        speakPulse = false, breathe = false, frozen = true, dim = 0.45f,
    )
}

/** Convenience: state straight to spec (what the composable reads each recomposition). */
internal fun ringSpecOf(state: VoiceState): RingSpec = specForMood(ringMoodOf(state))

/**
 * Pure: the muted-mic overlay. Muting is truthful (the client stops sending audio); this is its
 * VISIBLE half — the owner's cue (2026-07-11): "red = mic muted, blue = live". Color ONLY: the
 * base spec's motion flags, spin and brightness carry over untouched, so the ring keeps moving
 * (and pulsing with real amplitude) exactly as its state demands — just red. The hue family is
 * the screen's danger red (WearEveColors.danger 0xFFF87171) at full brightness, which reads
 * clearly against the black face — unlike DEAD's frozen dim-red, this ring is alive.
 */
internal fun mutedSpecOf(base: RingSpec): RingSpec = RingSpec(
    core = intArrayOf(248, 113, 113), // WearEveColors.danger
    ring = intArrayOf(239, 68, 68),
    glow = intArrayOf(255, 160, 160),
    baseRotDegPerSec = base.baseRotDegPerSec,
    counterRotate = base.counterRotate,
    radarSweep = base.radarSweep,
    pulseWithMic = base.pulseWithMic,
    speakPulse = base.speakPulse,
    breathe = base.breathe,
    frozen = base.frozen,
    dim = base.dim,
)

/**
 * Pure: the long-press-to-end visual. While the owner holds the orb the ring eases DOWN toward the
 * at-rest look — contracting and dimming with the hold progress (0..1) — so the hold never feels
 * dead and an early release visibly springs back. Values chosen to read on a small round face: a
 * full hold sits at 82% radius, 55% brightness. Progress is clamped so an animator overshoot can
 * never invert the ease.
 */
internal fun holdRadiusScale(progress: Float): Float = 1f - 0.18f * clamp01(progress)

internal fun holdBrightnessScale(progress: Float): Float = 1f - 0.45f * clamp01(progress)

// ---- precomputed geometry (resolution-independent fractions of the orb radius) -------------------

/** 3 concentric arc rings: radius, stroke width, relative spin (magnitude+dir), per-layer alpha. */
private val RING_RADIUS = floatArrayOf(0.60f, 0.78f, 0.94f)
private val RING_STROKE = floatArrayOf(0.060f, 0.042f, 0.030f)
private val RING_SPIN = floatArrayOf(1.0f, 0.75f, 1.15f)
private val RING_ALPHA = floatArrayOf(1.0f, 0.82f, 0.62f)
private val RING_SEG = intArrayOf(3, 5, 7)
private val RING_GAP = floatArrayOf(0.34f, 0.26f, 0.20f)

/** Segment start angles (deg) per ring — precomputed once, offset by the animated rotation at draw. */
private val RING_STARTS: Array<FloatArray> = Array(RING_RADIUS.size) { l ->
    FloatArray(RING_SEG[l]) { i -> i * 360f / RING_SEG[l] }
}
private val RING_SWEEP: FloatArray = FloatArray(RING_RADIUS.size) { l -> (360f / RING_SEG[l]) * (1f - RING_GAP[l]) }

private const val RADAR_DEG_PER_SEC = 150f
private const val BREATHE_HZ = 1.4f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
private fun colorOf(c: FloatArray, a: Float) = Color(c[0] / 255f, c[1] / 255f, c[2] / 255f, clamp01(a))

/** Additive radial glow disc — the same "lighter"-composited luminous look as NeuralBrain's glow(). */
private fun DrawScope.glowDisc(center: Offset, radius: Float, color: FloatArray, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0.5f) return
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to colorOf(color, alpha),
            0.55f to colorOf(color, alpha * 0.3f),
            1.0f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = BlendMode.Plus,
    )
}

// ---- the tiny animation state (no simulation arrays; a handful of eased scalars) -----------------

private class RingSim {
    var phase = 0f; private set
    var rot = 15f; private set // seeded off-axis so the static (reduced-motion) frame reads intentional
    var radar = 0f; private set
    var micNow = 0f; private set
    var botNow = 0f; private set
    var brightness = 0f; private set
    var pulse = 0f; private set

    // Current (eased) colours; lerp toward the spec target each frame for smooth state hand-offs.
    val core = floatArrayOf(56f, 195f, 215f)
    val ring = floatArrayOf(45f, 165f, 190f)
    val glow = floatArrayOf(80f, 200f, 220f)

    /** Advance one frame. mic is REAL (Hearing.level); the speaking pulse is EVE's REAL output level. */
    fun update(dt: Float, spec: RingSpec, rawMic: Float, rawBot: Float = 0f) {
        phase += dt
        val k = min(1f, dt * 5f)
        for (c in 0..2) {
            core[c] = lerp(core[c], spec.core[c].toFloat(), k)
            ring[c] = lerp(ring[c], spec.ring[c].toFloat(), k)
            glow[c] = lerp(glow[c], spec.glow[c].toFloat(), k)
        }
        if (!spec.frozen) {
            // Thinking ramps its spin gently up and down so the counter-rotation "speeds up slightly".
            val ramp = if (spec.counterRotate) 1f + 0.4f * (0.5f + 0.5f * sin(phase * 0.5f)) else 1f
            rot = (rot + spec.baseRotDegPerSec * ramp * dt) % 360f
        }
        if (spec.radarSweep) radar = (radar + RADAR_DEG_PER_SEC * dt) % 360f
        micNow += (rawMic - micNow) * min(1f, dt * 10f)
        botNow += (rawBot - botNow) * min(1f, dt * 12f)
        val speakEnv = if (spec.speakPulse) botNow else 0f
        val breatheVal = if (spec.breathe && !spec.frozen) 0.5f + 0.5f * sin(phase * BREATHE_HZ) else 0f
        val target = spec.dim * (0.55f + 0.25f * breatheVal + 0.30f * max(micNow, speakEnv))
        brightness += (target - brightness) * min(1f, dt * 6f)
        pulse = when {
            spec.pulseWithMic -> micNow
            spec.speakPulse -> speakEnv
            spec.breathe -> breatheVal * 0.35f
            else -> 0f
        }
    }

    /** Deterministic single frame for reduced-motion: snap colours + a representative static pose. */
    fun snapTo(spec: RingSpec, rawMic: Float) {
        for (c in 0..2) {
            core[c] = spec.core[c].toFloat(); ring[c] = spec.ring[c].toFloat(); glow[c] = spec.glow[c].toFloat()
        }
        micNow = if (spec.pulseWithMic) rawMic else 0f
        botNow = 0f
        brightness = spec.dim * 0.7f
        pulse = when {
            spec.pulseWithMic -> micNow
            spec.speakPulse -> 0.5f
            spec.breathe -> 0.2f
            else -> 0f
        }
    }
}

@Composable
fun JarvisRing(
    state: VoiceState,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    reducedMotion: Boolean = false,
    // A muted mic turns the whole orb RED (truthful, label-free "EVE can't hear you" cue — owner's
    // color language: red = muted, blue = live) and is spoken in the content description.
    micMuted: Boolean = false,
    // Deferred read (lambda, not value): EVE's real output level ticks ~25-50x/s and must feed the
    // frame loop without recomposing the composable. Default 0f keeps previews/old callers valid.
    botLevel: () -> Float = { 0f },
    // Deferred read: the long-press-to-end progress (0..1). While held the ring contracts + dims
    // toward the at-rest look (holdRadiusScale/holdBrightnessScale); read inside the draw scope so
    // each animated tick invalidates the draw only, never recomposes. Default 0f = no hold.
    holdProgress: () -> Float = { 0f },
) {
    val sim = remember { RingSim() }
    val stateNow = rememberUpdatedState(state)
    val mutedNow = rememberUpdatedState(micMuted)
    val botLevelNow = rememberUpdatedState(botLevel)
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                val s = stateNow.value
                val rawMic = if (s is VoiceState.Hearing) s.level else 0f
                // The sim lerps its colors toward the handed spec, so the muted overlay must be
                // applied HERE (a composition-only recolor would never reach the animated draw).
                val sp = ringSpecOf(s).let { if (mutedNow.value) mutedSpecOf(it) else it }
                sim.update(dt, sp, rawMic, botLevelNow.value())
                tick = now
            }
        }
    }

    // Muted recolors the spec (red, color-only overlay); motion carries over from the base spec.
    val spec = ringSpecOf(state).let { if (micMuted) mutedSpecOf(it) else it }
    val rawMic = if (state is VoiceState.Hearing) state.level else 0f
    val description = if (micMuted) {
        "${orbContentDescription(state)} — your mic is muted, tap to unmute"
    } else {
        orbContentDescription(state)
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        tick // observe the frame clock so the canvas redraws each animated frame
        if (reducedMotion) {
            sim.snapTo(spec, rawMic)
        } else if (sim.phase == 0f) {
            sim.update(0.016f, spec, rawMic) // first paint before the frame loop's first tick lands
        }

        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val center = Offset(cx, cy)
        // Long-press-to-end feedback: the whole orb eases down with the hold (see holdRadiusScale).
        val hold = holdProgress()
        val r = this.size.minDimension * 0.46f * holdRadiusScale(hold)
        val bright = sim.brightness * holdBrightnessScale(hold)

        // --- concentric arc rings (thin arcs with gaps, additive) -------------------------------
        for (l in RING_RADIUS.indices) {
            val rad = r * RING_RADIUS[l] * (1f + sim.pulse * 0.06f)
            val stroke = r * RING_STROKE[l]
            val dir = if (spec.counterRotate && l % 2 == 1) -1f else 1f
            val ringRot = sim.rot * RING_SPIN[l] * dir
            // Listening: segments tighten (sweep shrinks) as the user's real mic level rises.
            val sweep = RING_SWEEP[l] * (1f - 0.25f * sim.micNow * (if (spec.pulseWithMic) 1f else 0f))
            val alpha = clamp01(0.30f + bright * 0.55f) * RING_ALPHA[l] * (if (spec.radarSweep) 0.45f else 1f)
            val topLeft = Offset(cx - rad, cy - rad)
            val arcSize = Size(rad * 2f, rad * 2f)
            for (s in RING_STARTS[l]) {
                drawArc(
                    color = colorOf(sim.ring, alpha),
                    startAngle = s + ringRot,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    blendMode = BlendMode.Plus,
                )
            }
        }

        // --- radar scan: one bright short arc sweeping over the dim rings (connecting) -----------
        if (spec.radarSweep) {
            val rad = r * RING_RADIUS[2]
            val topLeft = Offset(cx - rad, cy - rad)
            val arcSize = Size(rad * 2f, rad * 2f)
            drawArc(
                color = colorOf(sim.core, clamp01(0.55f + bright * 0.4f)),
                startAngle = sim.radar,
                sweepAngle = 46f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = r * RING_STROKE[2] * 1.6f, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
            // bright leading tip
            val tipAng = ((sim.radar + 46f) * Math.PI / 180.0)
            val tip = Offset(cx + (rad * kotlin.math.cos(tipAng)).toFloat(), cy + (rad * kotlin.math.sin(tipAng)).toFloat())
            glowDisc(tip, r * 0.12f, sim.core, clamp01(0.5f + bright * 0.4f))
        }

        // --- core disc + halo (arc-reactor centre) ----------------------------------------------
        val coreR = r * (0.26f + sim.pulse * 0.07f)
        glowDisc(center, coreR * 2.6f, sim.glow, clamp01(bright * 0.6f)) // outer halo
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to colorOf(sim.core, clamp01(0.45f + bright * 0.45f)),
                0.6f to colorOf(sim.core, 0.15f),
                1.0f to Color.Transparent,
                center = center,
                radius = coreR,
            ),
            radius = coreR,
            center = center,
            blendMode = BlendMode.Plus,
        )
        // solid-ish bright pip so the reactor has a hot centre
        drawCircle(color = colorOf(sim.core, clamp01(0.55f + bright * 0.35f)), radius = coreR * 0.42f, center = center)
    }
}
