// Stage power-on sequence — plays once each time the voice loop CONNECTS
// (remounted via key). Hundreds of light streaks fly in from the screen edges
// and spiral into the center where the avatar lives, a bloom flares at the
// hand-off, then the overlay dissolves and the live avatar takes over.
// Pure theater, but honestly triggered: it runs exactly when the WebSocket to
// the sidecar actually comes up, never on a timer loop.
import { useEffect, useRef } from 'react';

interface Props {
  /** Total runtime in ms before the overlay unmounts itself. */
  duration?: number;
  onDone?: () => void;
}

// Pre-rendered glow sprite: the radial gradient is baked once at full
// intensity; per-draw intensity is globalAlpha (under 'lighter' compositing
// that's pixel-equivalent to rebuilding the gradient with scaled stops).
function makeGlowSprite(r: number, stops: Array<[number, string]>): HTMLCanvasElement {
  const cnv = document.createElement('canvas');
  cnv.width = cnv.height = r * 2;
  const c2 = cnv.getContext('2d');
  if (c2) {
    const g = c2.createRadialGradient(r, r, 0, r, r, r);
    for (const [off, color] of stops) g.addColorStop(off, color);
    c2.fillStyle = g;
    c2.fillRect(0, 0, r * 2, r * 2);
  }
  return cnv;
}

interface Streak {
  /** Spawn point on a screen edge. */
  x0: number;
  y0: number;
  /** 0..1 flight progress start offset (stagger). */
  delay: number;
  /** Flight time in seconds. */
  flight: number;
  /** Spiral curl direction/strength. */
  curl: number;
  /** Brightness weight. */
  w: number;
}

export function StageIntro({ duration = 2800, onDone }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const doneRef = useRef(onDone);
  doneRef.current = onDone;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const W = window.innerWidth;
    const H = window.innerHeight;
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    ctx.scale(dpr, dpr);

    // Convergence point matches the avatar's stage position (~42% height).
    const cx = W / 2;
    const cy = H * 0.43;

    let seed = 0x5eed;
    const rnd = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      return seed / 4294967296;
    };

    const streaks: Streak[] = Array.from({ length: 260 }, () => {
      const side = (rnd() * 4) | 0;
      const u = rnd();
      const x0 = side === 0 ? -20 : side === 1 ? W + 20 : u * W;
      const y0 = side === 2 ? -20 : side === 3 ? H + 20 : u * H;
      return {
        x0,
        y0,
        delay: rnd() * 0.9,
        flight: 0.7 + rnd() * 0.7,
        curl: (rnd() - 0.5) * 2.4,
        w: 0.35 + rnd() * 0.65,
      };
    });

    const T = duration / 1000;
    let raf = 0;
    let start = 0;

    const pos = (s: Streak, p: number): [number, number] => {
      // p: 0 at edge, 1 at center. Ease-in acceleration + spiral curl.
      const e = p * p * (3 - 2 * p);
      const dx = s.x0 - cx;
      const dy = s.y0 - cy;
      const r = 1 - e;
      const ang = s.curl * e;
      const ca = Math.cos(ang);
      const sa = Math.sin(ang);
      return [cx + (dx * ca - dy * sa) * r, cy + (dx * sa + dy * ca) * r];
    };

    const draw = (now: number) => {
      if (!start) start = now;
      const t = (now - start) / 1000;
      if (t >= T) {
        ctx.clearRect(0, 0, W, H);
        doneRef.current?.();
        return;
      }

      ctx.clearRect(0, 0, W, H);
      // The whole overlay fades out over the last 400ms (avatar hand-off).
      const fade = Math.min(1, (T - t) / 0.4);
      ctx.globalCompositeOperation = 'lighter';

      for (const s of streaks) {
        const p = (t - s.delay) / s.flight;
        if (p <= 0 || p >= 1) continue;
        const [x, y] = pos(s, p);
        const [tx, ty] = pos(s, Math.max(0, p - 0.06));
        const a = s.w * Math.min(1, p * 4) * (1 - p * 0.35) * fade;
        ctx.strokeStyle = `rgba(120, 210, 255, ${0.5 * a})`;
        ctx.lineWidth = 1.2;
        ctx.beginPath();
        ctx.moveTo(tx, ty);
        ctx.lineTo(x, y);
        ctx.stroke();
        const g = ctx.createRadialGradient(x, y, 0, x, y, 6);
        g.addColorStop(0, `rgba(165, 235, 255, ${0.9 * a})`);
        g.addColorStop(1, 'rgba(165,235,255,0)');
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(x, y, 6, 0, Math.PI * 2);
        ctx.fill();
      }

      // Center bloom: builds as streaks land, flares at the hand-off moment.
      const build = Math.min(1, t / (T - 0.5));
      const flare = t > T - 0.8 ? (1 - (T - t) / 0.8) * 0.6 : 0;
      const bloomR = 30 + build * 110 + flare * 80;
      const bg = ctx.createRadialGradient(cx, cy, 0, cx, cy, bloomR);
      bg.addColorStop(0, `rgba(140, 220, 255, ${(0.25 * build + flare * 0.45) * fade})`);
      bg.addColorStop(0.5, `rgba(80, 180, 235, ${(0.12 * build + flare * 0.2) * fade})`);
      bg.addColorStop(1, 'rgba(80,180,235,0)');
      ctx.fillStyle = bg;
      ctx.beginPath();
      ctx.arc(cx, cy, bloomR, 0, Math.PI * 2);
      ctx.fill();

      ctx.globalCompositeOperation = 'source-over';
      raf = requestAnimationFrame(draw);
    };

    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [duration]);

  return (
    <canvas
      ref={canvasRef}
      className="fixed inset-0 pointer-events-none"
      style={{ zIndex: 30, width: '100vw', height: '100vh' }}
      aria-hidden="true"
    />
  );
}
