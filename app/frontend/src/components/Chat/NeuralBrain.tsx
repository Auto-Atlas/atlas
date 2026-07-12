// The JARVIS avatar — one pool of ~128 glowing particles that physically
// MORPHS between forms depending on what he's actually doing:
//
//   idle         : a calm plume of drifting smoke (ambient, breathing)
//   listening    : the smoke condenses and leans in toward you, pulsing
//                  with your true mic RMS (`mic_level`) + Silero VAD
//   thinking     : particles snap into the neural brain — the original
//                  force-directed graph with synapses; flicker intensity
//                  tracks actual LLM stream ticks (`token` events)
//   speaking     : a genie — head, chest, gesturing arms rising out of a
//                  rotating vapor vortex; gestures scale with true TTS RMS
//   working      : a stick figure that walks across the panel while a real
//                  tool call is running (off on the task)
//   disconnected : near-static dim haze. No socket, no motion — that's the
//                  proof this is wired to reality and not a scripted loop.
//
// Transformations are cinematic (~1s): on every state change the particles
// burst apart with a glow flash, then spring to their new positions with a
// per-particle stagger so the new shape assembles organically. There are NO
// free-running fake activity loops: when the bridge stops sending events for
// >2.5s the level-driven states decay to idle (the `working` form is exempt —
// a long tool run is legitimately quiet on the wire between call and result).
import { useEffect, useRef } from 'react';
import type { JarvisLiveSignals } from '../../hooks/useJarvisBridge';

export type BrainState =
  | 'disconnected'
  | 'idle'
  | 'listening'
  | 'thinking'
  | 'speaking'
  | 'working';

type Form = 'smoke' | 'lean' | 'brain' | 'genie' | 'walker';

const FORM_OF: Record<BrainState, Form> = {
  disconnected: 'smoke',
  // The resting form IS the brain — the violet-on-obsidian look, calm and dim;
  // states still recolor it (cyan in, gold out) so the feedback stays legible.
  idle: 'brain',
  listening: 'brain',
  thinking: 'brain',
  speaking: 'genie',
  working: 'walker',
};

interface Props {
  state: BrainState;
  /** Render-free live signals from useJarvisBridge — read every frame. */
  signals: React.MutableRefObject<JarvisLiveSignals>;
  /** Side length in CSS pixels. */
  size?: number;
}

type RGB = [number, number, number];

interface Palette {
  node: RGB;
  edge: RGB;
  pulse: RGB;
  core: RGB;
}

const PALETTES: Record<BrainState, Palette> = {
  disconnected: { node: [100, 108, 122], edge: [70, 76, 90], pulse: [110, 118, 132], core: [96, 104, 118] },
  idle: { node: [126, 96, 212], edge: [80, 58, 152], pulse: [170, 140, 240], core: [142, 106, 232] },
  listening: { node: [167, 139, 250], edge: [110, 80, 200], pulse: [221, 200, 255], core: [192, 132, 252] },
  thinking: { node: [167, 139, 250], edge: [110, 80, 200], pulse: [221, 200, 255], core: [192, 132, 252] },
  speaking: { node: [245, 178, 64], edge: [190, 120, 36], pulse: [255, 226, 150], core: [251, 191, 36] },
  working: { node: [52, 211, 153], edge: [22, 140, 100], pulse: [150, 255, 210], core: [16, 185, 129] },
};

const rgba = (c: RGB, a: number) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const clamp01 = (v: number) => (v < 0 ? 0 : v > 1 ? 1 : v);

// ---------------------------------------------------------------------------
// Pre-rendered glow sprites. A radial gradient is baked once at full intensity
// into a small offscreen canvas; per-draw intensity is globalAlpha and per-draw
// radius is the drawImage size. Under 'lighter' compositing that is
// pixel-equivalent to the per-particle createRadialGradient it replaces
// (scaling every stop's alpha by k == drawing the baked sprite at alpha k),
// without building hundreds of gradients per frame.
// ---------------------------------------------------------------------------

const GLOW_SPRITE_R = 64; // sprite radius in px — particles draw at <=~30px, so always supersampled

type GlowStops = Array<[number, number]>; // [offset, alpha] pairs

function paintGlowSprite(target: HTMLCanvasElement, color: RGB, stops: GlowStops) {
  const c2 = target.getContext('2d');
  if (!c2) return;
  c2.clearRect(0, 0, target.width, target.height);
  const g = c2.createRadialGradient(
    GLOW_SPRITE_R, GLOW_SPRITE_R, 0,
    GLOW_SPRITE_R, GLOW_SPRITE_R, GLOW_SPRITE_R,
  );
  for (const [off, a] of stops) g.addColorStop(off, rgba(color, a));
  c2.fillStyle = g;
  c2.fillRect(0, 0, target.width, target.height);
}

function makeGlowSprite(color: RGB, stops: GlowStops): HTMLCanvasElement {
  const cnv = document.createElement('canvas');
  cnv.width = cnv.height = GLOW_SPRITE_R * 2;
  paintGlowSprite(cnv, color, stops);
  return cnv;
}

// Stop profiles match the gradients they replaced exactly.
const NODE_GLOW_STOPS: GlowStops = [[0, 1], [0.5, 0.3], [1, 0]];
const PULSE_GLOW_STOPS: GlowStops = [[0, 0.9], [0.4, 0.35], [1, 0]];

const colorKey = (c: RGB) => ((c[0] | 0) << 16) | ((c[1] | 0) << 8) | (c[2] | 0);

// Deterministic RNG so the avatar has the same anatomy every mount.
function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------------------
// Brain anatomy (the thinking form) — unchanged from the original neural graph.
// ---------------------------------------------------------------------------

interface Neuron {
  x: number;
  y: number;
  depth: number;
  parent: number;
  children: number[];
  r: number;
  phase: number;
  flash: number;
}

interface Edge {
  a: number;
  b: number;
  trunk: boolean;
}

interface Pulse {
  path: number[];
  seg: number;
  t: number;
  speed: number;
  intensity: number;
  color: RGB;
  /** Pre-rendered glow head for this pulse's color (snapshotted at spawn). */
  sprite: HTMLCanvasElement;
}

interface Graph {
  neurons: Neuron[];
  edges: Edge[];
  cortex: number[];
}

const RING_COUNTS = [1, 7, 14, 24, 36];
const RING_RADII = [0, 0.24, 0.46, 0.7, 0.94];
const GRAPH_N = RING_COUNTS.reduce((s, n) => s + n, 0); // 82
const TOTAL = 128; // morph particle pool; 82 brain neurons + 46 vapor extras

function buildGraph(): Graph {
  const rand = mulberry32(0x1a2b3c);
  const neurons: Neuron[] = [];
  const edges: Edge[] = [];
  const ringStart: number[] = [];

  for (let ring = 0; ring < RING_COUNTS.length; ring++) {
    ringStart.push(neurons.length);
    const count = RING_COUNTS[ring];
    for (let i = 0; i < count; i++) {
      const baseAng = (i / count) * Math.PI * 2 + ring * 0.7;
      const ang = baseAng + (rand() - 0.5) * (ring === 0 ? 0 : 0.5);
      const rad = RING_RADII[ring] * (ring === 0 ? 0 : 0.88 + rand() * 0.24);
      neurons.push({
        x: Math.cos(ang) * rad,
        y: Math.sin(ang) * rad,
        depth: ring,
        parent: -1,
        children: [],
        r: ring === 0 ? 3.2 : 2.6 - ring * 0.38 + rand() * 0.8,
        phase: rand() * Math.PI * 2,
        flash: 0,
      });
    }
  }

  for (let ring = 1; ring < RING_COUNTS.length; ring++) {
    const start = ringStart[ring];
    const prevStart = ringStart[ring - 1];
    const prevEnd = prevStart + RING_COUNTS[ring - 1];
    for (let i = start; i < start + RING_COUNTS[ring]; i++) {
      let best = prevStart;
      let bestD = Infinity;
      for (let j = prevStart; j < prevEnd; j++) {
        const dx = neurons[i].x - neurons[j].x;
        const dy = neurons[i].y - neurons[j].y;
        const d = dx * dx + dy * dy;
        if (d < bestD) {
          bestD = d;
          best = j;
        }
      }
      neurons[i].parent = best;
      neurons[best].children.push(i);
      edges.push({ a: best, b: i, trunk: true });
    }
  }

  for (let ring = 1; ring < RING_COUNTS.length; ring++) {
    const start = ringStart[ring];
    const end = start + RING_COUNTS[ring];
    for (let i = start; i < end; i++) {
      let best = -1;
      let bestD = Infinity;
      for (let j = start; j < end; j++) {
        if (j === i) continue;
        const dx = neurons[i].x - neurons[j].x;
        const dy = neurons[i].y - neurons[j].y;
        const d = dx * dx + dy * dy;
        if (d < bestD) {
          bestD = d;
          best = j;
        }
      }
      if (best >= 0 && !edges.some((e) => (e.a === i && e.b === best) || (e.a === best && e.b === i))) {
        edges.push({ a: i, b: best, trunk: false });
      }
    }
  }

  for (let iter = 0; iter < 120; iter++) {
    for (let i = 1; i < neurons.length; i++) {
      let fx = 0;
      let fy = 0;
      for (let j = 1; j < neurons.length; j++) {
        if (j === i) continue;
        const dx = neurons[i].x - neurons[j].x;
        const dy = neurons[i].y - neurons[j].y;
        const d2 = dx * dx + dy * dy + 0.0008;
        const f = 0.00035 / d2;
        fx += dx * f;
        fy += dy * f;
      }
      const p = neurons[neurons[i].parent === -1 ? 0 : neurons[i].parent];
      fx += (p.x - neurons[i].x) * 0.012;
      fy += (p.y - neurons[i].y) * 0.012;
      const targetRad = RING_RADII[neurons[i].depth];
      const rad = Math.hypot(neurons[i].x, neurons[i].y) || 1e-6;
      const radial = (targetRad - rad) * 0.05;
      fx += (neurons[i].x / rad) * radial;
      fy += (neurons[i].y / rad) * radial;
      neurons[i].x += fx;
      neurons[i].y += fy;
    }
  }

  const cortexStart = ringStart[RING_COUNTS.length - 1];
  const cortex = Array.from({ length: RING_COUNTS[RING_COUNTS.length - 1] }, (_, k) => cortexStart + k);
  return { neurons, edges, cortex };
}

// The graph is deterministic (seeded RNG) and size-independent (unit space),
// so the O(n^2) relax loop runs once per app load, not on every mount/resize.
// Each mount gets its own shallow neuron copies because `flash` is mutated at
// runtime; geometry, edges and child lists are shared read-only.
let sharedGraph: Graph | null = null;

function instanceGraph(): Graph {
  if (!sharedGraph) sharedGraph = buildGraph();
  return {
    neurons: sharedGraph.neurons.map((n) => ({ ...n, flash: 0 })),
    edges: sharedGraph.edges,
    cortex: sharedGraph.cortex,
  };
}

function pathInward(g: Graph, from: number): number[] {
  const path = [from];
  let cur = from;
  while (g.neurons[cur].parent !== -1) {
    cur = g.neurons[cur].parent;
    path.push(cur);
  }
  return path;
}

function pathOutward(g: Graph, rand: () => number): number[] {
  const path = [0];
  let cur = 0;
  while (g.neurons[cur].children.length > 0) {
    cur = g.neurons[cur].children[(rand() * g.neurons[cur].children.length) | 0];
    path.push(cur);
  }
  return path;
}

// ---------------------------------------------------------------------------
// Morph particle pool + form target geometry (all in unit space, y down).
// ---------------------------------------------------------------------------

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  /** Stable per-particle randoms for shape jitter / role assignment. */
  s1: number;
  s2: number;
  /** Morph stagger delay (s) and whether this particle's burst fired yet. */
  delay: number;
  burst: boolean;
  /** Per-form brightness weight, written by the target functions. */
  glow: number;
}

/** Distribute `count` particles (starting at write[]) along a polyline chain. */
function alongChain(
  pts: Array<[number, number]>,
  count: number,
  out: Array<[number, number]>,
  jitter: number,
  s: (k: number) => number,
) {
  let total = 0;
  const segLen: number[] = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const l = Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]);
    segLen.push(l);
    total += l;
  }
  for (let k = 0; k < count; k++) {
    let d = (k / Math.max(1, count - 1)) * total;
    let seg = 0;
    while (seg < segLen.length - 1 && d > segLen[seg]) {
      d -= segLen[seg];
      seg++;
    }
    const t = segLen[seg] > 0 ? d / segLen[seg] : 0;
    const x = lerp(pts[seg][0], pts[seg + 1][0], t) + (s(k) - 0.5) * jitter;
    const y = lerp(pts[seg][1], pts[seg + 1][1], t) + (s(k + 31) - 0.5) * jitter;
    out.push([x, y]);
  }
}

export function NeuralBrain({ state, signals, size = 104 }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stateRef = useRef(state);
  stateRef.current = state;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = size * dpr;
    canvas.height = size * dpr;
    ctx.scale(dpr, dpr);

    const graph = instanceGraph();
    const rand = mulberry32(0xf00d);
    const seeded = mulberry32(0xbeef);
    const pulses: Pulse[] = [];
    const cx = size / 2;
    const cy = size / 2;
    const R = size * 0.40;

    // The particle pool. Brain neurons claim the first GRAPH_N slots so the
    // thinking form is literally the original graph; the rest are vapor.
    const parts: Particle[] = Array.from({ length: TOTAL }, (_, i) => ({
      x: (seeded() - 0.5) * 1.6,
      y: (seeded() - 0.5) * 1.6,
      vx: 0,
      vy: 0,
      s1: seeded(),
      s2: seeded(),
      delay: (i % 17) * 0.018, // 0..~0.29s assembly ripple
      burst: false,
      glow: 1,
    }));

    // Smoothed live levels + spawn accumulator for thinking pulses.
    let mic = 0;
    let bot = 0;
    let think = 0;
    let energy = 0;
    let localAcc = 0;
    // Form blends (0..1 per form) drive edges/bones/core cross-fades.
    const blend: Record<Form, number> = { smoke: 1, lean: 0, brain: 0, genie: 0, walker: 0 };
    let curForm: Form = 'smoke';
    let prevForm: Form = 'smoke';
    let morphAt = -10; // time of last form change
    let morphGlow = 0;

    const pal: Palette = {
      node: [...PALETTES.idle.node] as RGB,
      edge: [...PALETTES.idle.edge] as RGB,
      pulse: [...PALETTES.idle.pulse] as RGB,
      core: [...PALETTES.idle.core] as RGB,
    };

    let raf = 0;
    let last = performance.now();
    let phase = 0;

    // Reused scratch arrays for form targets (avoid per-frame allocation churn).
    const targets: Array<[number, number]> = Array.from({ length: TOTAL }, () => [0, 0]);
    const glows: number[] = new Array(TOTAL).fill(1);
    const scratch: Array<[number, number]> = [];
    // Walker skeleton joints, recomputed once per frame, also used for bone strokes.
    let bones: Array<Array<[number, number]>> = [];

    // Glow sprites: one reusable node sprite repainted only when the lerped
    // palette color actually changes, plus a small cache for pulse colors
    // (snapshotted per spawn, so a handful of near-identical entries).
    const nodeSprite = makeGlowSprite(pal.node, NODE_GLOW_STOPS);
    let nodeSpriteKey = colorKey(pal.node);
    const pulseSprites = new Map<number, HTMLCanvasElement>();
    const pulseSpriteFor = (c: RGB): HTMLCanvasElement => {
      const key = colorKey(c);
      let spr = pulseSprites.get(key);
      if (!spr) {
        if (pulseSprites.size > 64) pulseSprites.clear();
        spr = makeGlowSprite(c, PULSE_GLOW_STOPS);
        pulseSprites.set(key, spr);
      }
      return spr;
    };

    // ---- form target generators ------------------------------------------

    const smokeTarget = (i: number, t: number, lean: boolean, micLvl: number) => {
      const p = parts[i];
      const u = ((i / TOTAL + p.s1) % 1 + t * 0.028) % 1; // perpetual rise
      let yy = 0.78 - u * 1.5;
      const spread = (0.1 + u * 0.34) * (lean ? 0.55 : 1);
      let xx = Math.sin(t * 0.6 + u * 7 + p.s2 * 6.28) * spread + (p.s1 - 0.5) * 0.2 * (0.3 + u);
      if (lean) {
        // Condense and tip toward the mic (the user): shear + crouch, and
        // contract slightly on real voice level so it visibly reacts to you.
        const k = 1 - micLvl * 0.22;
        xx = (xx + (yy - 0.2) * -0.38) * k;
        yy = (yy * 0.72 + 0.14) * k;
      }
      targets[i][0] = xx;
      targets[i][1] = yy;
      glows[i] = (0.5 + p.s2 * 0.5) * (1 - u * 0.55) + (lean ? micLvl * 0.5 : 0);
    };

    const brainTarget = (i: number, t: number, drift: number) => {
      if (i < GRAPH_N) {
        const n = graph.neurons[i];
        targets[i][0] = n.x + Math.cos(t * 0.5 + n.phase) * drift * 24;
        targets[i][1] = n.y + Math.sin(t * 0.45 + n.phase * 1.3) * drift * 24;
        glows[i] = 1;
      } else {
        // Vapor extras orbit just outside the cortex as a faint halo.
        const p = parts[i];
        const ang = p.s1 * Math.PI * 2 + t * 0.1 * (p.s2 > 0.5 ? 1 : -1);
        const rad = 1.04 + p.s2 * 0.12;
        targets[i][0] = Math.cos(ang) * rad;
        targets[i][1] = Math.sin(ang) * rad;
        glows[i] = 0.16;
      }
    };

    const genieTarget = (i: number, t: number, botLvl: number) => {
      const p = parts[i];
      const sway = Math.sin(t * 1.1) * 0.02;
      if (i < 12) {
        // Head ring.
        const ang = (i / 12) * Math.PI * 2 + t * 0.15;
        targets[i][0] = sway + Math.cos(ang) * 0.13;
        targets[i][1] = -0.52 + Math.sin(ang) * 0.13;
        glows[i] = 0.9 + botLvl * 0.4;
      } else if (i < 36) {
        // Chest/shoulders mass.
        const k = i - 12;
        const r = Math.sqrt(p.s1);
        const ang = k * 2.39996 + t * 0.05;
        targets[i][0] = sway + Math.cos(ang) * r * 0.3;
        targets[i][1] = -0.18 + Math.sin(ang) * r * 0.2;
        glows[i] = 0.55 + botLvl * 0.5;
      } else if (i < 60) {
        // Arms: two bezier-ish chains that gesture with the real TTS level.
        const left = i < 48;
        const k = (left ? i - 36 : i - 48) / 12;
        const dir = left ? -1 : 1;
        const raise = botLvl * 0.45 + Math.sin(t * 2.4 + (left ? 0 : 1.7)) * 0.1 * (0.25 + botLvl);
        const sx = sway + dir * 0.26;
        const sy = -0.26;
        const ex = sway + dir * (0.44 + raise * 0.1);
        const ey = 0.0 - raise * 0.42;
        const mx = sway + dir * 0.42;
        const my = -0.16 - raise * 0.18;
        // Quadratic bezier shoulder -> elbow(ctrl) -> hand.
        const a = 1 - k;
        targets[i][0] = a * a * sx + 2 * a * k * mx + k * k * ex + (p.s1 - 0.5) * 0.03;
        targets[i][1] = a * a * sy + 2 * a * k * my + k * k * ey + (p.s2 - 0.5) * 0.03;
        glows[i] = 0.5 + botLvl * 0.6;
      } else if (i < 80) {
        // Waist taper.
        const k = i - 60;
        const r = Math.sqrt(p.s2);
        const ang = k * 2.39996 - t * 0.08;
        targets[i][0] = sway + Math.cos(ang) * r * 0.2;
        targets[i][1] = 0.12 + Math.sin(ang) * r * 0.15;
        glows[i] = 0.45;
      } else {
        // Rotating vapor vortex tapering to the lamp-point below.
        const u = (i - 80) / (TOTAL - 80);
        const ang = t * 1.7 + u * 7.5 + p.s1 * 0.6;
        const rad = 0.3 * (1 - u * 0.85) * (0.85 + p.s2 * 0.3);
        targets[i][0] = sway + Math.cos(ang) * rad;
        targets[i][1] = 0.26 + u * 0.55 + Math.sin(t * 2 + u * 9) * 0.02;
        glows[i] = 0.5 * (1 - u * 0.4) + botLvl * 0.25;
      }
    };

    // Walker skeleton, computed at most once per frame (memoized on t) — the
    // per-particle walkerTarget only reads these precomputed bones.
    let walkerSkelT = NaN;
    let walkerHipX = 0;
    let walkerFacing = 1;
    let walkerHeadC: [number, number] = [0, 0];
    let walkerSpine: Array<[number, number]> = [];
    let walkerChains: Array<Array<[number, number]>> = [];

    const walkerSkeleton = (t: number) => {
      if (t === walkerSkelT) return;
      walkerSkelT = t;
      // Ping-pong traverse with facing flip; classic 2-segment limb swing.
      const cycle = (t * 0.17) % 2;
      const posX = (cycle < 1 ? cycle : 2 - cycle) * 1.0 - 0.5;
      const facing = cycle < 1 ? 1 : -1;
      const w = t * 4.6;
      const bob = Math.abs(Math.sin(w)) * 0.035;
      const hipX = posX;
      const hipY = 0.12 - bob;
      const neck: [number, number] = [hipX, hipY - 0.42];
      const headC: [number, number] = [hipX + facing * 0.015, hipY - 0.54];
      const sh: [number, number] = [hipX, hipY - 0.38];
      const legSwing = Math.sin(w) * 0.5;
      const armSwing = Math.sin(w + Math.PI) * 0.45;

      const limb = (origin: [number, number], swing: number, upper: number, lower: number, bend: number): Array<[number, number]> => {
        const a1 = Math.PI / 2 + swing * facing; // from straight-down
        const kx = origin[0] + Math.cos(a1) * upper;
        const ky = origin[1] + Math.sin(a1) * upper;
        const a2 = a1 + bend * facing;
        return [origin, [kx, ky], [kx + Math.cos(a2) * lower, ky + Math.sin(a2) * lower]];
      };

      const legL = limb([hipX, hipY], legSwing, 0.26, 0.26, Math.max(0, -Math.sin(w)) * 0.8);
      const legR = limb([hipX, hipY], -legSwing, 0.26, 0.26, Math.max(0, Math.sin(w)) * 0.8);
      const armL = limb(sh, armSwing, 0.2, 0.18, 0.35);
      const armR = limb(sh, -armSwing, 0.2, 0.18, 0.35);
      const spine: Array<[number, number]> = [neck, [hipX, hipY]];
      walkerHipX = hipX;
      walkerFacing = facing;
      walkerHeadC = headC;
      walkerSpine = spine;
      walkerChains = [armL, armR, legL, legR];
      bones = [spine, legL, legR, armL, armR];
    };

    const walkerTarget = (i: number, t: number) => {
      walkerSkeleton(t);
      const p = parts[i];
      if (i < 10) {
        const ang = (i / 10) * Math.PI * 2 + t * 0.3;
        targets[i][0] = walkerHeadC[0] + Math.cos(ang) * 0.095;
        targets[i][1] = walkerHeadC[1] + Math.sin(ang) * 0.095;
        glows[i] = 1;
      } else if (i < 20) {
        scratch.length = 0;
        alongChain(walkerSpine, 10, scratch, 0.012, (k) => ((i + k) % 7) / 7);
        const c = scratch[i - 10];
        targets[i][0] = c[0];
        targets[i][1] = c[1];
        glows[i] = 0.85;
      } else if (i < 52) {
        const chain = walkerChains[((i - 20) / 8) | 0];
        const k = (i - 20) % 8;
        scratch.length = 0;
        alongChain(chain, 8, scratch, 0.012, (q) => ((i + q) % 5) / 5);
        const c = scratch[k];
        targets[i][0] = c[0];
        targets[i][1] = c[1];
        glows[i] = 0.85;
      } else {
        // Dust trail drifting behind the walker, fading with distance.
        const u = (i - 52) / (TOTAL - 52);
        targets[i][0] = walkerHipX - walkerFacing * (0.14 + u * 0.55) + (p.s1 - 0.5) * 0.1;
        targets[i][1] = 0.55 + (p.s2 - 0.5) * 0.25 - u * 0.1;
        glows[i] = 0.25 * (1 - u);
      }
    };

    const setTargets = (form: Form, i: number, t: number, drift: number) => {
      switch (form) {
        case 'smoke':
          smokeTarget(i, t, false, 0);
          break;
        case 'lean':
          smokeTarget(i, t, true, mic);
          break;
        case 'brain':
          brainTarget(i, t, drift);
          break;
        case 'genie':
          genieTarget(i, t, bot);
          break;
        case 'walker':
          walkerTarget(i, t);
          break;
      }
    };

    const spawn = (path: number[], intensity: number, color: RGB, speed: number) => {
      if (pulses.length >= 90 || path.length < 2) return;
      pulses.push({ path, seg: 0, t: 0, speed, intensity, color, sprite: pulseSpriteFor(color) });
    };

    const draw = (now: number) => {
      const dt = Math.min(64, now - last) / 1000;
      last = now;
      phase += dt;

      const s = signals.current;
      // Liveness gate for level-driven states. `working` is exempt: a long
      // tool run is legitimately silent between the call and result events.
      const stale = now - s.lastEventAt > 2500;
      const propState = stateRef.current;
      const st: BrainState =
        propState === 'disconnected'
          ? 'disconnected'
          : stale && propState !== 'working'
            ? 'idle'
            : propState;

      const micTarget = st === 'listening' && !stale ? s.micLevel : 0;
      const botTarget = st === 'speaking' && !stale ? s.botLevel : 0;
      const thinkTarget = st === 'thinking' && !stale ? clamp01(1 - (now - s.lastTokenAt) / 600) : 0;
      mic += (micTarget - mic) * Math.min(1, dt * 10);
      bot += (botTarget - bot) * Math.min(1, dt * 10);
      think += (thinkTarget - think) * Math.min(1, dt * 8);

      const targetPal = PALETTES[st];
      const k = Math.min(1, dt * 5);
      (['node', 'edge', 'pulse', 'core'] as const).forEach((key) => {
        for (let c = 0; c < 3; c++) pal[key][c] = lerp(pal[key][c], targetPal[key][c], k);
      });

      // Repaint the node glow sprite only when the lerped color actually moved
      // (rgba() floors channels, so this matches the old per-particle output).
      const nodeKey = colorKey(pal.node);
      if (nodeKey !== nodeSpriteKey) {
        nodeSpriteKey = nodeKey;
        paintGlowSprite(nodeSprite, pal.node, NODE_GLOW_STOPS);
      }

      const breathe = st === 'disconnected' ? 0 : 0.5 + 0.5 * Math.sin(phase * (st === 'idle' ? 0.9 : 1.6));
      const activity = Math.max(mic, bot, think);
      energy += (Math.min(1, activity * 0.85 + breathe * 0.15) - energy) * Math.min(1, dt * 6);
      const drift = st === 'disconnected' ? 0.004 : 0.012 + energy * 0.02;

      // ---- form change: start the cinematic morph -------------------------
      const wantForm = FORM_OF[st];
      if (wantForm !== curForm) {
        prevForm = curForm;
        curForm = wantForm;
        morphAt = phase;
        morphGlow = 1;
        for (const p of parts) p.burst = false;
      }
      morphGlow = Math.max(0, morphGlow - dt * 1.2);
      for (const f of Object.keys(blend) as Form[]) {
        blend[f] += ((f === curForm ? 1 : 0) - blend[f]) * Math.min(1, dt * 3.2);
      }

      // ---- particle dynamics ----------------------------------------------
      // Gaseous forms get loose springs + noise; solid forms snap crisp.
      const gaseous = curForm === 'smoke' || curForm === 'lean';
      const K = gaseous ? 16 : 36;
      const D = gaseous ? 4.5 : 7.5;
      const frozen = st === 'disconnected';

      for (let i = 0; i < TOTAL; i++) {
        const p = parts[i];
        const sinceMorph = phase - morphAt;
        const inOldForm = sinceMorph < p.delay;
        setTargets(inOldForm ? prevForm : curForm, i, phase, drift);
        if (!inOldForm && !p.burst) {
          // This particle's moment in the assembly ripple: burst apart, then
          // the spring pulls it into the new shape.
          p.burst = true;
          p.vx += (seeded() - 0.5) * 2.6;
          p.vy += (seeded() - 0.5) * 2.6;
        }
        const tx = targets[i][0];
        const ty = targets[i][1];
        const kk = frozen ? K * 0.25 : K;
        p.vx += (tx - p.x) * kk * dt - p.vx * D * dt;
        p.vy += (ty - p.y) * kk * dt - p.vy * D * dt;
        if (gaseous && !frozen) {
          p.vx += (Math.sin(phase * 1.7 + p.s1 * 9) * 0.5 + (seeded() - 0.5)) * dt * 0.6;
          p.vy += (Math.cos(phase * 1.3 + p.s2 * 7) * 0.5 + (seeded() - 0.5)) * dt * 0.6;
        }
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.glow = glows[i];
      }

      // ---- thinking pulses (brain form only) ------------------------------
      if (st === 'thinking' && blend.brain > 0.7) {
        localAcc += dt * (3 + think * 26);
        while (localAcc >= 1) {
          localAcc -= 1;
          const inner = 1 + ((rand() * (RING_COUNTS[1] + RING_COUNTS[2])) | 0);
          const n = graph.neurons[inner];
          const hop = n.children.length && rand() < 0.6 ? n.children[(rand() * n.children.length) | 0] : n.parent === -1 ? 0 : n.parent;
          spawn([inner, hop], 0.3 + think * 0.7, pal.pulse.slice() as RGB, 7);
        }
        // Occasional long inward/outward sweeps keep the brain feeling alive.
        if (rand() < dt * 1.2) {
          spawn(
            rand() < 0.5 ? pathInward(graph, graph.cortex[(rand() * graph.cortex.length) | 0]) : pathOutward(graph, rand),
            0.4 + think * 0.5,
            pal.pulse.slice() as RGB,
            6,
          );
        }
      } else {
        localAcc = 0;
        if (blend.brain < 0.3) pulses.length = 0;
      }

      // ---- render ----------------------------------------------------------
      ctx.clearRect(0, 0, size, size);
      ctx.globalCompositeOperation = 'lighter';

      const X = (ux: number) => cx + ux * R;
      const Y = (uy: number) => cy + uy * R;

      // Brain synapses, faded by how assembled the brain is.
      if (blend.brain > 0.02) {
        ctx.lineWidth = 0.7;
        for (const e of graph.edges) {
          const pa = parts[e.a];
          const pb = parts[e.b];
          const na = graph.neurons[e.a];
          const nb = graph.neurons[e.b];
          const alpha = ((e.trunk ? 0.1 : 0.05) + energy * 0.08 + Math.max(na.flash, nb.flash) * 0.25) * blend.brain;
          ctx.strokeStyle = rgba(pal.edge, alpha);
          ctx.beginPath();
          ctx.moveTo(X(pa.x), Y(pa.y));
          ctx.lineTo(X(pb.x), Y(pb.y));
          ctx.stroke();
        }
      }

      // Walker skeleton strokes + ground line, faded by walker assembly.
      if (blend.walker > 0.02 && bones.length) {
        ctx.lineWidth = 1.1;
        ctx.strokeStyle = rgba(pal.edge, 0.5 * blend.walker);
        for (const chain of bones) {
          ctx.beginPath();
          ctx.moveTo(X(chain[0][0]), Y(chain[0][1]));
          for (let j = 1; j < chain.length; j++) ctx.lineTo(X(chain[j][0]), Y(chain[j][1]));
          ctx.stroke();
        }
        ctx.lineWidth = 1;
        ctx.strokeStyle = rgba(pal.edge, 0.14 * blend.walker);
        ctx.beginPath();
        ctx.moveTo(X(-0.8), Y(0.66));
        ctx.lineTo(X(0.8), Y(0.66));
        ctx.stroke();
      }

      // Traveling pulses (brain form).
      for (let i = pulses.length - 1; i >= 0; i--) {
        const p = pulses[i];
        p.t += p.speed * dt;
        while (p.t >= 1) {
          p.t -= 1;
          p.seg += 1;
          if (p.seg < p.path.length) {
            const arrived = graph.neurons[p.path[p.seg]];
            arrived.flash = Math.min(1, arrived.flash + 0.55 * p.intensity);
          }
          if (p.seg >= p.path.length - 1) break;
        }
        if (p.seg >= p.path.length - 1) {
          pulses.splice(i, 1);
          continue;
        }
        const a = parts[p.path[p.seg]];
        const b = parts[p.path[p.seg + 1]];
        const x = X(lerp(a.x, b.x, p.t));
        const y = Y(lerp(a.y, b.y, p.t));
        const tail = Math.max(0, p.t - 0.35);
        ctx.strokeStyle = rgba(p.color, 0.35 * p.intensity * blend.brain);
        ctx.lineWidth = 1.1;
        ctx.beginPath();
        ctx.moveTo(X(lerp(a.x, b.x, tail)), Y(lerp(a.y, b.y, tail)));
        ctx.lineTo(x, y);
        ctx.stroke();
        const pr = (1.2 + p.intensity * 1.6) * 3;
        ctx.globalAlpha = clamp01(p.intensity * blend.brain);
        ctx.drawImage(p.sprite, x - pr, y - pr, pr * 2, pr * 2);
        ctx.globalAlpha = 1;
      }

      // Particles.
      const sizeScale = size / 200; // glow radii tuned at ~200px reference
      for (let i = 0; i < TOTAL; i++) {
        const p = parts[i];
        const n = i < GRAPH_N ? graph.neurons[i] : null;
        if (n) n.flash = Math.max(0, n.flash - dt * 2.2);
        const x = X(p.x);
        const y = Y(p.y);
        const innerFlicker =
          st === 'thinking' && n && n.depth <= 2
            ? think * (0.3 + 0.7 * Math.abs(Math.sin(phase * 9 + n.phase * 5)))
            : 0;
        const bright = clamp01(
          (0.2 + breathe * 0.12 + energy * 0.15 + (n ? n.flash : 0) + innerFlicker + morphGlow * 0.45) * p.glow,
        );
        if (bright < 0.01) continue;
        const baseR = (n ? n.r : 1.4 + p.s1 * 1.2) * Math.max(0.7, sizeScale);
        const r = baseR * (0.8 + bright * 0.6) * 2.6;
        ctx.globalAlpha = bright;
        ctx.drawImage(nodeSprite, x - r, y - r, r * 2, r * 2);
      }
      ctx.globalAlpha = 1;

      // Core glow — brain form only (the brainstem), flickers under token load.
      if (blend.brain > 0.02) {
        const coreFlicker = st === 'thinking' ? think * (0.5 + 0.5 * Math.sin(phase * 13)) : 0;
        const coreGlow = clamp01(0.35 + breathe * 0.15 + energy * 0.45 + coreFlicker * 0.4) * blend.brain;
        const coreR = R * (0.13 + coreGlow * 0.07);
        const cg = ctx.createRadialGradient(cx, cy, 0, cx, cy, coreR * 3);
        cg.addColorStop(0, rgba(pal.core, Math.min(1, coreGlow + 0.2 * blend.brain)));
        cg.addColorStop(0.35, rgba(pal.core, coreGlow * 0.45));
        cg.addColorStop(1, rgba(pal.core, 0));
        ctx.fillStyle = cg;
        ctx.beginPath();
        ctx.arc(cx, cy, coreR * 3, 0, Math.PI * 2);
        ctx.fill();
      }

      ctx.globalCompositeOperation = 'source-over';
      raf = requestAnimationFrame(draw);
    };

    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [size, signals]);

  return (
    <canvas
      ref={canvasRef}
      style={{ width: size, height: size, display: 'block' }}
      aria-hidden="true"
    />
  );
}
