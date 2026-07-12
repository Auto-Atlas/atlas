// Phase 2: live bridge to the local voice sidecar (jarvis-sidecar).
//
// The sidecar (the atlas repo; see JARVIS_SIDECAR_DIR) owns the mic, runs STT -> LLM -> TTS
// locally, and broadcasts one-line JSON events over ws://127.0.0.1:8765. This hook
// consumes that stream and exposes a small structured state the X-Ray footer, chat
// timeline, and speaking indicator can render directly. The connection auto-reconnects,
// so the UI runs fine whether or not the sidecar is up — it just shows "disconnected".
//
// Event shapes are the contract from bridge.py (do not drift from these):
//   {"type":"status",            "mode":"local"|"showtime", "ws":"ready"}
//   {"type":"user_transcript",   "text":"..."}
//   {"type":"interim_transcript","text":"..."}
//   {"type":"user_speaking",     "speaking":true|false}      (Silero VAD)
//   {"type":"thinking",          "active":true|false}        (LLM response window)
//   {"type":"token",             "n":3,"chars":17}           (LLM stream ticks, ~15 Hz max)
//   {"type":"bot_transcript",    "text":"..."}               (what TTS is saying)
//   {"type":"bot_speaking",      "speaking":true|false}
//   {"type":"tool_call",         "tool":"open_on_pc","args":"{...}","status":"running"}
//   {"type":"tool_result",       "tool":"open_on_pc","ok":true,"detail":"{...}"}
//   {"type":"mic_level",         "value":0.34}               (real mic RMS 0..1, ~15 Hz)
//   {"type":"bot_level",         "value":0.41}               (real TTS RMS 0..1, ~15 Hz)
//   {"type":"metric",            "name":"TTFBMetricsData","processor":"...","value":0.18}
//   {"type":"usage",             "processor":"...","prompt_tokens":123,"completion_tokens":45,"total_tokens":168}
//
// High-rate signals (mic_level / bot_level / token) are deliberately kept OUT of
// React state: they land in mutable refs (`signals`) that canvas animation loops
// read every frame. State only changes on real conversational transitions, so the
// component tree re-renders a handful of times per turn, not 15x/second.
import { useCallback, useEffect, useRef, useState } from 'react';
import type { TokenUsage } from '../types';

export type JarvisEvent =
  | { type: 'status'; mode: string; ws: string }
  | { type: 'user_transcript'; text: string }
  | { type: 'interim_transcript'; text: string }
  | { type: 'user_speaking'; speaking: boolean }
  | { type: 'thinking'; active: boolean }
  | { type: 'thinking_mode'; enabled: boolean }
  | { type: 'token'; n: number; chars: number }
  | { type: 'bot_transcript'; text: string }
  | { type: 'bot_speaking'; speaking: boolean }
  | { type: 'tool_call'; tool: string; args?: string; status: string }
  | { type: 'tool_result'; tool: string; ok: boolean; detail?: string }
  | { type: 'mic_level'; value: number }
  | { type: 'bot_level'; value: number }
  | {
      type: 'delegation_start';
      deleg_id: string;
      tool?: string;
      task?: string;
      brains?: string[];
    }
  | {
      type: 'delegation_step';
      deleg_id: string;
      brain?: string;
      phase?: 'try' | 'fail' | 'answer' | 'working';
      detail?: string;
      ok?: boolean;
      latency_ms?: number;
      tokens?: number;
    }
  | {
      type: 'delegation_end';
      deleg_id: string;
      brain?: string;
      ok: boolean;
      result?: string;
      failures?: string[];
      total_latency_ms?: number;
      total_tokens?: number;
    }
  | {
      type: 'surface_visual';
      kind?: string;
      title?: string;
      visual_id?: string;
      url?: string;
      text?: string;
      /** Bounded JPEG inlined for the stage (it has no approval-api bearer to fetch by URL). */
      data_uri?: string;
    }
  | { type: 'metric'; name: string; processor?: string; value?: number }
  | {
      type: 'usage';
      processor?: string;
      prompt_tokens?: number;
      completion_tokens?: number;
      total_tokens?: number;
    };

/**
 * Mutable, render-free live signals. Animation loops read `.current` fields
 * directly each rAF tick. Every value is set ONLY by a real WS event — when the
 * sidecar is silent or disconnected these decay to zero and the visuals go calm.
 */
export interface JarvisLiveSignals {
  /** Real mic RMS 0..1 from the sidecar's input audio frames. */
  micLevel: number;
  /** Real TTS output RMS 0..1 from the sidecar's speaker frames. */
  botLevel: number;
  /** Monotonic count of LLM stream chunks this session — diff it to detect token activity. */
  tokenCount: number;
  /** performance.now() of the last token tick. */
  lastTokenAt: number;
  /** performance.now() of the last event of any kind (liveness probe). */
  lastEventAt: number;
}

/** One finished line of the session transcript. Consecutive same-speaker
 * fragments (TTS speaks sentence-by-sentence) are merged into one turn —
 * but only within a short gap; a pause longer than that starts a new turn.
 * `who: 'session'` is a divider row marking a sidecar (re)connect. */
export interface TranscriptTurn {
  /** Monotonic per-app-load id — stable across the MAX_TURNS window sliding,
   * so it's safe as a React key (array indexes shift when old turns fall off). */
  id: number;
  who: 'you' | 'jarvis' | 'session';
  text: string;
  /** Date.now() when the turn started. */
  at: number;
  /** Date.now() of the most recent fragment merged into this turn. */
  lastAt: number;
}

/** The most recent tool invocation seen on the wire — drives the live tool
 * status line. `running` flips to `ok`/`error` when the result event lands. */
export interface ToolActivity {
  tool: string;
  status: 'running' | 'ok' | 'error';
  /** Truncated args (while running) or result/error detail (when finished). */
  detail: string;
  /** Date.now() of the latest update. */
  at: number;
  /** Date.now() when the tool_call started (carried onto the result). */
  startedAt: number;
  /** Wall-clock ms from call to result, set once the result lands. */
  latencyMs?: number;
}

/** Low-churn conversational state — changes only on (re)connects and finished
 * turns, so the chat timeline can subscribe to it without re-rendering on
 * every speaking flag / metric / interim-transcript tick. */
export interface JarvisStableState {
  /** WebSocket open to the sidecar. */
  connected: boolean;
  /** "local" (free, on-device) or "showtime" (premium cloud voices), once the sidecar greets us. */
  mode: string | null;
  /** Latest finalized user utterance from STT. */
  transcript: string;
  /** Whole-session transcript: finalized user + bot turns, oldest first. */
  turns: TranscriptTurn[];
  /** Persistent THINKING toggle (Epic T): true = Atlas reasons before answering. Mirrors the
   * voice loop's thinking_state; flipped from here via setThinking. Distinct from churn.thinking
   * (the ephemeral 'reasoning right now' flag). */
  thinkingMode: boolean;
}

/** High-churn per-utterance state — speaking flags, partials, telemetry. */
export interface JarvisChurnState {
  /** In-flight partial transcript while the user is mid-sentence (empty when finalized). */
  interimTranscript: string;
  /** What Jarvis last said out loud (from the TTS text stream). */
  botTranscript: string;
  /** True while the Silero VAD hears the user talking. */
  userSpeaking: boolean;
  /** True while the LLM is composing a response (start→end of the stream). */
  thinking: boolean;
  /** True while Jarvis is talking — drives the speaking/pulse indicator and barge-in affordance. */
  botSpeaking: boolean;
  /** Latest per-metric values in seconds, keyed by Pipecat metric name (e.g. TTFBMetricsData). */
  metrics: Record<string, number>;
  /** Latest LLM token usage for the turn. */
  usage: TokenUsage | null;
  /** Most recent tool call/result this session (null until a tool runs). */
  toolActivity: ToolActivity | null;
  /** Live delegation waterfall (null until jarvis_agent runs; cleared next turn). */
  delegation: DelegationState | null;
  /** Latest visual EVE surfaced (null until surface_visual fires). */
  surfacedVisual: SurfacedVisual | null;
}

/** One brain's attempt within a delegation waterfall. */
export interface DelegationStep {
  brain: string;
  phase: 'try' | 'fail' | 'answer';
  detail: string;
  ok?: boolean;
  latencyMs?: number;
}

/** Live state of the most recent jarvis_agent delegation — drives the
 * real-time ticker on the stage so you watch the brain waterfall happen. */
export interface DelegationState {
  delegId: string;
  task: string;
  brains: string[];
  steps: DelegationStep[];
  done: boolean;
  ok?: boolean;
  winner?: string;
  totalLatencyMs?: number;
  /** Brain currently running (between its 'try' and 'answer'/'fail'). */
  activeBrain?: string;
  /** Live elapsed-time label from the latest 'working' heartbeat (e.g. "16s"). */
  activeDetail?: string;
}

/** A visual Atlas chose to SHOW (surface_visual): a desktop screenshot, an image,
 * or a text/log note. One at a time — a new one replaces the last; the card UI
 * owns dismissal locally (keyed by `at`). */
export interface SurfacedVisual {
  kind: 'desktop_screen' | 'image' | 'note';
  title: string;
  /** data: URI of the bounded JPEG (empty for notes). */
  dataUri: string;
  /** Note/log body (empty for images). */
  text: string;
  /** Date.now() when it arrived — stable identity for dismissal. */
  at: number;
}

export interface JarvisBridgeState extends JarvisStableState, JarvisChurnState {
  /** Render-free high-rate signals for canvas loops (stable ref object). */
  signals: React.MutableRefObject<JarvisLiveSignals>;
}

/** What the hook hands the provider: the two state slices kept separate so
 * they can back two contexts (stable timeline vs churny HUD). */
export interface JarvisBridgeHandle {
  stable: JarvisStableState;
  churn: JarvisChurnState;
  signals: React.MutableRefObject<JarvisLiveSignals>;
  /** Flip the persistent thinking toggle; sends a control message up the same WS. No-op if the
   * socket isn't open (the next status greeting re-syncs the real state). */
  setThinking: (on: boolean) => void;
}

// Whole-session transcript cap — old turns fall off the front so a marathon
// session can't grow React state without bound.
const MAX_TURNS = 200;

// Same-speaker fragments are one turn only when they arrive close together.
// Without this gap rule, a day with zero user turns fused EVERY Jarvis
// utterance from 2:31 PM to 6:08 PM into a single chat bubble.
const MERGE_GAP_MS = 10_000;

// Monotonic turn id source (see TranscriptTurn.id).
let nextTurnId = 1;

/**
 * Append a finalized line to the transcript, merging consecutive fragments
 * from the same speaker (TTS emits bot_transcript sentence-by-sentence) into
 * a single readable turn. Returns a new array — never mutates `turns`.
 */
function appendTurn(
  turns: TranscriptTurn[],
  who: TranscriptTurn['who'],
  text: string,
): TranscriptTurn[] {
  const now = Date.now();
  const last = turns[turns.length - 1];
  if (last && last.who === who && now - last.lastAt <= MERGE_GAP_MS) {
    return [
      ...turns.slice(0, -1),
      { ...last, text: `${last.text} ${text}`.trim(), lastAt: now },
    ];
  }
  return [...turns, { id: nextTurnId++, who, text, at: now, lastAt: now }].slice(-MAX_TURNS);
}

/** A divider row marking a sidecar (re)connect — never doubled up. */
function appendSessionDivider(turns: TranscriptTurn[], mode: string): TranscriptTurn[] {
  const last = turns[turns.length - 1];
  if (!last || last.who === 'session') return turns;
  const now = Date.now();
  return [
    ...turns,
    {
      id: nextTurnId++,
      who: 'session' as const,
      text: `session · ${mode} · ${new Date(now).toLocaleTimeString()}`,
      at: now,
      lastAt: now,
    },
  ].slice(-MAX_TURNS);
}

const INITIAL_SIGNALS: JarvisLiveSignals = {
  micLevel: 0,
  botLevel: 0,
  tokenCount: 0,
  lastTokenAt: 0,
  lastEventAt: 0,
};

// Reconnect backoff bounds: 1s doubling to a 15s cap, reset on a real open.
const RETRY_MIN_MS = 1000;
const RETRY_MAX_MS = 15_000;

export function useJarvisBridge(url = 'ws://127.0.0.1:8765'): JarvisBridgeHandle {
  const signals = useRef<JarvisLiveSignals>({ ...INITIAL_SIGNALS });
  const [stable, setStable] = useState<JarvisStableState>({
    connected: false,
    mode: null,
    transcript: '',
    turns: [],
    thinkingMode: false,
  });
  const [churn, setChurn] = useState<JarvisChurnState>({
    interimTranscript: '',
    botTranscript: '',
    userSpeaking: false,
    thinking: false,
    botSpeaking: false,
    metrics: {},
    usage: null,
    toolActivity: null,
    delegation: null,
    surfacedVisual: null,
  });
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    let alive = true;
    let retry: ReturnType<typeof setTimeout> | null = null;
    let retryMs = RETRY_MIN_MS;

    // Every setter below returns `prev` untouched when the event carries no
    // actual change, so repeated speaking/metric/interim frames don't force a
    // re-render of every context consumer.
    const apply = (ev: JarvisEvent) => {
      signals.current.lastEventAt = performance.now();

      // High-rate signals: write to the ref and skip React entirely.
      switch (ev.type) {
        case 'mic_level':
          signals.current.micLevel = ev.value;
          return;
        case 'bot_level':
          signals.current.botLevel = ev.value;
          return;
        case 'token':
          signals.current.tokenCount += ev.n;
          signals.current.lastTokenAt = performance.now();
          return;
      }

      switch (ev.type) {
        case 'status': {
          const { mode } = ev;
          setStable((prev) => {
            // A status greeting means a (re)connected sidecar — mark the seam
            // so two sessions can never read as one continuous conversation.
            const turns = appendSessionDivider(prev.turns, mode);
            if (prev.mode === mode && turns === prev.turns) return prev;
            return { ...prev, mode, turns };
          });
          break;
        }
        case 'user_transcript': {
          const { text } = ev;
          setStable((prev) => ({
            ...prev,
            transcript: text,
            turns: appendTurn(prev.turns, 'you', text),
          }));
          setChurn((prev) => {
            const next =
              prev.interimTranscript === '' ? prev : { ...prev, interimTranscript: '' };
            // A new user turn dismisses the previous delegation ticker.
            if (next.delegation && next.delegation.done) {
              return next === prev ? { ...prev, delegation: null } : { ...next, delegation: null };
            }
            return next;
          });
          break;
        }
        case 'interim_transcript': {
          const { text } = ev;
          setChurn((prev) =>
            prev.interimTranscript === text ? prev : { ...prev, interimTranscript: text },
          );
          break;
        }
        case 'user_speaking': {
          const { speaking } = ev;
          setChurn((prev) =>
            prev.userSpeaking === speaking ? prev : { ...prev, userSpeaking: speaking },
          );
          break;
        }
        case 'thinking': {
          const { active } = ev;
          setChurn((prev) => (prev.thinking === active ? prev : { ...prev, thinking: active }));
          break;
        }
        case 'thinking_mode': {
          const { enabled } = ev;
          setStable((prev) => (prev.thinkingMode === enabled ? prev : { ...prev, thinkingMode: enabled }));
          break;
        }
        case 'bot_transcript': {
          const { text } = ev;
          setStable((prev) => ({ ...prev, turns: appendTurn(prev.turns, 'jarvis', text) }));
          setChurn((prev) =>
            prev.botTranscript === text ? prev : { ...prev, botTranscript: text },
          );
          break;
        }
        case 'tool_call': {
          const now = Date.now();
          const toolActivity: ToolActivity = {
            tool: ev.tool,
            status: 'running',
            detail: ev.args ?? '',
            at: now,
            startedAt: now,
          };
          setChurn((prev) => ({ ...prev, toolActivity }));
          break;
        }
        case 'tool_result': {
          const now = Date.now();
          setChurn((prev) => {
            // Carry the matching call's start time forward to compute latency (same tool, still running).
            const startedAt =
              prev.toolActivity && prev.toolActivity.tool === ev.tool
                ? prev.toolActivity.startedAt
                : now;
            const toolActivity: ToolActivity = {
              tool: ev.tool,
              status: ev.ok ? 'ok' : 'error',
              detail: ev.detail ?? '',
              at: now,
              startedAt,
              latencyMs: now - startedAt,
            };
            return { ...prev, toolActivity };
          });
          break;
        }
        case 'delegation_start': {
          const delegation: DelegationState = {
            delegId: ev.deleg_id,
            task: ev.task ?? '',
            brains: ev.brains ?? [],
            steps: [],
            done: false,
          };
          setChurn((prev) => ({ ...prev, delegation }));
          break;
        }
        case 'delegation_step': {
          const { deleg_id } = ev;
          setChurn((prev) => {
            if (!prev.delegation || prev.delegation.delegId !== deleg_id) return prev;
            // 'working' = a live heartbeat: update the elapsed label only, never
            // append (it would flood steps every few seconds).
            if (ev.phase === 'working') {
              return {
                ...prev,
                delegation: {
                  ...prev.delegation,
                  activeBrain: ev.brain ?? prev.delegation.activeBrain,
                  activeDetail: ev.detail ?? '',
                },
              };
            }
            const step: DelegationStep = {
              brain: ev.brain ?? '?',
              phase: ev.phase ?? 'try',
              detail: ev.detail ?? '',
              ok: ev.ok,
              latencyMs: ev.latency_ms,
            };
            return {
              ...prev,
              delegation: {
                ...prev.delegation,
                steps: [...prev.delegation.steps, step],
                // a 'try' marks the new active brain; 'answer'/'fail' clears the live label
                activeBrain: ev.phase === 'try' ? ev.brain : prev.delegation.activeBrain,
                activeDetail: ev.phase === 'try' ? '' : prev.delegation.activeDetail,
              },
            };
          });
          break;
        }
        case 'delegation_end': {
          const { deleg_id } = ev;
          setChurn((prev) => {
            if (!prev.delegation || prev.delegation.delegId !== deleg_id) return prev;
            return {
              ...prev,
              delegation: {
                ...prev.delegation,
                done: true,
                ok: ev.ok,
                winner: ev.brain,
                totalLatencyMs: ev.total_latency_ms,
              },
            };
          });
          break;
        }
        case 'surface_visual': {
          // A visual with neither an image nor text renders nothing — drop it.
          const kind =
            ev.kind === 'desktop_screen' || ev.kind === 'image' || ev.kind === 'note'
              ? ev.kind
              : null;
          if (!kind || (!ev.data_uri && !ev.text)) break;
          const surfacedVisual: SurfacedVisual = {
            kind,
            title: ev.title ?? '',
            dataUri: ev.data_uri ?? '',
            text: ev.text ?? '',
            at: Date.now(),
          };
          setChurn((prev) => ({ ...prev, surfacedVisual }));
          break;
        }
        case 'bot_speaking': {
          const { speaking } = ev;
          setChurn((prev) =>
            prev.botSpeaking === speaking ? prev : { ...prev, botSpeaking: speaking },
          );
          break;
        }
        case 'metric': {
          if (typeof ev.value !== 'number') break;
          const { name, value } = ev;
          setChurn((prev) =>
            prev.metrics[name] === value
              ? prev
              : { ...prev, metrics: { ...prev.metrics, [name]: value } },
          );
          break;
        }
        case 'usage': {
          const usage: TokenUsage = {
            prompt_tokens: ev.prompt_tokens ?? 0,
            completion_tokens: ev.completion_tokens ?? 0,
            total_tokens:
              ev.total_tokens ?? (ev.prompt_tokens ?? 0) + (ev.completion_tokens ?? 0),
          };
          setChurn((prev) =>
            prev.usage &&
            prev.usage.prompt_tokens === usage.prompt_tokens &&
            prev.usage.completion_tokens === usage.completion_tokens &&
            prev.usage.total_tokens === usage.total_tokens
              ? prev
              : { ...prev, usage },
          );
          break;
        }
      }
    };

    const scheduleReconnect = () => {
      // Capped exponential backoff with jitter so a dead sidecar isn't hammered
      // every second forever; resets to 1s the moment a connection opens.
      retry = setTimeout(connect, retryMs + Math.random() * retryMs * 0.25);
      retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
    };

    const connect = () => {
      if (!alive) return;
      let sock: WebSocket;
      try {
        sock = new WebSocket(url);
      } catch {
        scheduleReconnect();
        return;
      }
      wsRef.current = sock;

      sock.onopen = () => {
        if (!alive) return;
        retryMs = RETRY_MIN_MS;
        setStable((p) => (p.connected ? p : { ...p, connected: true }));
      };
      sock.onmessage = (e) => {
        if (!alive) return;
        try {
          apply(JSON.parse(e.data) as JarvisEvent);
        } catch {
          /* ignore malformed frames — never let the UI crash on bad input */
        }
      };
      sock.onclose = () => {
        if (!alive) return;
        // Hard zero every live signal: a dead socket must read as a calm brain.
        signals.current.micLevel = 0;
        signals.current.botLevel = 0;
        // A closed socket is a dead sidecar session: clear the transcript so
        // the next session starts a fresh bubble instead of growing the old
        // one forever (the 6/10 mega-bubble was hours of restarts fused) —
        // and the telemetry, so "CONNECTING" never shows stale numbers.
        setStable((p) => ({ ...p, connected: false, transcript: '', turns: [] }));
        setChurn((p) => ({
          ...p,
          interimTranscript: '',
          botTranscript: '',
          userSpeaking: false,
          thinking: false,
          botSpeaking: false,
          metrics: {},
          usage: null,
          delegation: null,
        }));
        scheduleReconnect(); // auto-reconnect until the sidecar is up
      };
      // onerror is followed by onclose; let onclose own the reconnect.
      sock.onerror = () => sock.close();
    };

    connect();
    return () => {
      alive = false;
      if (retry) clearTimeout(retry);
      const sock = wsRef.current;
      if (sock) {
        sock.onopen = null;
        sock.onmessage = null;
        sock.onclose = null;
        sock.onerror = null;
        sock.close();
      }
    };
  }, [url]);

  // Stable identity (wsRef is a ref) so the actions context doesn't churn every render.
  const setThinking = useCallback((on: boolean) => {
    const sock = wsRef.current;
    if (sock && sock.readyState === WebSocket.OPEN) {
      sock.send(JSON.stringify({ type: 'set_thinking', on }));
    }
  }, []);

  return { stable, churn, signals, setThinking };
}
