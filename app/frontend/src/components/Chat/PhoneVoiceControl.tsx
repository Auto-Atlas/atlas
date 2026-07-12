// Tap-to-talk control layer for the phone page. Sits on top of the avatar
// stage and owns the WebRTC call (mic up, Jarvis's voice out) via usePhoneVoice.
// The avatar itself reacts to the conversation independently through the metrics
// bridge, so this layer is just the call affordance: one big button to start,
// then a minimal mute / end-call bar while live.
import { Mic, MicOff, PhoneOff, Loader2, AudioLines } from 'lucide-react';
import { usePhoneVoice } from '../../hooks/usePhoneVoice';

const ACCENT = '#2dd4bf';
const DANGER = '#f43f5e';

export function PhoneVoiceControl({ rtcBase }: { rtcBase: string }) {
  const voice = usePhoneVoice(rtcBase);
  const { status, error, muted } = voice;
  const live = status === 'live';
  const connecting = status === 'connecting';

  return (
    // Overlay: transparent to clicks except on the actual controls.
    <div className="fixed inset-0 z-30 pointer-events-none select-none">
      {/* Jarvis's voice. Hidden; playback is unlocked by the start tap. */}
      <audio ref={voice.audioRef} autoPlay playsInline className="hidden" />

      {/* Live: minimal control bar, bottom-center. */}
      {live ? (
        <div className="absolute bottom-7 left-1/2 -translate-x-1/2 flex items-center gap-3 pointer-events-auto">
          <button
            onClick={voice.toggleMute}
            className="flex items-center gap-2 px-4 h-12 rounded-full text-[13px] font-medium transition active:scale-95"
            style={{
              background: muted ? 'rgba(244,63,94,0.16)' : 'rgba(255,255,255,0.06)',
              border: `1px solid ${muted ? DANGER : ACCENT}55`,
              color: muted ? DANGER : ACCENT,
              letterSpacing: '0.06em',
              fontFamily: 'ui-monospace, monospace',
              backdropFilter: 'blur(8px)',
            }}
            title={muted ? 'Unmute microphone' : 'Mute microphone'}
          >
            {muted ? <MicOff size={18} /> : <Mic size={18} />}
            {muted ? 'MUTED' : 'MIC ON'}
          </button>
          <button
            onClick={voice.stop}
            className="flex items-center justify-center w-12 h-12 rounded-full transition active:scale-95"
            style={{
              background: 'rgba(244,63,94,0.16)',
              border: `1px solid ${DANGER}66`,
              color: DANGER,
              backdropFilter: 'blur(8px)',
            }}
            title="End call"
          >
            <PhoneOff size={20} />
          </button>
        </div>
      ) : (
        /* Idle / connecting / ended / error: one big call-to-action, lifted
           clear of the stage's subtitle line. */
        <div className="absolute bottom-[17vh] left-1/2 -translate-x-1/2 flex flex-col items-center gap-3 pointer-events-auto">
          {error && (
            <div
              className="text-[12px] px-3 py-1 rounded-md"
              style={{
                color: DANGER,
                background: 'rgba(244,63,94,0.1)',
                border: `1px solid ${DANGER}40`,
                fontFamily: 'ui-monospace, monospace',
                letterSpacing: '0.04em',
              }}
            >
              {error}
            </div>
          )}
          <button
            onClick={voice.start}
            disabled={connecting}
            className="flex items-center gap-3 pl-5 pr-6 h-16 rounded-full font-semibold transition active:scale-95 disabled:opacity-80"
            style={{
              background: `linear-gradient(135deg, ${ACCENT}26, ${ACCENT}10)`,
              border: `1px solid ${ACCENT}66`,
              color: ACCENT,
              fontSize: '17px',
              letterSpacing: '0.04em',
              boxShadow: `0 0 28px ${ACCENT}33, inset 0 0 18px ${ACCENT}14`,
              backdropFilter: 'blur(8px)',
            }}
          >
            {connecting ? (
              <>
                <Loader2 size={22} className="animate-spin" />
                Connecting…
              </>
            ) : (
              <>
                <span
                  className="flex items-center justify-center w-9 h-9 rounded-full"
                  style={{ background: `${ACCENT}22` }}
                >
                  {status === 'ended' || status === 'error' ? (
                    <AudioLines size={20} />
                  ) : (
                    <Mic size={20} />
                  )}
                </span>
                {status === 'ended' || status === 'error' ? 'Talk again' : 'Tap to talk'}
              </>
            )}
          </button>
        </div>
      )}
    </div>
  );
}
