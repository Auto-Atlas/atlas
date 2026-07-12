// Phone app shell — a real mobile app feel (bottom tab bar, safe-areas) wrapping
// the world-class desktop UI. ONE shared JarvisBridge so the Talk stage and the
// Conversation transcript read the same live turns. The stage + voice control are
// always mounted (a call keeps running while you switch tabs); other tabs render
// as full-screen overlays above it. Tabs that need the approval backend
// (Approvals / Activity / Memory / Skills) are wired in a later pass.
import { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { AudioLines, MessageSquareText, Settings2 } from 'lucide-react';
import { ErrorBoundary } from './components/ErrorBoundary';
import {
  JarvisBridgeProvider,
  useJarvisBridgeState,
  useJarvisStableState,
} from './components/Chat/JarvisBridgeContext';
import { Stage } from './pages/JarvisStagePage';
import { PhoneVoiceControl } from './components/Chat/PhoneVoiceControl';
import { resolveJarvisWsUrl, resolveRtcBase } from './lib/jarvisWs';
import './index.css';

document.documentElement.classList.add('dark');
// Reserve space for the bottom tab bar so the stage's wordmark + subtitle lift
// above it instead of hiding behind. Read by JarvisStagePage (defaults to 0 on
// desktop, where there is no tab bar).
document.documentElement.style.setProperty(
  '--eve-bottom-inset',
  'calc(env(safe-area-inset-bottom, 0px) + 72px)',
);

type TabId = 'talk' | 'conversation' | 'settings';

const TABS: { id: TabId; label: string; Icon: typeof AudioLines }[] = [
  { id: 'talk', label: 'Talk', Icon: AudioLines },
  { id: 'conversation', label: 'Chat', Icon: MessageSquareText },
  { id: 'settings', label: 'Settings', Icon: Settings2 },
];

const SAFE_TOP = 'calc(env(safe-area-inset-top, 0px) + 1rem)';

function MobileApp() {
  const [tab, setTab] = useState<TabId>('talk');
  return (
    <JarvisBridgeProvider url={resolveJarvisWsUrl()}>
      {/* Always mounted: the stage (your UI) + the voice call, so switching tabs
          never drops a live conversation. */}
      <Stage />
      <PhoneVoiceControl rtcBase={resolveRtcBase()} />

      {tab === 'conversation' && <ConversationTab />}
      {tab === 'settings' && <SettingsTab />}

      <BottomTabBar tab={tab} onTab={setTab} />
    </JarvisBridgeProvider>
  );
}

/** A full-screen overlay pane that sits above the stage (z-40) but below the tab
 *  bar (z-50). Deep-space background so the stage doesn't bleed through. */
function Pane({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div
      className="fixed inset-0 z-40 overflow-y-auto"
      style={{
        background: 'radial-gradient(120% 120% at 50% 0%, #0a1018 0%, #060912 55%, #04060b 100%)',
        paddingTop: SAFE_TOP,
        paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 5.5rem)',
      }}
    >
      <h1 className="px-5 pt-2 pb-4 text-2xl font-semibold text-slate-100">{title}</h1>
      <div className="px-4">{children}</div>
    </div>
  );
}

function ConversationTab() {
  const { turns } = useJarvisStableState();
  const live = useJarvisBridgeState();
  const empty =
    turns.length === 0 && !live.interimTranscript && !(live.botSpeaking && live.botTranscript);

  return (
    <Pane title="Conversation">
      {empty ? (
        <p className="px-1 text-slate-400 text-[15px] leading-relaxed">
          Nothing yet — go to <span className="text-teal-300">Talk</span>, tap the orb, and say
          something. Your words and Atlas's replies show up here.
        </p>
      ) : (
        <div className="flex flex-col gap-2.5">
          {turns.map((t) =>
            t.who === 'session' ? (
              <div key={t.id} className="my-1 text-center text-[11px] uppercase tracking-widest text-slate-500">
                — new session —
              </div>
            ) : (
              <Bubble key={t.id} who={t.who} text={t.text} />
            ),
          )}
          {live.interimTranscript && <Bubble who="you" text={live.interimTranscript} faded />}
          {live.botSpeaking && live.botTranscript && <Bubble who="jarvis" text={live.botTranscript} faded />}
        </div>
      )}
    </Pane>
  );
}

function Bubble({ who, text, faded }: { who: 'you' | 'jarvis'; text: string; faded?: boolean }) {
  const mine = who === 'you';
  return (
    <div className={`flex ${mine ? 'justify-end' : 'justify-start'}`}>
      <div
        className="max-w-[82%] rounded-2xl px-3.5 py-2.5 text-[15px] leading-snug"
        style={{
          background: mine ? 'rgba(45,212,191,0.14)' : 'rgba(148,163,184,0.10)',
          border: `1px solid ${mine ? 'rgba(45,212,191,0.35)' : 'rgba(148,163,184,0.22)'}`,
          color: '#e2e8f0',
          opacity: faded ? 0.6 : 1,
        }}
      >
        <div
          className="mb-0.5 text-[10px] uppercase tracking-widest"
          style={{ color: mine ? '#5eead4' : '#fbbf24' }}
        >
          {mine ? 'You' : 'Atlas'}
        </div>
        {text}
      </div>
    </div>
  );
}

function SettingsTab() {
  const s = useJarvisBridgeState();
  const rows: { label: string; value: string }[] = [
    { label: 'Voice link', value: s.connected ? 'Connected' : 'Not connected' },
    { label: 'Mode', value: (s.mode ?? 'local').toUpperCase() },
    { label: 'Events (WS)', value: resolveJarvisWsUrl() },
    { label: 'Voice (WebRTC)', value: resolveRtcBase() },
  ];
  return (
    <Pane title="Settings">
      <div className="flex flex-col gap-2">
        {rows.map((r) => (
          <div
            key={r.label}
            className="flex flex-col gap-0.5 rounded-xl px-3.5 py-3"
            style={{ background: 'rgba(148,163,184,0.06)', border: '1px solid rgba(148,163,184,0.14)' }}
          >
            <span className="text-[11px] uppercase tracking-widest text-slate-500">{r.label}</span>
            <span className="break-all text-[14px] text-slate-200">{r.value}</span>
          </div>
        ))}
        <p className="px-1 pt-3 text-[13px] leading-relaxed text-slate-500">
          Voice (male/female), the daily briefing toggle, and the Approvals, Activity, Memory and
          Skills tabs are wired in next.
        </p>
      </div>
    </Pane>
  );
}

function BottomTabBar({ tab, onTab }: { tab: TabId; onTab: (t: TabId) => void }) {
  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-50 flex items-stretch justify-around"
      style={{
        paddingBottom: 'env(safe-area-inset-bottom, 0px)',
        background: 'rgba(6,9,18,0.92)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        borderTop: '1px solid rgba(148,163,184,0.16)',
      }}
    >
      {TABS.map(({ id, label, Icon }) => {
        const active = tab === id;
        return (
          <button
            key={id}
            onClick={() => onTab(id)}
            className="flex flex-1 flex-col items-center gap-1 py-2.5"
            style={{ color: active ? '#2dd4bf' : 'rgba(148,163,184,0.7)' }}
          >
            <Icon size={22} strokeWidth={active ? 2.4 : 1.8} />
            <span className="text-[11px] font-medium tracking-wide">{label}</span>
          </button>
        );
      })}
    </nav>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <MobileApp />
    </ErrorBoundary>
  </StrictMode>,
);
