// Live delegation ticker for the JARVIS stage. When Jarvis hands a task to the
// brain chain (jarvis_agent), this shows the waterfall happening in real time —
// codex → glm → local — each row flipping to trying / failed / answered with its
// latency, then collapsing to the winner. Driven by the delegation_* events on
// the existing metrics WebSocket (no new socket). Renders nothing when idle.
import type { DelegationState } from '../../hooks/useJarvisBridge';

const PHASE_COLOR: Record<string, string> = {
  try: '#94a3b8',
  fail: '#f43f5e',
  answer: '#34d399',
};

export function DelegationTicker({ delegation }: { delegation: DelegationState | null }) {
  if (!delegation) return null;

  // Latest phase seen per brain, so each brain shows one evolving row.
  const byBrain = new Map<string, { phase: string; latencyMs?: number; detail: string }>();
  for (const s of delegation.steps) {
    byBrain.set(s.brain, { phase: s.phase, latencyMs: s.latencyMs, detail: s.detail });
  }
  const rows = delegation.brains.length
    ? delegation.brains.map((b) => ({ brain: b, ...(byBrain.get(b) ?? { phase: 'pending', detail: '' }) }))
    : [...byBrain.entries()].map(([brain, v]) => ({ brain, ...v }));

  const headColor = delegation.done ? (delegation.ok ? '#34d399' : '#f43f5e') : '#38bdf8';

  return (
    <div
      className="absolute top-20 left-6 z-20 max-w-[20rem] rounded-xl px-3.5 py-3 text-xs"
      style={{
        background: 'rgba(8,12,20,0.72)',
        border: `1px solid ${headColor}44`,
        backdropFilter: 'blur(10px)',
        boxShadow: `0 0 24px ${headColor}22`,
        animation: 'stage-rise 280ms ease-out',
      }}
    >
      <div
        className="flex items-center gap-1.5 font-medium mb-1.5"
        style={{ color: headColor, letterSpacing: '0.04em', fontFamily: 'ui-monospace, monospace' }}
      >
        <span
          className="w-1.5 h-1.5 rounded-full"
          style={{
            background: headColor,
            boxShadow: `0 0 8px ${headColor}`,
            animation: delegation.done ? 'none' : 'stage-pulse 0.7s ease-in-out infinite',
          }}
        />
        {delegation.done
          ? `DELEGATED → ${(delegation.winner ?? 'failed').toUpperCase()}`
          : 'DELEGATING…'}
        {delegation.totalLatencyMs != null && (
          <span className="ml-auto opacity-60">{(delegation.totalLatencyMs / 1000).toFixed(1)}s</span>
        )}
      </div>

      {delegation.task && (
        <div className="italic mb-2 line-clamp-2" style={{ color: 'rgba(148,163,184,0.75)' }}>
          “{delegation.task}”
        </div>
      )}

      <div className="space-y-1" style={{ fontFamily: 'ui-monospace, monospace' }}>
        {rows.map((r) => {
          const c = PHASE_COLOR[r.phase] ?? '#64748b';
          // The brain that's currently running shows a live "working Ns" counter.
          const isActive = !delegation.done && r.phase === 'try' && r.brain === delegation.activeBrain;
          const label =
            r.phase === 'answer'
              ? '✓ answered'
              : r.phase === 'fail'
                ? '✗ failed'
                : isActive
                  ? `working ${delegation.activeDetail ?? ''}`.trim()
                  : r.phase === 'try'
                    ? '· trying…'
                    : 'queued';
          return (
            <div key={r.brain} className="flex items-center gap-2 text-[11px]">
              {isActive && (
                <span
                  className="w-1.5 h-1.5 rounded-full"
                  style={{ background: c, boxShadow: `0 0 6px ${c}`, animation: 'stage-pulse 0.7s ease-in-out infinite' }}
                />
              )}
              <span style={{ color: c, minWidth: 44 }}>{r.brain}</span>
              <span style={{ color: c }}>{label}</span>
              {r.latencyMs != null && r.latencyMs > 0 && (
                <span style={{ color: 'rgba(148,163,184,0.6)' }}>{(r.latencyMs / 1000).toFixed(1)}s</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
