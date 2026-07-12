// Live tool activity + session transcript, shared by the LiveVoicePanel and
// the /stage page. Both render ONLY real sidecar WS events: the status line
// mirrors the latest tool_call/tool_result frame, and the transcript is the
// accumulated user/bot turns from useJarvisBridge. Nothing here animates on
// its own — a row appears exactly when the corresponding event arrived.
import { useEffect, useRef } from 'react';
import { motion } from 'motion/react';
import type { ToolActivity, TranscriptTurn } from '../../hooks/useJarvisBridge';
import { toolVisual } from '../../lib/toolVisuals';

const MONO = 'ui-monospace, SFMono-Regular, Menlo, monospace';

const TOOL_COLORS: Record<ToolActivity['status'], string> = {
  running: '#38bdf8',
  ok: '#34d399',
  error: '#f87171',
};

function fmtLatency(ms?: number): string {
  if (ms == null) return '';
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Live readout of the most recent tool invocation, rendered the human way: the tool's icon +
 * a present-tense phrase ("Checking your email…") while running, then its name + DONE/FAILED +
 * latency. The icon pulses while running; the row eases in fresh on each new tool call.
 */
export function ToolStatusLine({ activity }: { activity: ToolActivity | null }) {
  if (!activity) return null;
  const color = TOOL_COLORS[activity.status];
  const visual = toolVisual(activity.tool);
  const Icon = visual.Icon;
  const running = activity.status === 'running';
  const label = running ? 'RUNNING' : activity.status === 'ok' ? 'DONE' : 'FAILED';
  const lat = fmtLatency(activity.latencyMs);
  const primary = running ? visual.running : visual.title;
  return (
    <motion.div
      key={`${activity.tool}-${activity.startedAt}`}
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.22, ease: 'easeOut' }}
      className="flex items-center gap-2 min-w-0 text-[12px]"
      title={activity.detail || undefined}
    >
      <motion.span
        animate={running ? { opacity: [0.5, 1, 0.5] } : { opacity: 1 }}
        transition={
          running ? { duration: 1.1, repeat: Infinity, ease: 'easeInOut' } : { duration: 0.2 }
        }
        style={{ color, display: 'inline-flex', filter: `drop-shadow(0 0 6px ${color}66)` }}
      >
        <Icon size={14} strokeWidth={2.2} />
      </motion.span>
      <span style={{ color, fontWeight: 600 }} className="truncate">
        {primary}
      </span>
      <span
        className="text-[10px] shrink-0"
        style={{ color: `${color}cc`, fontFamily: MONO, letterSpacing: '0.1em' }}
      >
        {label}
        {lat && ` · ${lat}`}
      </span>
    </motion.div>
  );
}

/**
 * Scrollable whole-session transcript. Sticks to the newest turn unless the
 * user has scrolled up to review — then it stays put until they return to
 * the bottom.
 */
export function SessionTranscript({
  turns,
  maxHeight = 132,
}: {
  turns: TranscriptTurn[];
  maxHeight?: number;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickToBottom = useRef(true);
  const lastTurn = turns[turns.length - 1];

  useEffect(() => {
    const el = scrollRef.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [turns.length, lastTurn?.text]);

  if (turns.length === 0) return null;

  return (
    <div
      ref={scrollRef}
      onScroll={(e) => {
        const el = e.currentTarget;
        stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
      }}
      className="overflow-y-auto pr-1 space-y-1.5"
      style={{ maxHeight, scrollbarWidth: 'thin' }}
    >
      {turns.map((turn, i) => {
        if (turn.who === 'session') {
          return (
            <div
              key={`${turn.at}-${i}`}
              className="flex items-center gap-2 py-0.5"
              style={{ fontFamily: MONO, fontSize: 10, letterSpacing: '0.14em', color: 'rgba(148,163,184,0.55)' }}
            >
              <span className="flex-1 border-t" style={{ borderColor: 'rgba(148,163,184,0.25)' }} />
              {turn.text.toUpperCase()}
              <span className="flex-1 border-t" style={{ borderColor: 'rgba(148,163,184,0.25)' }} />
            </div>
          );
        }
        const accent = turn.who === 'you' ? '#38bdf8' : '#f59e0b';
        return (
          <div key={`${turn.at}-${i}`} className="text-[13px] leading-snug" style={{ color: '#cbd5e1' }}>
            <span
              style={{
                color: accent,
                fontFamily: MONO,
                fontSize: 11,
                letterSpacing: '0.08em',
              }}
            >
              {turn.who === 'you' ? 'YOU' : 'JARVIS'}&nbsp;›&nbsp;
            </span>
            {turn.text}
          </div>
        );
      })}
    </div>
  );
}
