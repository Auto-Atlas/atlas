// Single shared connection to the voice sidecar, so the LiveVoicePanel and the
// InputArea mic toggle read the same state from one WebSocket instead of each
// opening their own.
//
// The state is split across two contexts: a low-churn one (connect/turn
// boundaries) and a high-churn one (speaking flags, partials, telemetry).
// The chat timeline subscribes only to the stable slice via
// useJarvisStableState(), so interim transcripts and metrics never force a
// re-render of every message bubble.
import { createContext, useContext, useMemo, type ReactNode } from 'react';
import {
  useJarvisBridge,
  type JarvisBridgeState,
  type JarvisChurnState,
  type JarvisLiveSignals,
  type JarvisStableState,
} from '../../hooks/useJarvisBridge';

type ChurnValue = JarvisChurnState & { signals: React.MutableRefObject<JarvisLiveSignals> };

/** Control actions (UI -> voice loop). Separate from state so a button consumer doesn't
 * re-render on every transcript/metric tick. */
interface BridgeActions {
  setThinking: (on: boolean) => void;
}

const StableContext = createContext<JarvisStableState | null>(null);
const ChurnContext = createContext<ChurnValue | null>(null);
const ActionsContext = createContext<BridgeActions | null>(null);

const NO_ACTIONS: BridgeActions = { setThinking: () => {} };

export function JarvisBridgeProvider({
  children,
  url,
}: {
  children: ReactNode;
  /** Override the bridge WebSocket URL. Omit to use the loopback default
   * (ws://127.0.0.1:8765) — the phone build passes a wss://<host>/ws URL. */
  url?: string;
}) {
  const { stable, churn, signals, setThinking } = useJarvisBridge(url);
  const churnValue = useMemo(() => ({ ...churn, signals }), [churn, signals]);
  const actions = useMemo(() => ({ setThinking }), [setThinking]);
  return (
    <StableContext.Provider value={stable}>
      <ChurnContext.Provider value={churnValue}>
        <ActionsContext.Provider value={actions}>{children}</ActionsContext.Provider>
      </ChurnContext.Provider>
    </StableContext.Provider>
  );
}

// Stable zeroed signal ref for use outside the provider — reads as "no activity",
// which keeps any visual honest (calm) when there's no live connection.
const DEAD_SIGNALS: { current: JarvisLiveSignals } = {
  current: { micLevel: 0, botLevel: 0, tokenCount: 0, lastTokenAt: 0, lastEventAt: 0 },
};

const DISCONNECTED_STABLE: JarvisStableState = {
  connected: false,
  mode: null,
  transcript: '',
  turns: [],
  thinkingMode: false,
};

const DISCONNECTED_CHURN: ChurnValue = {
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
  signals: DEAD_SIGNALS,
};

/** Read only the low-churn voice-loop state (connection, mode, finished turns).
 * Components that render the transcript timeline should use this so they don't
 * re-render on every speaking/metric/interim event. */
export function useJarvisStableState(): JarvisStableState {
  return useContext(StableContext) ?? DISCONNECTED_STABLE;
}

/** Read live voice-loop state. Returns a disconnected snapshot if used outside the provider. */
export function useJarvisBridgeState(): JarvisBridgeState {
  const stable = useJarvisStableState();
  const churn = useContext(ChurnContext) ?? DISCONNECTED_CHURN;
  return useMemo(() => ({ ...stable, ...churn }), [stable, churn]);
}

/** Control actions for the voice loop (e.g. the thinking toggle). No-op outside the provider. */
export function useBridgeActions(): BridgeActions {
  return useContext(ActionsContext) ?? NO_ACTIONS;
}
