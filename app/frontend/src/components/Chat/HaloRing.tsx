// Voice-reactive halo — a ring of 64 spectrum-style bars orbiting the avatar.
// The bar ENVELOPE is the real signal: when Jarvis speaks the ring erupts
// with the true TTS RMS (`bot_level`), when you talk it pulses with your true
// mic RMS (`mic_level`), while the LLM streams it shimmers with real token
// ticks. Per-bar texture is decorative, but the loudness driving it is never
// synthesized — disconnect the sidecar and the ring goes flat.
import { useEffect, useRef } from 'react';
import type { JarvisLiveSignals } from '../../hooks/useJarvisBridge';
import type { BrainState } from './NeuralBrain';

interface Props {
  state: BrainState;
  signals: React.MutableRefObject<JarvisLiveSignals>;
  /** Canvas side length in CSS px; the bar ring is sized relative to this. */
  size: number;
}

const BARS = 64;

type RGB = [number, number, number];

const COLORS: Record<BrainState, RGB> = {
  disconnected: [107, 114, 128],
  idle: [45, 212, 191],
  listening: [56, 189, 248],
  thinking: [192, 132, 252],
  speaking: [245, 158, 11],
  working: [52, 211, 153],
};

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

export function HaloRing({ state, signals, size }: Props) {
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

    const c = size / 2;
    const r0 = size * 0.415; // bars start just outside the avatar's reach
    const maxLen = size * 0.085;

    const bars = new Float32Array(BARS);
    const col: RGB = [...COLORS.idle] as RGB;
    let lastTokens = 0;
    let tokenPulse = 0;
    let raf = 0;
    let last = performance.now();
    let phase = 0;

    const draw = (now: number) => {
      const dt = Math.min(64, now - last) / 1000;
      last = now;
      phase += dt;

      const s = signals.current;
      const st = stateRef.current;
      const stale = now - s.lastEventAt > 2500;

      // Real envelope per state. Token ticks become a decaying pulse so the
      // thinking shimmer tracks actual stream activity, not a fake loop.
      if (s.tokenCount !== lastTokens) {
        lastTokens = s.tokenCount;
        tokenPulse = 1;
      }
      tokenPulse = Math.max(0, tokenPulse - dt * 2.5);
      const lvl = stale || st === 'disconnected'
        ? 0
        : st === 'speaking'
          ? s.botLevel
          : st === 'listening'
            ? s.micLevel
            : st === 'thinking'
              ? tokenPulse * 0.45
              : 0.05 + 0.03 * Math.sin(phase * 1.1); // idle breath

      const target = COLORS[st];
      for (let i = 0; i < 3; i++) col[i] = lerp(col[i], target[i], Math.min(1, dt * 5));

      for (let i = 0; i < BARS; i++) {
        // Organic per-bar shape under the real envelope.
        const shape =
          0.3 +
          0.7 * Math.abs(Math.sin(i * 0.61 + phase * 3.1)) * (0.6 + 0.4 * Math.sin(i * 2.3 - phase * 1.7));
        const t = lvl * shape;
        // Fast attack, slower release — reads like a real spectrum analyzer.
        bars[i] += (t - bars[i]) * Math.min(1, dt * (t > bars[i] ? 16 : 6));
      }

      ctx.clearRect(0, 0, size, size);
      ctx.globalCompositeOperation = 'lighter';
      // Constant per frame — only the alpha varies per bar below.
      ctx.lineWidth = Math.max(1.5, size * 0.006);
      ctx.lineCap = 'round';
      const colPrefix = `rgba(${col[0] | 0},${col[1] | 0},${col[2] | 0},`;
      const spin = phase * 0.12;
      for (let i = 0; i < BARS; i++) {
        const ang = (i / BARS) * Math.PI * 2 + spin;
        const len = 1.5 + bars[i] * maxLen;
        const ca = Math.cos(ang);
        const sa = Math.sin(ang);
        const a = 0.12 + bars[i] * 0.85;
        ctx.strokeStyle = `${colPrefix}${a})`;
        ctx.beginPath();
        ctx.moveTo(c + ca * r0, c + sa * r0);
        ctx.lineTo(c + ca * (r0 + len), c + sa * (r0 + len));
        ctx.stroke();
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
      className="absolute pointer-events-none"
      style={{ width: size, height: size, left: '50%', top: '50%', transform: 'translate(-50%,-50%)' }}
      aria-hidden="true"
    />
  );
}
