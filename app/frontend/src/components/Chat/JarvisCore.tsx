// The JARVIS arc-reactor core — a live canvas presence for the voice loop.
//
// Driven entirely by real signals: connection state, listening/speaking state
// from the sidecar bridge, and (when permitted) your actual mic amplitude via
// useMicLevel. No mock data — when there's no audio tap it falls back to
// state-driven synthetic motion so it still feels alive.
//
// Visual language: concentric reactor coils, a slowly rotating HUD tick ring,
// a bright pulsing core, and outward ripples while Jarvis is speaking. Color
// shifts by state — cyan when listening, gold while speaking, dim teal idle.
import { useEffect, useRef } from 'react';
import { useMicLevel } from '../../hooks/useMicLevel';

export type CoreState = 'disconnected' | 'idle' | 'listening' | 'speaking';

interface Props {
  state: CoreState;
  /** Side length in CSS pixels. */
  size?: number;
}

interface Palette {
  core: [number, number, number];
  ring: [number, number, number];
}

const PALETTES: Record<CoreState, Palette> = {
  disconnected: { core: [110, 118, 130], ring: [80, 88, 100] },
  idle: { core: [56, 200, 220], ring: [40, 150, 170] },
  listening: { core: [70, 220, 255], ring: [56, 189, 248] },
  speaking: { core: [245, 175, 60], ring: [245, 158, 11] },
};

const rgba = (c: [number, number, number], a: number) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;

export function JarvisCore({ state, size = 104 }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const { levelRef, availableRef } = useMicLevel(state !== 'disconnected');

  // Mutable bag the animation loop reads without re-subscribing each frame.
  const anim = useRef({ state, phase: 0, glow: 0, ripples: [] as { r: number; a: number }[], lastSpeak: false });
  anim.current.state = state;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = size * dpr;
    canvas.height = size * dpr;
    ctx.scale(dpr, dpr);

    let raf = 0;
    let last = performance.now();
    const cx = size / 2;
    const cy = size / 2;
    const R = size * 0.42;

    const draw = (now: number) => {
      const dt = Math.min(64, now - last) / 1000;
      last = now;
      const a = anim.current;
      a.phase += dt;

      const st = a.state;
      const pal = PALETTES[st];

      // Amplitude: real mic level when available, else synthetic per state.
      let level = availableRef.current ? levelRef.current : 0;
      if (!availableRef.current) {
        if (st === 'speaking') level = 0.45 + 0.35 * Math.abs(Math.sin(a.phase * 7));
        else if (st === 'listening') level = 0.18 + 0.12 * Math.abs(Math.sin(a.phase * 3));
        else level = 0;
      }
      // Gentle breathing baseline so it's never fully still when online.
      const breathe = st === 'disconnected' ? 0 : 0.5 + 0.5 * Math.sin(a.phase * 1.6);
      const energy = Math.min(1, level * 0.9 + breathe * 0.18);
      a.glow += (energy - a.glow) * 0.2;

      // Spawn outward ripples on speaking energy peaks.
      if (st === 'speaking' && level > 0.6 && a.ripples.length < 4 && Math.random() < 0.25) {
        a.ripples.push({ r: R * 0.5, a: 0.5 });
      }
      a.lastSpeak = st === 'speaking';

      ctx.clearRect(0, 0, size, size);
      ctx.save();
      ctx.translate(cx, cy);

      // --- outward speaking ripples ---
      for (const rp of a.ripples) {
        rp.r += dt * size * 0.9;
        rp.a -= dt * 0.7;
        ctx.beginPath();
        ctx.arc(0, 0, rp.r, 0, Math.PI * 2);
        ctx.strokeStyle = rgba(pal.ring, Math.max(0, rp.a));
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }
      a.ripples = a.ripples.filter((r) => r.a > 0.02 && r.r < size * 0.7);

      // --- rotating HUD tick ring ---
      const ticks = 48;
      const rot = a.phase * (st === 'speaking' ? 0.5 : 0.22);
      for (let i = 0; i < ticks; i++) {
        const ang = rot + (i / ticks) * Math.PI * 2;
        const long = i % 4 === 0;
        const r0 = R * 1.04;
        const r1 = R * (long ? 1.16 : 1.1);
        ctx.beginPath();
        ctx.moveTo(Math.cos(ang) * r0, Math.sin(ang) * r0);
        ctx.lineTo(Math.cos(ang) * r1, Math.sin(ang) * r1);
        ctx.strokeStyle = rgba(pal.ring, long ? 0.55 : 0.25);
        ctx.lineWidth = long ? 1.4 : 0.8;
        ctx.stroke();
      }

      // --- reactor coil segments (the arc-reactor signature) ---
      const seg = 8;
      ctx.shadowBlur = 8 + a.glow * 16;
      ctx.shadowColor = rgba(pal.core, 0.7);
      for (let i = 0; i < seg; i++) {
        const ang = -rot * 0.6 + (i / seg) * Math.PI * 2;
        const inner = R * 0.5;
        const outer = R * (0.82 + a.glow * 0.06);
        const w = 0.16;
        ctx.beginPath();
        ctx.moveTo(Math.cos(ang - w) * inner, Math.sin(ang - w) * inner);
        ctx.lineTo(Math.cos(ang - w * 0.6) * outer, Math.sin(ang - w * 0.6) * outer);
        ctx.lineTo(Math.cos(ang + w * 0.6) * outer, Math.sin(ang + w * 0.6) * outer);
        ctx.lineTo(Math.cos(ang + w) * inner, Math.sin(ang + w) * inner);
        ctx.closePath();
        ctx.fillStyle = rgba(pal.core, 0.18 + a.glow * 0.35);
        ctx.fill();
      }
      ctx.shadowBlur = 0;

      // --- pulsing concentric rings ---
      for (let i = 0; i < 3; i++) {
        const rr = R * (0.5 + i * 0.18) + a.glow * 4 * (i + 1);
        ctx.beginPath();
        ctx.arc(0, 0, rr, 0, Math.PI * 2);
        ctx.strokeStyle = rgba(pal.ring, 0.12 + (i === 0 ? a.glow * 0.4 : 0.06));
        ctx.lineWidth = i === 0 ? 1.6 : 1;
        ctx.stroke();
      }

      // --- bright central core ---
      const coreR = R * (0.34 + a.glow * 0.14);
      const grad = ctx.createRadialGradient(0, 0, 0, 0, 0, coreR);
      grad.addColorStop(0, rgba(pal.core, 0.95));
      grad.addColorStop(0.5, rgba(pal.core, 0.45 + a.glow * 0.3));
      grad.addColorStop(1, rgba(pal.core, 0));
      ctx.beginPath();
      ctx.arc(0, 0, coreR, 0, Math.PI * 2);
      ctx.fillStyle = grad;
      ctx.shadowBlur = 14 + a.glow * 26;
      ctx.shadowColor = rgba(pal.core, 0.9);
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.restore();
      raf = requestAnimationFrame(draw);
    };

    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [size, levelRef, availableRef]);

  return (
    <canvas
      ref={canvasRef}
      style={{ width: size, height: size, display: 'block' }}
      aria-hidden="true"
    />
  );
}
