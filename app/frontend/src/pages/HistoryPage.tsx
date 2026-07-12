// Conversation hub — the "second brain" history view.
//
// One place to browse EVERY conversation across surfaces (phone voice, desktop
// voice, typed chat), each with its own title + source badge. Selecting one shows
// the full timeline, including what Jarvis delegated (to Hermes / Codex / a tool)
// and what came back — so you can see the system actually working, not just guess.
import { useEffect, useMemo, useState } from 'react';
import {
  Search,
  Smartphone,
  Monitor,
  MessageSquare,
  ArrowRight,
  Check,
  X,
  Loader2,
  Wrench,
  ChevronRight,
  ChevronDown,
} from 'lucide-react';
import {
  listHistory,
  getHistory,
  searchHistory,
  type HistoryConversation,
  type HistoryConversationDetail,
  type HistoryMessage,
  type HistorySource,
} from '../lib/history-api';

const SOURCES: { key: HistorySource | 'all'; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'phone-voice', label: 'Phone' },
  { key: 'desktop-voice', label: 'Desktop' },
  { key: 'typed-chat', label: 'Typed' },
];

const SOURCE_META: Record<HistorySource, { label: string; color: string; Icon: typeof Smartphone }> = {
  'phone-voice': { label: 'Phone Voice', color: '#38bdf8', Icon: Smartphone },
  'desktop-voice': { label: 'Desktop Voice', color: '#2dd4bf', Icon: Monitor },
  'typed-chat': { label: 'Typed Chat', color: '#c084fc', Icon: MessageSquare },
};

function fmtWhen(ms: number): string {
  if (!ms) return '';
  const d = new Date(ms);
  const today = new Date();
  const sameDay = d.toDateString() === today.toDateString();
  const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  return sameDay ? `Today ${time}` : `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${time}`;
}

function SourceBadge({ source }: { source: HistorySource }) {
  const m = SOURCE_META[source] ?? SOURCE_META['desktop-voice'];
  const { Icon } = m;
  return (
    <span
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium tracking-wide"
      style={{ background: `${m.color}1f`, color: m.color, border: `1px solid ${m.color}40` }}
    >
      <Icon size={11} />
      {m.label}
    </span>
  );
}

export function HistoryPage() {
  const [filter, setFilter] = useState<HistorySource | 'all'>('all');
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<HistoryConversation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<HistoryConversationDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // Load list (by filter) or run a search.
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    const run = query.trim()
      ? searchHistory(query.trim())
      : listHistory(filter === 'all' ? undefined : filter);
    run
      .then((rows) => {
        if (!alive) return;
        const filtered =
          query.trim() && filter !== 'all' ? rows.filter((r) => r.source === filter) : rows;
        setItems(filtered);
      })
      .catch((e) => alive && setError(String(e.message || e)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [filter, query]);

  // Load the selected conversation's full timeline.
  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    let alive = true;
    setDetailLoading(true);
    getHistory(selectedId)
      .then((d) => alive && setDetail(d))
      .catch(() => alive && setDetail(null))
      .finally(() => alive && setDetailLoading(false));
    return () => {
      alive = false;
    };
  }, [selectedId]);

  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const it of items) c[it.source] = (c[it.source] ?? 0) + 1;
    return c;
  }, [items]);

  return (
    <div className="flex h-full min-h-0 w-full">
      {/* LEFT: list */}
      <div
        className="w-[360px] shrink-0 flex flex-col min-h-0 border-r"
        style={{ borderColor: 'var(--color-border, rgba(255,255,255,0.08))' }}
      >
        <div className="px-4 pt-4 pb-3">
          <h1 className="text-lg font-semibold mb-0.5" style={{ color: 'var(--color-text)' }}>
            History
          </h1>
          <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
            Every conversation, every surface — your second brain.
          </p>

          <div
            className="flex items-center gap-2 px-2.5 h-9 rounded-lg mb-2"
            style={{ background: 'var(--color-surface, rgba(255,255,255,0.04))', border: '1px solid var(--color-border, rgba(255,255,255,0.08))' }}
          >
            <Search size={15} style={{ color: 'var(--color-text-muted, #94a3b8)' }} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search everything said…"
              className="bg-transparent outline-none text-sm flex-1"
              style={{ color: 'var(--color-text)' }}
            />
            {query && (
              <button onClick={() => setQuery('')} style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
                <X size={14} />
              </button>
            )}
          </div>

          <div className="flex gap-1">
            {SOURCES.map((s) => {
              const active = filter === s.key;
              return (
                <button
                  key={s.key}
                  onClick={() => setFilter(s.key)}
                  className="px-2.5 py-1 rounded-md text-xs font-medium transition"
                  style={{
                    background: active ? 'var(--color-accent)' : 'transparent',
                    color: active ? '#04060b' : 'var(--color-text-muted, #94a3b8)',
                    border: '1px solid var(--color-border, rgba(255,255,255,0.08))',
                  }}
                >
                  {s.label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto px-2 pb-4">
          {loading ? (
            <div className="flex items-center justify-center py-10" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
              <Loader2 size={18} className="animate-spin" />
            </div>
          ) : error ? (
            <div className="px-3 py-4 text-sm" style={{ color: 'var(--color-error, #f43f5e)' }}>{error}</div>
          ) : items.length === 0 ? (
            <div className="px-3 py-10 text-center text-sm" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
              {query ? 'No conversations match that.' : 'No conversations yet.'}
            </div>
          ) : (
            items.map((c) => {
              const active = c.id === selectedId;
              const m = SOURCE_META[c.source] ?? SOURCE_META['desktop-voice'];
              return (
                <button
                  key={c.id}
                  onClick={() => setSelectedId(c.id)}
                  className="w-full text-left px-3 py-2.5 rounded-lg mb-1 transition"
                  style={{
                    background: active ? `${m.color}14` : 'transparent',
                    border: `1px solid ${active ? `${m.color}44` : 'transparent'}`,
                  }}
                >
                  <div className="flex items-center justify-between gap-2 mb-1">
                    <SourceBadge source={c.source} />
                    <span className="text-[10px] shrink-0" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
                      {fmtWhen(c.started_at)}
                    </span>
                  </div>
                  <div className="text-sm leading-snug line-clamp-2" style={{ color: 'var(--color-text)' }}>
                    {c.title || '(untitled)'}
                  </div>
                  <div className="flex items-center gap-2 mt-1 text-[10px]" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
                    <span>{c.msg_count} msg</span>
                    {c.tool_count > 0 && (
                      <span className="inline-flex items-center gap-0.5">
                        <Wrench size={9} /> {c.tool_count}
                      </span>
                    )}
                    {c.snippet && <span className="truncate italic">· {c.snippet.replace(/[[\]]/g, '')}</span>}
                  </div>
                </button>
              );
            })
          )}
        </div>
        <div className="px-4 py-2 text-[10px] border-t" style={{ borderColor: 'var(--color-border, rgba(255,255,255,0.08))', color: 'var(--color-text-muted, #94a3b8)' }}>
          {items.length} conversation{items.length === 1 ? '' : 's'}
          {Object.keys(counts).length > 1 && (
            <span> · {Object.entries(counts).map(([k, v]) => `${v} ${SOURCE_META[k as HistorySource]?.label ?? k}`).join(' · ')}</span>
          )}
        </div>
      </div>

      {/* RIGHT: detail */}
      <div className="flex-1 min-w-0 flex flex-col min-h-0">
        {!selectedId ? (
          <div className="flex-1 flex items-center justify-center text-sm" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
            Select a conversation to read it.
          </div>
        ) : detailLoading ? (
          <div className="flex-1 flex items-center justify-center" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
            <Loader2 size={20} className="animate-spin" />
          </div>
        ) : detail ? (
          <ConversationView detail={detail} />
        ) : (
          <div className="flex-1 flex items-center justify-center text-sm" style={{ color: 'var(--color-error, #f43f5e)' }}>
            Could not load that conversation.
          </div>
        )}
      </div>
    </div>
  );
}

function ConversationView({ detail }: { detail: HistoryConversationDetail }) {
  return (
    <>
      <div className="px-6 pt-5 pb-3 border-b" style={{ borderColor: 'var(--color-border, rgba(255,255,255,0.08))' }}>
        <div className="flex items-center gap-2 mb-1">
          <SourceBadge source={detail.source} />
          <span className="text-xs" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
            {fmtWhen(detail.started_at)} · {detail.msg_count} messages
            {detail.tool_count > 0 && ` · ${detail.tool_count} delegation${detail.tool_count === 1 ? '' : 's'}`}
            {detail.total_tokens > 0 && ` · ${detail.total_tokens.toLocaleString()} tokens`}
          </span>
        </div>
        <h2 className="text-base font-semibold" style={{ color: 'var(--color-text)' }}>
          {detail.title || '(untitled)'}
        </h2>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto px-6 py-5 space-y-3">
        {detail.messages.map((m) => (
          <MessageRow key={m.seq} m={m} />
        ))}
      </div>
    </>
  );
}

function phaseColor(phase?: string): string {
  if (phase === 'answer') return '#34d399';
  if (phase === 'fail') return '#f43f5e';
  return '#94a3b8';
}

// The rich delegation trace: Jarvis -> the per-brain waterfall, with each brain's
// try/fail/answer, latency, and the full result behind an expander.
function DelegationCard({ meta }: { meta: HistoryMessage['meta'] }) {
  const [open, setOpen] = useState(false);
  const ok = meta.status === 'ok' || meta.ok === true;
  const headColor = ok ? '#34d399' : '#f43f5e';
  return (
    <div className="flex justify-center">
      <div
        className="w-full max-w-[88%] rounded-lg px-3.5 py-2.5 text-xs"
        style={{ background: '#34d39910', border: '1px solid #34d39933' }}
      >
        <div className="flex items-center gap-1.5 font-medium" style={{ color: headColor }}>
          <span>Jarvis</span>
          <ArrowRight size={12} />
          <span>{meta.brain || meta.target || 'agent'}</span>
          {ok ? <Check size={13} /> : <X size={13} />}
          {meta.total_latency_ms != null && (
            <span
              className="ml-auto font-mono text-[10px]"
              style={{ color: 'var(--color-text-muted, #94a3b8)' }}
            >
              {(meta.total_latency_ms / 1000).toFixed(1)}s
            </span>
          )}
        </div>
        {meta.task && (
          <div className="mt-1 italic" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
            “{meta.task}”
          </div>
        )}
        <div className="mt-2 space-y-1">
          {(meta.steps ?? []).map((s, i) => {
            const c = phaseColor(s.phase);
            return (
              <div key={i} className="flex items-center gap-2 font-mono text-[11px]">
                <span style={{ color: c, minWidth: 46 }}>{s.brain}</span>
                <span style={{ color: c }}>
                  {s.phase === 'answer' ? '✓ answered' : s.phase === 'fail' ? '✗ failed' : '· trying'}
                </span>
                {s.latency_ms != null && s.latency_ms > 0 && (
                  <span style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
                    {(s.latency_ms / 1000).toFixed(1)}s
                  </span>
                )}
                {s.detail && s.phase === 'fail' && (
                  <span className="truncate" style={{ color: '#f43f5e99' }}>
                    {s.detail}
                  </span>
                )}
              </div>
            );
          })}
        </div>
        {meta.result && (
          <div className="mt-2">
            <button
              onClick={() => setOpen((o) => !o)}
              className="flex items-center gap-1 text-[11px]"
              style={{ color: 'var(--color-accent, #2dd4bf)' }}
            >
              {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
              {open ? 'Hide' : 'Show'} full result ({meta.result.length} chars)
            </button>
            {open && (
              <div
                className="mt-1 whitespace-pre-wrap rounded p-2 max-h-72 overflow-y-auto text-[12px] leading-relaxed"
                style={{ background: 'rgba(0,0,0,0.25)', color: 'var(--color-text, #e2e8f0)' }}
              >
                {meta.result}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function MessageRow({ m }: { m: HistoryMessage }) {
  // Delegation with a step tree (jarvis_agent waterfall) — the rich trace card.
  if (m.role === 'delegation' && m.meta.steps && m.meta.steps.length > 0) {
    return <DelegationCard meta={m.meta} />;
  }
  // Plain tool / delegation hand-off — compact centered trace card.
  if (m.role === 'delegation' || m.role === 'tool') {
    const ok = m.meta.status === 'ok' || m.meta.ok === true;
    const failed = m.meta.status === 'error' || m.meta.ok === false;
    const isDelegation = m.role === 'delegation';
    const color = isDelegation ? '#34d399' : '#94a3b8';
    return (
      <div className="flex justify-center">
        <div
          className="max-w-[85%] rounded-lg px-3 py-2 text-xs"
          style={{ background: `${color}12`, border: `1px solid ${color}33` }}
        >
          <div className="flex items-center gap-1.5 font-medium" style={{ color }}>
            {isDelegation ? 'Jarvis' : <Wrench size={12} />}
            {isDelegation && <ArrowRight size={12} />}
            <span>{m.meta.target || m.meta.tool}</span>
            {ok && <Check size={12} style={{ color: '#34d399' }} />}
            {failed && <X size={12} style={{ color: '#f43f5e' }} />}
          </div>
          {m.meta.args && (
            <div className="mt-1 font-mono opacity-70 break-all" style={{ color: 'var(--color-text-muted, #94a3b8)' }}>
              → {String(m.meta.args).slice(0, 240)}
            </div>
          )}
          {m.meta.detail && (
            <div className="mt-1 font-mono break-all" style={{ color: failed ? '#f43f5e' : 'var(--color-text, #e2e8f0)' }}>
              ← {String(m.meta.detail).slice(0, 240)}
            </div>
          )}
        </div>
      </div>
    );
  }

  const isUser = m.role === 'user' || m.role === 'sms';
  const accent = isUser ? '#38bdf8' : '#2dd4bf';
  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div
        className="max-w-[75%] rounded-2xl px-3.5 py-2"
        style={{
          background: isUser ? `${accent}18` : 'var(--color-surface, rgba(255,255,255,0.04))',
          border: `1px solid ${isUser ? `${accent}33` : 'var(--color-border, rgba(255,255,255,0.08))'}`,
        }}
      >
        <div className="text-[10px] mb-0.5 font-medium tracking-wide" style={{ color: accent }}>
          {m.role === 'sms' ? `SMS${m.meta.from ? ` · ${m.meta.from}` : ''}` : isUser ? 'You' : 'Jarvis'}
        </div>
        <div className="text-sm whitespace-pre-wrap leading-relaxed" style={{ color: 'var(--color-text)' }}>
          {m.text}
        </div>
      </div>
    </div>
  );
}
