// The stage-side renderer for EVE's surface_visual events — when Atlas decides to
// SHOW something (a desktop screenshot, an image, a note/log) instead of only
// saying it. One card at a time (a new visual replaces the last); dismissal is
// local, keyed by the visual's arrival timestamp so the same card never
// re-appears after being closed, while a genuinely new one always does.
// Images arrive as bounded data: URIs (the stage has no approval-api bearer to
// fetch by URL). Click an image for a full-screen look; Esc or click dismisses.
import { useEffect, useState } from 'react';
import { Image as ImageIcon, Monitor, StickyNote, X } from 'lucide-react';
import type { SurfacedVisual } from '../../hooks/useJarvisBridge';

const KIND_META = {
  desktop_screen: { Icon: Monitor, label: 'DESKTOP' },
  image: { Icon: ImageIcon, label: 'IMAGE' },
  note: { Icon: StickyNote, label: 'NOTE' },
} as const;

export function SurfaceVisualCard({ visual }: { visual: SurfacedVisual | null }) {
  const [dismissedAt, setDismissedAt] = useState<number | null>(null);
  const [zoomed, setZoomed] = useState(false);

  // A new visual (different arrival time) always re-opens the card.
  useEffect(() => {
    setZoomed(false);
  }, [visual?.at]);

  useEffect(() => {
    if (!zoomed) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setZoomed(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [zoomed]);

  if (!visual || dismissedAt === visual.at) return null;
  const { Icon, label } = KIND_META[visual.kind];

  return (
    <div
      className="relative mx-4 mb-3 rounded-lg overflow-hidden"
      style={{ background: 'rgba(2,6,12,0.55)', border: '1px solid rgba(148,163,184,0.18)' }}
    >
      <div className="flex items-center gap-2 px-3 pt-2">
        <Icon size={12} style={{ color: 'rgba(148,163,184,0.8)' }} />
        <span
          className="text-[10px]"
          style={{
            color: 'rgba(148,163,184,0.55)',
            letterSpacing: '0.18em',
            fontFamily: 'ui-monospace, monospace',
          }}
        >
          {label}
          {visual.title ? ` · ${visual.title.toUpperCase()}` : ''}
        </span>
        <button
          type="button"
          aria-label="Dismiss visual"
          onClick={() => setDismissedAt(visual.at)}
          className="ml-auto p-0.5 rounded transition-colors"
          style={{ color: 'rgba(148,163,184,0.6)' }}
        >
          <X size={13} />
        </button>
      </div>

      {visual.dataUri ? (
        <img
          src={visual.dataUri}
          alt={visual.title || 'Visual from Atlas'}
          onClick={() => setZoomed(true)}
          className="block w-full cursor-zoom-in px-3 py-2"
          style={{ maxHeight: 260, objectFit: 'contain' }}
        />
      ) : (
        <pre
          className="px-3 py-2 m-0 text-[12px] leading-relaxed overflow-auto whitespace-pre-wrap"
          style={{
            color: '#cbd5e1',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            maxHeight: 220,
          }}
        >
          {visual.text}
        </pre>
      )}

      {zoomed && visual.dataUri && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center cursor-zoom-out"
          style={{ background: 'rgba(0,0,0,0.85)' }}
          onClick={() => setZoomed(false)}
        >
          <img
            src={visual.dataUri}
            alt={visual.title || 'Visual from Atlas'}
            className="max-w-[94vw] max-h-[94vh] object-contain"
          />
        </div>
      )}
    </div>
  );
}
