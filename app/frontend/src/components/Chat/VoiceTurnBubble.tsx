// A voice-loop turn rendered as a first-class chat bubble. Mirrors
// MessageBubble's visual language (user right / Jarvis left, theme tokens)
// with a small mic badge so spoken turns are distinguishable from typed ones
// at a glance. Session rows render as a thin divider marking a sidecar
// (re)connect.
import { Mic } from 'lucide-react';
import type { TranscriptTurn } from '../../hooks/useJarvisBridge';

function timeOf(at: number): string {
  return new Date(at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

export function VoiceTurnBubble({ turn }: { turn: TranscriptTurn }) {
  if (turn.who === 'session') {
    return (
      <div
        className="flex items-center gap-2 my-3 select-none"
        style={{ color: 'var(--color-text-tertiary)', fontSize: 10, letterSpacing: '0.12em' }}
      >
        <span className="flex-1 border-t" style={{ borderColor: 'var(--color-border)' }} />
        VOICE {turn.text.toUpperCase()}
        <span className="flex-1 border-t" style={{ borderColor: 'var(--color-border)' }} />
      </div>
    );
  }

  const isUser = turn.who === 'you';
  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-4`}>
      <div
        className="max-w-[85%] px-4 py-2.5 text-sm leading-relaxed"
        style={{
          background: isUser ? 'var(--color-user-bubble)' : 'var(--color-bg-secondary)',
          color: isUser ? 'var(--color-user-bubble-text)' : 'var(--color-text)',
          border: isUser ? 'none' : '1px solid var(--color-border)',
          borderRadius: isUser
            ? 'var(--radius-xl) var(--radius-xl) var(--radius-sm) var(--radius-xl)'
            : 'var(--radius-xl) var(--radius-xl) var(--radius-xl) var(--radius-sm)',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        <div
          className="flex items-center gap-1.5 mb-1"
          style={{ fontSize: 11, opacity: 0.65, letterSpacing: '0.04em' }}
        >
          <Mic size={11} />
          {isUser ? 'you said' : 'Jarvis said'} · {timeOf(turn.at)}
        </div>
        {turn.text}
      </div>
    </div>
  );
}
