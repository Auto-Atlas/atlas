// Full-screen owner "stage" — a chrome-free, recording-grade view of the
// live voice loop. Open it in any browser at http://localhost:5173/stage and go
// fullscreen. It connects to the same jarvis-sidecar WebSocket as the desktop
// app (ws://127.0.0.1:8765) via its own JarvisBridgeProvider, so it shows the
// exact same live turns — transcript, speaking state, TTFB + tokens — just
// scaled up and cinematic. The neural brain fires inward with your real mic
// signal, flickers with real LLM token ticks, and fires outward with the real
// TTS waveform. No mock data: kill the sidecar and the brain goes still.
import { useEffect, useRef, useState } from 'react';
import { JarvisBridgeProvider } from '../components/Chat/JarvisBridgeContext';
import { useJarvisBridgeState } from '../components/Chat/JarvisBridgeContext';
import { NeuralBrain, type BrainState } from '../components/Chat/NeuralBrain';
import { HaloRing } from '../components/Chat/HaloRing';
import { StageIntro } from '../components/Chat/StageIntro';
import { ToolStatusLine } from '../components/Chat/JarvisActivity';
import { DelegationTicker } from '../components/Chat/DelegationTicker';
import { brainStateOf } from '../components/Chat/LiveVoicePanel';
import { Maximize2 } from 'lucide-react';

const STAGE_KEYFRAMES = `
@keyframes stage-grid { from { background-position: 0 0; } to { background-position: 0 -48px; } }
@keyframes stage-spin { to { transform: rotate(360deg); } }
@keyframes stage-rise { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
@keyframes stage-pulse { 0%,100% { transform: scale(1); } 50% { transform: scale(1.35); } }
@keyframes stage-letter { from { opacity: 0; filter: blur(6px); transform: translateY(6px); } to { opacity: 1; filter: blur(0); transform: translateY(0); } }
`;

const WORDMARK = 'ATLAS';

const ACCENTS: Record<BrainState, string> = {
  disconnected: '#6b7280',
  idle: '#2dd4bf',
  listening: '#38bdf8',
  thinking: '#c084fc',
  speaking: '#f59e0b',
  working: '#34d399',
};

const LABELS: Record<BrainState, string> = {
  disconnected: 'CONNECTING',
  idle: 'STANDBY',
  listening: 'LISTENING',
  thinking: 'THINKING',
  speaking: 'SPEAKING',
  working: 'ON IT',
};

export default function JarvisStagePage({ wsUrl }: { wsUrl?: string } = {}) {
  return (
    <JarvisBridgeProvider url={wsUrl}>
      <Stage />
    </JarvisBridgeProvider>
  );
}

export function Stage() {
  const bridge = useJarvisBridgeState();
  const [coreSize, setCoreSize] = useState(360);
  const [cursorHidden, setCursorHidden] = useState(false);
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Power-on sequence: replays on every REAL (re)connect of the voice loop.
  // introKey remounts the overlay + restarts the wordmark letter animation.
  const [introKey, setIntroKey] = useState(0);
  const [introPlaying, setIntroPlaying] = useState(false);
  const wasConnected = useRef(false);
  useEffect(() => {
    if (bridge.connected && !wasConnected.current) {
      setIntroKey((k) => k + 1);
      setIntroPlaying(true);
    }
    wasConnected.current = bridge.connected;
  }, [bridge.connected]);

  // Responsive brain sizing. Debounced: every size change re-seeds the
  // particle pool, so resizing live during a window drag reset the avatar
  // dozens of times a second. One settle, one reset.
  useEffect(() => {
    let settle: ReturnType<typeof setTimeout> | null = null;
    const measure = () => {
      const w = window.innerWidth;
      const h = window.innerHeight;
      // Phones are tall+narrow: a bigger fraction fills the stage with presence
      // instead of a small brain marooned in empty space.
      const frac = w < 640 ? 0.72 : 0.52;
      setCoreSize(Math.round(Math.min(w, h) * frac));
    };
    const onResize = () => {
      if (settle) clearTimeout(settle);
      settle = setTimeout(measure, 150);
    };
    measure();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      if (settle) clearTimeout(settle);
    };
  }, []);

  // Auto-hide the cursor after 2.5s of stillness (clean for recording).
  useEffect(() => {
    const onMove = () => {
      setCursorHidden(false);
      if (idleTimer.current) clearTimeout(idleTimer.current);
      idleTimer.current = setTimeout(() => setCursorHidden(true), 2500);
    };
    onMove();
    window.addEventListener('mousemove', onMove);
    return () => {
      window.removeEventListener('mousemove', onMove);
      if (idleTimer.current) clearTimeout(idleTimer.current);
    };
  }, []);

  const brainState = brainStateOf(bridge);
  const accent = ACCENTS[brainState];
  const statusLabel = LABELS[brainState];

  const ttfbSec = bridge.metrics['TTFBMetricsData'];
  const ttfbMs = ttfbSec !== undefined ? Math.round(ttfbSec * 1000) : null;
  const totalTokens = bridge.usage?.total_tokens ?? null;
  const promptTokens = bridge.usage?.prompt_tokens ?? null;
  const completionTokens = bridge.usage?.completion_tokens ?? null;

  const goFullscreen = () => {
    const el = document.documentElement;
    if (!document.fullscreenElement) el.requestFullscreen?.().catch(() => {});
    else document.exitFullscreen?.().catch(() => {});
  };

  // Subtitle priority: what it's saying > what you're mid-saying > what you said.
  const subtitle = bridge.botSpeaking && bridge.botTranscript
    ? { who: 'Atlas', text: bridge.botTranscript }
    : bridge.interimTranscript
      ? { who: 'YOU', text: bridge.interimTranscript }
      : bridge.transcript
        ? { who: 'YOU', text: bridge.transcript }
        : null;

  return (
    <div
      className="fixed inset-0 overflow-hidden"
      style={{
        background:
          'radial-gradient(120% 120% at 50% 38%, #0a1018 0%, #060912 55%, #04060b 100%)',
        cursor: cursorHidden ? 'none' : 'default',
      }}
    >
      <style>{STAGE_KEYFRAMES}</style>

      {/* Power-on light-streak convergence (plays on each real connect) */}
      {introPlaying && <StageIntro key={introKey} onDone={() => setIntroPlaying(false)} />}

      {/* Faint moving grid */}
      <div
        className="absolute inset-0 pointer-events-none"
        style={{
          backgroundImage:
            'linear-gradient(rgba(56,189,248,0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(56,189,248,0.05) 1px, transparent 1px)',
          backgroundSize: '48px 48px',
          maskImage: 'radial-gradient(circle at 50% 42%, black 30%, transparent 75%)',
          WebkitMaskImage: 'radial-gradient(circle at 50% 42%, black 30%, transparent 75%)',
          animation: 'stage-grid 6s linear infinite',
        }}
      />

      {/* Fullscreen button (hides with cursor) */}
      <button
        onClick={goFullscreen}
        className="absolute top-5 right-5 z-20 p-2.5 rounded-lg hidden sm:block"
        style={{
          background: 'rgba(255,255,255,0.04)',
          border: `1px solid ${accent}40`,
          color: accent,
          opacity: cursorHidden ? 0 : 1,
          transition: 'opacity 300ms',
        }}
        title="Toggle fullscreen (or press F11)"
      >
        <Maximize2 size={18} />
      </button>

      {/* Latest tool activity — appears only when a tool has actually run */}
      {bridge.toolActivity && (
        <div
          className="absolute left-6 z-10 max-w-[80vw] sm:max-w-[44ch]"
          style={{ top: 'calc(env(safe-area-inset-top, 0px) + 3.25rem)' }}
        >
          <ToolStatusLine activity={bridge.toolActivity} />
        </div>
      )}

      {/* Live delegation waterfall — watch the brain chain happen in real time */}
      <DelegationTicker delegation={bridge.delegation} />

      {/* Top-left status */}
      <div
        className="absolute left-6 z-10 flex items-center gap-2.5"
        style={{ top: 'calc(env(safe-area-inset-top, 0px) + 1rem)' }}
      >
        <span
          className="w-2.5 h-2.5 rounded-full"
          style={{
            background: accent,
            boxShadow: `0 0 12px ${accent}`,
            animation: bridge.botSpeaking ? 'stage-pulse 0.5s ease-in-out infinite' : 'none',
          }}
        />
        <span
          className="text-[13px] font-medium"
          style={{ color: accent, letterSpacing: '0.22em', fontFamily: 'ui-monospace, monospace' }}
        >
          {statusLabel}
        </span>
        <span
          className="text-[12px]"
          style={{ color: 'rgba(148,163,184,0.55)', letterSpacing: '0.18em', fontFamily: 'ui-monospace, monospace' }}
        >
          · {(bridge.mode ?? 'local').toUpperCase()} · $0/MIN
        </span>
      </div>

      {/* Top-right telemetry */}
      <div
        className="absolute top-6 right-16 z-10 hidden sm:flex items-center gap-5 text-[12px]"
        style={{ fontFamily: 'ui-monospace, monospace' }}
      >
        <Stat label="TTFB" value={ttfbMs !== null ? `${ttfbMs}ms` : '—'} accent={accent} />
        <Stat label="TOKENS" value={totalTokens !== null ? `${totalTokens}` : '—'} accent={accent} />
      </div>

      {/* Center stack (lifts above the phone tab bar via --eve-bottom-inset) */}
      <div
        className="absolute inset-0 flex flex-col items-center justify-center z-10"
        style={{ bottom: 'var(--eve-bottom-inset, 0px)' }}
      >
        {/* Rotating HUD ring behind the brain */}
        <div className="relative flex items-center justify-center" style={{ width: coreSize * 1.3, height: coreSize * 1.3 }}>
          <div
            className="absolute rounded-full pointer-events-none"
            style={{
              width: coreSize * 1.24,
              height: coreSize * 1.24,
              border: `1px solid ${accent}22`,
              maskImage: 'conic-gradient(from 0deg, transparent 0deg, black 40deg, transparent 80deg, black 200deg, transparent 240deg, black 320deg, transparent 360deg)',
              WebkitMaskImage: 'conic-gradient(from 0deg, transparent 0deg, black 40deg, transparent 80deg, black 200deg, transparent 240deg, black 320deg, transparent 360deg)',
              animation: 'stage-spin 24s linear infinite',
            }}
          />
          <div
            className="absolute rounded-full pointer-events-none"
            style={{
              width: coreSize * 1.08,
              height: coreSize * 1.08,
              border: `1px dashed ${accent}26`,
              animation: 'stage-spin 36s linear infinite reverse',
            }}
          />
          {/* Voice-reactive halo: erupts with his real TTS RMS / your mic RMS */}
          <HaloRing state={brainState} signals={bridge.signals} size={coreSize * 1.3} />
          <NeuralBrain state={brainState} signals={bridge.signals} size={coreSize} />
        </div>

        {/* Wordmark — letters type in at the end of each power-on sequence */}
        <div
          key={introKey}
          className="mt-4 font-semibold select-none"
          style={{
            color: accent,
            fontSize: 'clamp(28px, 5vw, 56px)',
            letterSpacing: '0.5em',
            paddingLeft: '0.5em',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            textShadow: `0 0 24px ${accent}aa`,
          }}
        >
          {WORDMARK.split('').map((ch, i) => (
            <span
              key={i}
              style={{
                display: 'inline-block',
                animation: 'stage-letter 260ms ease-out both',
                animationDelay: `${1500 + i * 110}ms`,
              }}
            >
              {ch}
            </span>
          ))}
        </div>
      </div>

      {/* Subtitle-style transcript (sits above the phone tab bar) */}
      <div
        className="absolute left-0 right-0 z-10 flex justify-center px-8 pb-[4vh]"
        style={{ bottom: 'var(--eve-bottom-inset, 0px)' }}
      >
        <div
          key={subtitle?.text ?? statusLabel}
          className="text-center max-w-[60ch]"
          style={{
            fontSize: 'clamp(20px, 2.6vw, 40px)',
            lineHeight: 1.25,
            color: subtitle ? '#f1f5f9' : 'rgba(148,163,184,0.5)',
            fontWeight: 500,
            textShadow: subtitle ? '0 2px 24px rgba(0,0,0,0.8)' : 'none',
            animation: subtitle ? 'stage-rise 320ms ease-out' : 'none',
          }}
        >
          {subtitle ? (
            <>
              <span style={{ color: accent, fontFamily: 'ui-monospace, monospace', fontSize: '0.6em' }}>
                {subtitle.who}&nbsp;›&nbsp;
              </span>
              {subtitle.text}
            </>
          ) : bridge.connected ? (
            'Say something — Atlas is listening.'
          ) : (
            'Connecting to the voice loop…'
          )}
        </div>
      </div>

      {/* Detailed bottom-left readout */}
      {bridge.usage && (
        <div
          className="absolute bottom-5 left-6 z-10 text-[11px]"
          style={{ color: 'rgba(148,163,184,0.5)', fontFamily: 'ui-monospace, monospace', letterSpacing: '0.08em' }}
        >
          {promptTokens} prompt · {completionTokens} generated
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: string; accent: string }) {
  return (
    <div className="flex items-center gap-2">
      <span style={{ color: 'rgba(148,163,184,0.55)', letterSpacing: '0.14em' }}>{label}</span>
      <span style={{ color: accent, fontWeight: 600, textShadow: `0 0 10px ${accent}66` }}>{value}</span>
    </div>
  );
}
