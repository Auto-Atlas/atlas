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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The living avatar — a faithful Compose-Canvas port of the phone's ui/components/NeuralBrain (same
 * forms, palettes, physics and animation), sized for the round watch face (~180dp default). One pool
 * of 128 glowing particles that physically MORPHS between forms as Atlas's REAL server state changes:
 *
 *   not-configured / error / reconnect → dim SMOKE slate (frozen haze)
 *   idle / connecting                  → SMOKE  teal (calm plume)
 *   listening (your turn / hearing)    → LEAN   sky (condenses, leans in)
 *   thinking                           → BRAIN  purple (neural graph + synapse pulses)
 *   speaking                           → GENIE  amber (rising vapor genie)
 *
 * Physics run in resolution-independent [-1,1] space and scale to the canvas at draw time. Under
 * reduced-motion the orb renders a single static frame (the caller's label carries the signal).
 *
 * NOTE: the phone's WALKER (tool "working") form is intentionally dropped — the live watch call has no
 * tool surface, so the working state never occurs here. Everything else is ported verbatim.
 */

private const val TOTAL = 128
private val RING_COUNTS = intArrayOf(1, 7, 14, 24, 36)
private val RING_RADII = floatArrayOf(0f, 0.24f, 0.46f, 0.7f, 0.94f)
private val GRAPH_N = RING_COUNTS.sum()

private enum class Form { SMOKE, LEAN, BRAIN, GENIE }

/** The visual state the avatar reads — collapses the conversation machine into the web states. */
private enum class Vis { DISCONNECTED, IDLE, LISTENING, THINKING, SPEAKING }

private fun visOf(state: VoiceState): Vis = when {
    state is VoiceState.Speaking -> Vis.SPEAKING
    state is VoiceState.Thinking -> Vis.THINKING
    state is VoiceState.Hearing || state is VoiceState.YourTurn -> Vis.LISTENING
    state is VoiceState.Error || state is VoiceState.Reconnecting || state is VoiceState.NotConfigured ->
        Vis.DISCONNECTED
    else -> Vis.IDLE // Idle, Connecting, NoAudio
}

private fun formOf(v: Vis): Form = when (v) {
    Vis.SPEAKING -> Form.GENIE
    Vis.THINKING -> Form.BRAIN
    Vis.LISTENING -> Form.LEAN
    Vis.DISCONNECTED, Vis.IDLE -> Form.SMOKE
}

/** 4-colour palette per visual state (node / edge / pulse / core) — exact web hues. */
private class Palette(val node: FloatArray, val edge: FloatArray, val pulse: FloatArray, val core: FloatArray)

private val PALETTES: Map<Vis, Palette> = mapOf(
    Vis.DISCONNECTED to Palette(f(100, 108, 122), f(70, 76, 90), f(110, 118, 132), f(96, 104, 118)),
    Vis.IDLE to Palette(f(45, 165, 190), f(30, 110, 135), f(80, 200, 220), f(56, 195, 215)),
    Vis.LISTENING to Palette(f(56, 189, 248), f(38, 120, 200), f(165, 235, 255), f(90, 210, 255)),
    Vis.THINKING to Palette(f(167, 139, 250), f(110, 80, 200), f(221, 200, 255), f(192, 132, 252)),
    Vis.SPEAKING to Palette(f(245, 178, 64), f(190, 120, 36), f(255, 226, 150), f(251, 191, 36)),
)

private fun f(r: Int, g: Int, b: Int) = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
private fun colorOf(c: FloatArray, a: Float) = Color(c[0] / 255f, c[1] / 255f, c[2] / 255f, clamp01(a))

/** Deterministic RNG (mulberry32) so the avatar has the same anatomy every launch. */
private fun mulberry32(seed: Int): () -> Float {
    var a = seed
    return {
        a += 0x6D2B79F5.toInt()
        var t = a
        t = (t xor (t ushr 15)) * (1 or t)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
}

// --- Neural graph (the thinking form): force-directed, built once, shared read-only ---------------
private class Graph(
    val nx: FloatArray, val ny: FloatArray, val depth: IntArray, val parent: IntArray,
    val children: Array<IntArray>, val nr: FloatArray, val phase: FloatArray,
    val edges: Array<IntArray>, /* [a,b,trunk] */ val cortex: IntArray,
)

private val sharedGraph: Graph by lazy { buildGraph() }

private fun buildGraph(): Graph {
    val rand = mulberry32(0x1A2B3C)
    val n = GRAPH_N
    val nx = FloatArray(n); val ny = FloatArray(n); val depth = IntArray(n)
    val parent = IntArray(n) { -1 }; val nr = FloatArray(n); val phase = FloatArray(n)
    val kids = Array(n) { mutableListOf<Int>() }
    val ringStart = IntArray(RING_COUNTS.size)
    var idx = 0
    for (ring in RING_COUNTS.indices) {
        ringStart[ring] = idx
        val count = RING_COUNTS[ring]
        for (i in 0 until count) {
            val baseAng = (i.toFloat() / count) * (PI * 2).toFloat() + ring * 0.7f
            val ang = baseAng + (rand() - 0.5f) * (if (ring == 0) 0f else 0.5f)
            val rad = RING_RADII[ring] * (if (ring == 0) 0f else 0.88f + rand() * 0.24f)
            nx[idx] = cos(ang) * rad; ny[idx] = sin(ang) * rad
            depth[idx] = ring
            nr[idx] = if (ring == 0) 3.2f else 2.6f - ring * 0.38f + rand() * 0.8f
            phase[idx] = rand() * (PI * 2).toFloat()
            idx++
        }
    }
    val edges = mutableListOf<IntArray>()
    for (ring in 1 until RING_COUNTS.size) {
        val start = ringStart[ring]; val prevStart = ringStart[ring - 1]
        val prevEnd = prevStart + RING_COUNTS[ring - 1]
        for (i in start until start + RING_COUNTS[ring]) {
            var best = prevStart; var bestD = Float.MAX_VALUE
            for (j in prevStart until prevEnd) {
                val dx = nx[i] - nx[j]; val dy = ny[i] - ny[j]; val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = j }
            }
            parent[i] = best; kids[best].add(i); edges.add(intArrayOf(best, i, 1))
        }
    }
    for (ring in 1 until RING_COUNTS.size) {
        val start = ringStart[ring]; val end = start + RING_COUNTS[ring]
        for (i in start until end) {
            var best = -1; var bestD = Float.MAX_VALUE
            for (j in start until end) {
                if (j == i) continue
                val dx = nx[i] - nx[j]; val dy = ny[i] - ny[j]; val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = j }
            }
            if (best >= 0 && edges.none { (it[0] == i && it[1] == best) || (it[0] == best && it[1] == i) }) {
                edges.add(intArrayOf(i, best, 0))
            }
        }
    }
    repeat(120) {
        for (i in 1 until n) {
            var fx = 0f; var fy = 0f
            for (j in 1 until n) {
                if (j == i) continue
                val dx = nx[i] - nx[j]; val dy = ny[i] - ny[j]
                val d2 = dx * dx + dy * dy + 0.0008f
                val fr = 0.00035f / d2
                fx += dx * fr; fy += dy * fr
            }
            val p = if (parent[i] == -1) 0 else parent[i]
            fx += (nx[p] - nx[i]) * 0.012f; fy += (ny[p] - ny[i]) * 0.012f
            val targetRad = RING_RADII[depth[i]]
            val rad = max(1e-6f, hypot(nx[i], ny[i]))
            val radial = (targetRad - rad) * 0.05f
            fx += (nx[i] / rad) * radial; fy += (ny[i] / rad) * radial
            nx[i] += fx; ny[i] += fy
        }
    }
    val cortexStart = ringStart[RING_COUNTS.size - 1]
    val cortex = IntArray(RING_COUNTS.last()) { cortexStart + it }
    return Graph(nx, ny, depth, parent, Array(n) { kids[it].toIntArray() }, nr, phase, edges.toTypedArray(), cortex)
}

private fun pathInward(g: Graph, from: Int): IntArray {
    val path = mutableListOf(from); var cur = from
    while (g.parent[cur] != -1) { cur = g.parent[cur]; path.add(cur) }
    return path.toIntArray()
}

private fun pathOutward(g: Graph, rand: () -> Float): IntArray {
    val path = mutableListOf(0); var cur = 0
    while (g.children[cur].isNotEmpty()) {
        cur = g.children[cur][(rand() * g.children[cur].size).toInt().coerceAtMost(g.children[cur].size - 1)]
        path.add(cur)
    }
    return path.toIntArray()
}

private class Pulse(val path: IntArray, var seg: Int, var t: Float, val speed: Float, val intensity: Float)

/** Live levels feeding the avatar. mic is REAL (Hearing.level); bot/think are gentle envelopes. */
private class Signals(var mic: Float = 0f, var bot: Float = 0f, var think: Float = 0f)

// --- The simulation -------------------------------------------------------------------------------
private class Brain {
    val g = sharedGraph
    private val seeded = mulberry32(0xBEEF)
    private val rand = mulberry32(0xF00D)

    val x = FloatArray(TOTAL); val y = FloatArray(TOTAL)
    private val vx = FloatArray(TOTAL); private val vy = FloatArray(TOTAL)
    private val s1 = FloatArray(TOTAL); private val s2 = FloatArray(TOTAL)
    private val delay = FloatArray(TOTAL); private val burst = BooleanArray(TOTAL)
    val glow = FloatArray(TOTAL)
    val flash = FloatArray(GRAPH_N)
    val pulses = ArrayList<Pulse>()

    private var mic = 0f; private var bot = 0f; private var think = 0f
    private var energy = 0f; private var localAcc = 0f
    var phase = 0f; private set
    var morphGlow = 0f; private set
    val blend = floatArrayOf(1f, 0f, 0f, 0f) // smoke, lean, brain, genie
    private var curForm = Form.SMOKE; private var prevForm = Form.SMOKE; private var morphAt = -10f

    val pNode = floatArrayOf(45f, 165f, 190f); val pEdge = floatArrayOf(30f, 110f, 135f)
    val pPulse = floatArrayOf(80f, 200f, 220f); val pCore = floatArrayOf(56f, 195f, 215f)

    private val tx = FloatArray(TOTAL); private val ty = FloatArray(TOTAL)

    init {
        for (i in 0 until TOTAL) {
            x[i] = (seeded() - 0.5f) * 1.6f; y[i] = (seeded() - 0.5f) * 1.6f
            s1[i] = seeded(); s2[i] = seeded(); delay[i] = (i % 17) * 0.018f; glow[i] = 1f
        }
    }

    fun update(dt: Float, vis: Vis, sig: Signals) {
        phase += dt
        val frozen = vis == Vis.DISCONNECTED

        mic += (sig.mic - mic) * min(1f, dt * 10f)
        bot += (sig.bot - bot) * min(1f, dt * 10f)
        think += (sig.think - think) * min(1f, dt * 8f)

        val tp = PALETTES.getValue(vis); val k = min(1f, dt * 5f)
        for (c in 0..2) {
            pNode[c] = lerp(pNode[c], tp.node[c], k); pEdge[c] = lerp(pEdge[c], tp.edge[c], k)
            pPulse[c] = lerp(pPulse[c], tp.pulse[c], k); pCore[c] = lerp(pCore[c], tp.core[c], k)
        }

        val breathe = if (frozen) 0f else 0.5f + 0.5f * sin(phase * (if (vis == Vis.IDLE) 0.9f else 1.6f))
        val activity = max(mic, max(bot, think))
        energy += (min(1f, activity * 0.85f + breathe * 0.15f) - energy) * min(1f, dt * 6f)
        val drift = if (frozen) 0.004f else 0.012f + energy * 0.02f

        val want = formOf(vis)
        if (want != curForm) {
            prevForm = curForm; curForm = want; morphAt = phase; morphGlow = 1f
            for (i in 0 until TOTAL) burst[i] = false
        }
        morphGlow = max(0f, morphGlow - dt * 1.2f)
        for (i in 0..3) blend[i] += ((if (i == curForm.ordinal) 1f else 0f) - blend[i]) * min(1f, dt * 3.2f)

        val gaseous = curForm == Form.SMOKE || curForm == Form.LEAN
        val K = if (gaseous) 16f else 36f
        val D = if (gaseous) 4.5f else 7.5f
        for (i in 0 until TOTAL) {
            val sinceMorph = phase - morphAt
            val inOld = sinceMorph < delay[i]
            setTargets(if (inOld) prevForm else curForm, i, drift)
            if (!inOld && !burst[i]) {
                burst[i] = true
                vx[i] += (seeded() - 0.5f) * 2.6f; vy[i] += (seeded() - 0.5f) * 2.6f
            }
            val kk = if (frozen) K * 0.25f else K
            vx[i] += (tx[i] - x[i]) * kk * dt - vx[i] * D * dt
            vy[i] += (ty[i] - y[i]) * kk * dt - vy[i] * D * dt
            if (gaseous && !frozen) {
                vx[i] += (sin(phase * 1.7f + s1[i] * 9f) * 0.5f + (seeded() - 0.5f)) * dt * 0.6f
                vy[i] += (cos(phase * 1.3f + s2[i] * 7f) * 0.5f + (seeded() - 0.5f)) * dt * 0.6f
            }
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
        }

        if (vis == Vis.THINKING && blend[Form.BRAIN.ordinal] > 0.7f) {
            localAcc += dt * (3f + think * 26f)
            while (localAcc >= 1f) {
                localAcc -= 1f
                if (pulses.size < 90) {
                    val inner = 1 + (rand() * (RING_COUNTS[1] + RING_COUNTS[2])).toInt()
                    val hop = if (g.children[inner].isNotEmpty() && rand() < 0.6f)
                        g.children[inner][(rand() * g.children[inner].size).toInt().coerceAtMost(g.children[inner].size - 1)]
                    else if (g.parent[inner] == -1) 0 else g.parent[inner]
                    pulses.add(Pulse(intArrayOf(inner, hop), 0, 0f, 7f, 0.3f + think * 0.7f))
                }
            }
            if (rand() < dt * 1.2f && pulses.size < 90) {
                val path = if (rand() < 0.5f) pathInward(g, g.cortex[(rand() * g.cortex.size).toInt().coerceAtMost(g.cortex.size - 1)])
                else pathOutward(g, rand)
                if (path.size >= 2) pulses.add(Pulse(path, 0, 0f, 6f, 0.4f + think * 0.5f))
            }
        } else {
            localAcc = 0f
            if (blend[Form.BRAIN.ordinal] < 0.3f) pulses.clear()
        }
        val it = pulses.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.t += p.speed * dt
            while (p.t >= 1f) {
                p.t -= 1f; p.seg += 1
                if (p.seg < p.path.size) { val a = p.path[p.seg]; flash[a] = min(1f, flash[a] + 0.55f * p.intensity) }
                if (p.seg >= p.path.size - 1) break
            }
            if (p.seg >= p.path.size - 1) it.remove()
        }
        for (i in 0 until GRAPH_N) flash[i] = max(0f, flash[i] - dt * 2.2f)
        this.energyOut = energy; this.breatheOut = breathe; this.thinkOut = think; this.visNow = vis
    }

    var energyOut = 0f; var breatheOut = 0f; var thinkOut = 0f; var visNow = Vis.IDLE

    fun nodeRadius(i: Int) = if (i < GRAPH_N) g.nr[i] else 1.4f + s1[i] * 1.2f
    fun particleBright(i: Int): Float {
        val n = if (i < GRAPH_N) i else -1
        val inner = if (visNow == Vis.THINKING && n >= 0 && g.depth[n] <= 2)
            thinkOut * (0.3f + 0.7f * abs(sin(phase * 9f + g.phase[n] * 5f))) else 0f
        val fl = if (n >= 0) flash[n] else 0f
        return clamp01((0.2f + breatheOut * 0.12f + energyOut * 0.15f + fl + inner + morphGlow * 0.45f) * glow[i])
    }

    private fun setTargets(form: Form, i: Int, drift: Float) = when (form) {
        Form.SMOKE -> smokeTarget(i, false)
        Form.LEAN -> smokeTarget(i, true)
        Form.BRAIN -> brainTarget(i, drift)
        Form.GENIE -> genieTarget(i)
    }

    private fun smokeTarget(i: Int, lean: Boolean) {
        val u = ((i.toFloat() / TOTAL + s1[i]) % 1f + phase * 0.028f) % 1f
        var yy = 0.78f - u * 1.5f
        val spread = (0.1f + u * 0.34f) * (if (lean) 0.55f else 1f)
        var xx = sin(phase * 0.6f + u * 7f + s2[i] * 6.28f) * spread + (s1[i] - 0.5f) * 0.2f * (0.3f + u)
        if (lean) {
            val kk = 1f - mic * 0.22f
            xx = (xx + (yy - 0.2f) * -0.38f) * kk
            yy = (yy * 0.72f + 0.14f) * kk
        }
        tx[i] = xx; ty[i] = yy
        glow[i] = (0.5f + s2[i] * 0.5f) * (1f - u * 0.55f) + (if (lean) mic * 0.5f else 0f)
    }

    private fun brainTarget(i: Int, drift: Float) {
        if (i < GRAPH_N) {
            tx[i] = g.nx[i] + cos(phase * 0.5f + g.phase[i]) * drift * 24f
            ty[i] = g.ny[i] + sin(phase * 0.45f + g.phase[i] * 1.3f) * drift * 24f
            glow[i] = 1f
        } else {
            val ang = s1[i] * (PI * 2).toFloat() + phase * 0.1f * (if (s2[i] > 0.5f) 1f else -1f)
            val rad = 1.04f + s2[i] * 0.12f
            tx[i] = cos(ang) * rad; ty[i] = sin(ang) * rad; glow[i] = 0.16f
        }
    }

    private fun genieTarget(i: Int) {
        val sway = sin(phase * 1.1f) * 0.02f
        when {
            i < 12 -> {
                val ang = (i.toFloat() / 12) * (PI * 2).toFloat() + phase * 0.15f
                tx[i] = sway + cos(ang) * 0.13f; ty[i] = -0.52f + sin(ang) * 0.13f; glow[i] = 0.9f + bot * 0.4f
            }
            i < 36 -> {
                val r = sqrt(s1[i]); val ang = i * 2.39996f + phase * 0.05f
                tx[i] = sway + cos(ang) * r * 0.3f; ty[i] = -0.18f + sin(ang) * r * 0.2f; glow[i] = 0.55f + bot * 0.5f
            }
            i < 60 -> {
                val left = i < 48; val kk = (if (left) i - 36 else i - 48) / 12f; val dir = if (left) -1f else 1f
                val raise = bot * 0.45f + sin(phase * 2.4f + (if (left) 0f else 1.7f)) * 0.1f * (0.25f + bot)
                val sx = sway + dir * 0.26f; val sy = -0.26f
                val ex = sway + dir * (0.44f + raise * 0.1f); val ey = 0f - raise * 0.42f
                val mx = sway + dir * 0.42f; val my = -0.16f - raise * 0.18f
                val a = 1f - kk
                tx[i] = a * a * sx + 2 * a * kk * mx + kk * kk * ex + (s1[i] - 0.5f) * 0.03f
                ty[i] = a * a * sy + 2 * a * kk * my + kk * kk * ey + (s2[i] - 0.5f) * 0.03f
                glow[i] = 0.5f + bot * 0.6f
            }
            i < 80 -> {
                val r = sqrt(s2[i]); val ang = (i - 60) * 2.39996f - phase * 0.08f
                tx[i] = sway + cos(ang) * r * 0.2f; ty[i] = 0.12f + sin(ang) * r * 0.15f; glow[i] = 0.45f
            }
            else -> {
                val u = (i - 80).toFloat() / (TOTAL - 80)
                val ang = phase * 1.7f + u * 7.5f + s1[i] * 0.6f
                val rad = 0.3f * (1f - u * 0.85f) * (0.85f + s2[i] * 0.3f)
                tx[i] = sway + cos(ang) * rad; ty[i] = 0.26f + u * 0.55f + sin(phase * 2f + u * 9f) * 0.02f
                glow[i] = 0.5f * (1f - u * 0.4f) + bot * 0.25f
            }
        }
    }
}

/** Additive radial glow disc — the "lighter"-composited luminous look from the web canvas. */
private fun DrawScope.glow(center: Offset, radius: Float, color: FloatArray, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0.5f) return
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to colorOf(color, alpha),
            0.5f to colorOf(color, alpha * 0.3f),
            1.0f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = BlendMode.Plus,
    )
}

@Composable
fun NeuralBrain(
    state: VoiceState,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    reducedMotion: Boolean = false,
) {
    val brain = remember { Brain() }
    val sig = remember { Signals() }
    val stateNow = rememberUpdatedState(state)
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                val s = stateNow.value
                val vis = visOf(s)
                sig.mic = if (s is VoiceState.Hearing) s.level else 0f
                sig.bot = if (vis == Vis.SPEAKING) 0.45f + 0.35f * (0.5f + 0.5f * sin(brain.phase * 6f)) else 0f
                sig.think = if (vis == Vis.THINKING) 0.5f + 0.5f * (0.5f + 0.5f * sin(brain.phase * 4f)) else 0f
                brain.update(dt, vis, sig)
                tick = now
            }
        }
    }

    val vis = visOf(state)
    val description = orbContentDescription(state)

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        tick // observe the frame clock so the canvas redraws each frame
        if (brain.energyOut == 0f && brain.phase == 0f) brain.update(0.016f, vis, sig) // first paint under reduced-motion
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        val r = this.size.minDimension * 0.40f
        val sizeScale = this.size.minDimension / 200f
        fun px(i: Int) = cx + brain.x[i] * r
        fun py(i: Int) = cy + brain.y[i] * r
        fun ux(v: Float) = cx + v * r
        fun uy(v: Float) = cy + v * r
        val bBrain = brain.blend[Form.BRAIN.ordinal]

        if (bBrain > 0.02f) {
            for (e in brain.g.edges) {
                val a = e[0]; val b = e[1]
                val alpha = ((if (e[2] == 1) 0.1f else 0.05f) + brain.energyOut * 0.08f +
                    max(brain.flash[a], brain.flash[b]) * 0.25f) * bBrain
                drawLine(
                    color = colorOf(brain.pEdge, alpha),
                    start = Offset(px(a), py(a)), end = Offset(px(b), py(b)),
                    strokeWidth = 0.7f, blendMode = BlendMode.Plus,
                )
            }
        }

        for (p in brain.pulses) {
            if (p.seg >= p.path.size - 1) continue
            val a = p.path[p.seg]; val b = p.path[p.seg + 1]
            val hx = ux(lerp(brain.x[a], brain.x[b], p.t)); val hy = uy(lerp(brain.y[a], brain.y[b], p.t))
            val tail = max(0f, p.t - 0.35f)
            drawLine(
                color = colorOf(brain.pPulse, 0.35f * p.intensity * bBrain),
                start = Offset(ux(lerp(brain.x[a], brain.x[b], tail)), uy(lerp(brain.y[a], brain.y[b], tail))),
                end = Offset(hx, hy), strokeWidth = 1.1f, blendMode = BlendMode.Plus,
            )
            glow(Offset(hx, hy), (1.2f + p.intensity * 1.6f) * 3f, brain.pPulse, clamp01(p.intensity * bBrain))
        }

        for (i in 0 until TOTAL) {
            val bright = brain.particleBright(i)
            if (bright < 0.01f) continue
            val baseR = brain.nodeRadius(i) * max(0.7f, sizeScale)
            val rr = baseR * (0.8f + bright * 0.6f) * 2.6f
            glow(Offset(px(i), py(i)), rr, brain.pNode, bright)
        }

        if (bBrain > 0.02f) {
            val coreFlicker = if (brain.visNow == Vis.THINKING) brain.thinkOut * (0.5f + 0.5f * sin(brain.phase * 13f)) else 0f
            val coreGlow = clamp01(0.35f + brain.breatheOut * 0.15f + brain.energyOut * 0.45f + coreFlicker * 0.4f) * bBrain
            glow(Offset(cx, cy), r * (0.13f + coreGlow * 0.07f) * 3f, brain.pCore, min(1f, coreGlow + 0.2f * bBrain))
        }
    }
}
