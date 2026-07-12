// Read-only microphone amplitude for the JARVIS arc-reactor visuals.
//
// This opens a SECOND, analysis-only tap on the mic via the Web Audio API so the
// core can pulse with your actual voice. The jarvis-sidecar keeps owning the mic
// for STT (Windows shared-mode lets both read it); this stream is never recorded
// or sent anywhere — it only feeds an AnalyserNode for the animation.
//
// Everything degrades gracefully: if getUserMedia is unavailable or denied, the
// level stays 0 and JarvisCore falls back to state-driven synthetic motion, so
// the UI still looks alive. `available` lets the UI label which mode it's in.
import { useEffect, useRef } from 'react';

export interface MicLevel {
  /** Smoothed 0..1 amplitude, updated in-place each animation frame. Read .current in a rAF loop. */
  levelRef: React.MutableRefObject<number>;
  /** True once a real mic tap is live; false means synthetic fallback. */
  availableRef: React.MutableRefObject<boolean>;
}

export function useMicLevel(active: boolean): MicLevel {
  const levelRef = useRef(0);
  const availableRef = useRef(false);

  useEffect(() => {
    if (!active || typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      return;
    }

    let alive = true;
    let raf: number | null = null;
    let ctx: AudioContext | null = null;
    let stream: MediaStream | null = null;
    let removeResume: (() => void) | null = null;

    navigator.mediaDevices
      .getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false } })
      .then((s) => {
        if (!alive) {
          s.getTracks().forEach((t) => t.stop());
          return;
        }
        stream = s;
        ctx = new AudioContext();
        // AudioContext can start suspended until a user gesture; resume now and
        // also on the next pointer/key event so the analyser sees real samples.
        const tryResume = () => ctx?.resume().catch(() => {});
        tryResume();
        const onGesture = () => tryResume();
        window.addEventListener('pointerdown', onGesture, { once: true });
        window.addEventListener('keydown', onGesture, { once: true });
        removeResume = () => {
          window.removeEventListener('pointerdown', onGesture);
          window.removeEventListener('keydown', onGesture);
        };

        const source = ctx.createMediaStreamSource(s);
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 256;
        analyser.smoothingTimeConstant = 0.6;
        source.connect(analyser);
        const data = new Uint8Array(analyser.frequencyBinCount);
        availableRef.current = true;

        const tick = () => {
          if (!alive) return;
          analyser.getByteTimeDomainData(data);
          let sum = 0;
          for (let i = 0; i < data.length; i++) {
            const v = (data[i] - 128) / 128;
            sum += v * v;
          }
          const rms = Math.sqrt(sum / data.length);
          // Scale RMS into a lively 0..1 range and smooth toward it.
          const target = Math.min(1, rms * 3.4);
          levelRef.current += (target - levelRef.current) * 0.35;
          raf = requestAnimationFrame(tick);
        };
        tick();
      })
      .catch(() => {
        availableRef.current = false; // denied/unavailable — synthetic fallback owns the visuals
      });

    return () => {
      alive = false;
      if (raf) cancelAnimationFrame(raf);
      removeResume?.();
      stream?.getTracks().forEach((t) => t.stop());
      ctx?.close().catch(() => {});
      availableRef.current = false;
      levelRef.current = 0;
    };
  }, [active]);

  return { levelRef, availableRef };
}
