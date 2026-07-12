// Phase 2: the live JARVIS surface for the local voice loop (jarvis-sidecar).
//
// A heads-up-display panel above the input box: a living neural-brain graph that
// fires inward when you speak, flickers while the LLM thinks, and fires outward
// while it talks — every motion driven by real sidecar WebSocket signals (VAD,
// mic RMS, LLM token ticks, TTS RMS). Plus the wordmark + live status, the latest
// spoken transcript, and real per-turn telemetry (TTFB + tokens). No mock data.
// Stays hidden until the voice loop is connected or has spoken, so non-voice use
// of the app is unchanged.
import { useJarvisBridgeState, useBridgeActions } from './JarvisBridgeContext';
import { NeuralBrain, type BrainState } from './NeuralBrain';
import { ToolStatusLine, SessionTranscript } from './JarvisActivity';
import { SurfaceVisualCard } from './SurfaceVisualCard';
import { XRayFooter } from './XRayFooter';
import type { MessageTelemetry } from '../../types';

const HUD_KEYFRAMES = `
@keyframes jarvis-scan { 0% { transform: translateY(-100%); opacity: 0; } 8% { opacity: 0.5; } 92% { opacity: 0.5; } 100% { transform: translateY(900%); opacity: 0; } }
@keyframes jarvis-flicker { 0%,100% { opacity: 0.85; } 50% { opacity: 1; } }
`;

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

export function brainStateOf(bridge: ReturnType<typeof useJarvisBridgeState>): BrainState {
  if (!bridge.connected) return 'disconnected';
  if (bridge.botSpeaking) return 'speaking';
  // A delegation or tool in flight = Jarvis is off doing the task — keep the
  // avatar in its lively "working" morph the whole time (no silent freeze).
  if (bridge.delegation && !bridge.delegation.done) return 'working';
  if (bridge.toolActivity?.status === 'running') return 'working';
  if (bridge.thinking) return 'thinking';
  if (bridge.userSpeaking || bridge.interimTranscript) return 'listening';
  return 'idle';
}

export function LiveVoicePanel() {
  const bridge = useJarvisBridgeState();
  const { setThinking } = useBridgeActions();

  // Keep the normal chat clean until the voice loop is live or has spoken.
  if (!bridge.connected && !bridge.transcript && !bridge.botSpeaking) return null;

  const brainState = brainStateOf(bridge);

  const ttfbSec = bridge.metrics['TTFBMetricsData'];
  const telemetry: MessageTelemetry | undefined =
    ttfbSec !== undefined || bridge.mode
      ? {
          engine: bridge.mode === 'showtime' ? 'showtime (cloud)' : 'local',
          ttft_ms: ttfbSec !== undefined ? ttfbSec * 1000 : undefined,
          total_ms: ttfbSec !== undefined ? ttfbSec * 1000 : undefined,
        }
      : undefined;

  const accent = ACCENTS[brainState];
  const statusLabel = LABELS[brainState];

  const ttfbMs = ttfbSec !== undefined ? Math.round(ttfbSec * 1000) : null;
  const totalTokens = bridge.usage?.total_tokens ?? null;

  // Transcript line priority: what it's saying > what you're mid-saying > what you said.
  const line = bridge.botSpeaking && bridge.botTranscript
    ? { who: 'Jarvis', text: bridge.botTranscript }
    : bridge.interimTranscript
      ? { who: 'You', text: bridge.interimTranscript }
      : bridge.transcript
        ? { who: 'You', text: bridge.transcript }
        : null;

  return (
    <div
      className="relative mx-4 mb-2 shrink-0 overflow-hidden"
      style={{
        background: 'linear-gradient(180deg, rgba(8,12,18,0.92) 0%, rgba(10,14,20,0.96) 100%)',
        border: `1px solid ${accent}40`,
        borderRadius: '14px',
        boxShadow: `0 0 0 1px ${accent}14, 0 0 24px -6px ${accent}55, inset 0 0 40px -24px ${accent}`,
        transition: 'border-color 300ms, box-shadow 300ms',
      }}
    >
      <style>{HUD_KEYFRAMES}</style>

      {/* Corner brackets */}
      {[
        { top: 6, left: 6, borderWidth: '1.5px 0 0 1.5px' },
        { top: 6, right: 6, borderWidth: '1.5px 1.5px 0 0' },
        { bottom: 6, left: 6, borderWidth: '0 0 1.5px 1.5px' },
        { bottom: 6, right: 6, borderWidth: '0 1.5px 1.5px 0' },
      ].map((pos, i) => (
        <span
          key={i}
          className="absolute pointer-events-none"
          style={{ width: 12, height: 12, borderStyle: 'solid', borderColor: accent, ...pos }}
        />
      ))}

      {/* Scanline sweep */}
      <span
        className="absolute left-0 right-0 pointer-events-none"
        style={{
          height: '14px',
          top: 0,
          background: `linear-gradient(180deg, transparent, ${accent}22, transparent)`,
          animation: 'jarvis-scan 4.5s linear infinite',
        }}
      />

      <div className="relative flex items-center gap-4 px-4 py-3">
        {/* Living neural brain */}
        <div className="shrink-0">
          <NeuralBrain state={brainState} signals={bridge.signals} size={112} />
        </div>

        {/* Right column: wordmark, status, transcript, telemetry */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1.5">
            <span
              className="text-[13px] font-semibold"
              style={{
                color: accent,
                letterSpacing: '0.42em',
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                textShadow: `0 0 12px ${accent}99`,
                animation: bridge.botSpeaking ? 'jarvis-flicker 1.4s ease-in-out infinite' : 'none',
              }}
            >
              ATLAS
            </span>
            <span
              className="w-1.5 h-1.5 rounded-full shrink-0"
              style={{ background: accent, boxShadow: `0 0 8px ${accent}` }}
            />
            <span
              className="text-[10px] font-medium"
              style={{
                color: accent,
                letterSpacing: '0.15em',
                fontFamily: 'ui-monospace, monospace',
              }}
            >
              {statusLabel}
            </span>
            {/* Thinking toggle (Epic T): flips the persistent reasoning mode over the WS. Purple
                matches the THINKING brain state. Disabled until the voice loop is connected. */}
            <button
              type="button"
              onClick={() => setThinking(!bridge.thinkingMode)}
              disabled={!bridge.connected}
              className="text-[10px] ml-auto px-2 py-0.5 rounded-full transition-colors disabled:opacity-40"
              style={{
                fontFamily: 'ui-monospace, monospace',
                letterSpacing: '0.14em',
                color: bridge.thinkingMode ? '#c084fc' : 'rgba(148,163,184,0.7)',
                border: `1px solid ${bridge.thinkingMode ? '#c084fc' : 'rgba(148,163,184,0.25)'}`,
                background: bridge.thinkingMode ? 'rgba(192,132,252,0.12)' : 'transparent',
                boxShadow: bridge.thinkingMode ? '0 0 10px -2px #c084fc88' : 'none',
              }}
              title={
                bridge.thinkingMode
                  ? 'Thinking mode ON — Atlas reasons before answering. Click for fast mode.'
                  : 'Fast mode. Click to make Atlas think before answering.'
              }
            >
              {bridge.thinkingMode ? 'THINK ●' : 'THINK ○'}
            </button>
            <span
              className="text-[10px]"
              style={{ color: 'rgba(148,163,184,0.7)', letterSpacing: '0.1em', fontFamily: 'ui-monospace, monospace' }}
            >
              {bridge.mode ? bridge.mode.toUpperCase() : 'LOCAL'} · $0/MIN
            </span>
          </div>

          {/* Transcript */}
          <div
            className="text-[15px] leading-snug min-h-[1.4rem] mb-2 truncate"
            style={{ color: line ? '#e2e8f0' : 'rgba(148,163,184,0.55)' }}
          >
            {line ? (
              <>
                <span style={{ color: accent, fontFamily: 'ui-monospace, monospace', fontSize: 12 }}>
                  {line.who}&nbsp;›&nbsp;
                </span>
                {line.text}
              </>
            ) : brainState === 'disconnected' ? (
              'Connecting to the voice loop…'
            ) : (
              'Listening — say something.'
            )}
          </div>

          {/* Live gauges + latest tool activity */}
          <div className="flex items-center gap-3 text-[11px] min-w-0" style={{ fontFamily: 'ui-monospace, monospace' }}>
            <Gauge label="TTFB" value={ttfbMs !== null ? `${ttfbMs}ms` : '—'} accent={accent} />
            <Gauge label="TOKENS" value={totalTokens !== null ? `${totalTokens}` : '—'} accent={accent} />
            <ToolStatusLine activity={bridge.toolActivity} />
          </div>
        </div>
      </div>

      {/* Visual EVE chose to SHOW (screenshot / image / note) — above the transcript */}
      <SurfaceVisualCard visual={bridge.surfacedVisual} />

      {/* Whole-session transcript — scrollable, sticks to the newest turn */}
      {bridge.turns.length > 0 && (
        <div
          className="relative mx-4 mb-3 px-3 py-2 rounded-lg"
          style={{ background: 'rgba(2,6,12,0.55)', border: '1px solid rgba(148,163,184,0.12)' }}
        >
          <div
            className="text-[10px] mb-1.5"
            style={{ color: 'rgba(148,163,184,0.55)', letterSpacing: '0.18em', fontFamily: 'ui-monospace, monospace' }}
          >
            SESSION TRANSCRIPT
          </div>
          <SessionTranscript turns={bridge.turns} />
        </div>
      )}

      {/* Detailed expandable trace (real usage + telemetry) */}
      {(bridge.usage || telemetry) && (
        <div className="relative px-4 pb-2">
          <XRayFooter usage={bridge.usage ?? undefined} telemetry={telemetry} />
        </div>
      )}
    </div>
  );
}

function Gauge({ label, value, accent }: { label: string; value: string; accent: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <span style={{ color: 'rgba(148,163,184,0.6)', letterSpacing: '0.1em' }}>{label}</span>
      <span style={{ color: accent, fontWeight: 600, textShadow: `0 0 8px ${accent}66` }}>{value}</span>
    </div>
  );
}
