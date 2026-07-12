// Browser side of the phone voice call.
//
// Establishes the SAME WebRTC session the pipecat playground does, but in our
// own page — so one screen both talks to Jarvis and shows the avatar. The
// handshake mirrors scripts/test_phone_client.py against phone_bot.py's
// SmallWebRTCTransport (pipecat runner protocol):
//
//   1. POST {rtcBase}/start  -> { sessionId, iceConfig }
//   2. getUserMedia(mic), add the track to an RTCPeerConnection
//   3. createOffer + setLocalDescription, wait for ICE gathering to finish
//      (the server expects a complete, non-trickle offer)
//   4. POST {rtcBase}/sessions/{sessionId}/api/offer  -> { sdp, type } answer
//   5. setRemoteDescription; the bot's TTS arrives on pc.ontrack and plays out
//
// Media (RTP) flows peer-to-peer over ICE (Tailscale / LAN host candidates),
// NOT through the gateway — the gateway only carries signaling. The avatar's
// live state comes separately from the metrics bridge (:8766) over /ws, so this
// hook is purely about getting audio in and out; it never touches React-heavy
// state on the hot path.
import { useCallback, useEffect, useRef, useState } from 'react';

export type VoiceStatus = 'idle' | 'connecting' | 'live' | 'ended' | 'error';

export interface PhoneVoiceHandle {
  status: VoiceStatus;
  error: string | null;
  muted: boolean;
  /** Attach to the <audio> element that plays Jarvis's voice. */
  audioRef: React.RefObject<HTMLAudioElement | null>;
  start: () => void;
  stop: () => void;
  toggleMute: () => void;
}

// Cap the ICE-gathering wait so a stray candidate never hangs the tap-to-start.
const ICE_GATHER_TIMEOUT_MS = 4000;

function waitForIceGathering(pc: RTCPeerConnection): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      pc.removeEventListener('icegatheringstatechange', check);
      clearTimeout(timer);
      resolve();
    };
    const check = () => {
      if (pc.iceGatheringState === 'complete') done();
    };
    const timer = setTimeout(done, ICE_GATHER_TIMEOUT_MS);
    pc.addEventListener('icegatheringstatechange', check);
  });
}

export function usePhoneVoice(rtcBase: string): PhoneVoiceHandle {
  const [status, setStatus] = useState<VoiceStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [muted, setMuted] = useState(false);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const pcRef = useRef<RTCPeerConnection | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  // Guards stale async work after a teardown (React strict-mode double-mount,
  // or the user ending the call mid-handshake).
  const epochRef = useRef(0);

  const teardown = useCallback(() => {
    epochRef.current += 1;
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    const pc = pcRef.current;
    if (pc) {
      pc.ontrack = null;
      pc.onconnectionstatechange = null;
      try {
        pc.close();
      } catch {
        /* already closed */
      }
      pcRef.current = null;
    }
    if (audioRef.current) audioRef.current.srcObject = null;
  }, []);

  const start = useCallback(async () => {
    teardown();
    const epoch = epochRef.current;
    setError(null);
    setStatus('connecting');
    setMuted(false);
    try {
      // 1. Register a session with the runner.
      const startResp = await fetch(`${rtcBase}/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ transport: 'webrtc', enableDefaultIceServers: true }),
      });
      if (!startResp.ok) throw new Error(`start failed (${startResp.status})`);
      const { sessionId, iceConfig } = (await startResp.json()) as {
        sessionId: string;
        iceConfig?: { iceServers?: RTCIceServer[] };
      };
      if (epoch !== epochRef.current) return;

      // 2. Peer connection using the ICE servers the server handed us.
      const pc = new RTCPeerConnection({
        iceServers: iceConfig?.iceServers ?? [{ urls: 'stun:stun.l.google.com:19302' }],
      });
      pcRef.current = pc;

      pc.ontrack = (e) => {
        const el = audioRef.current;
        if (el && e.streams[0]) {
          el.srcObject = e.streams[0];
          // The tap that called start() is the user gesture that unblocks this.
          void el.play().catch(() => {});
        }
      };
      pc.onconnectionstatechange = () => {
        if (epoch !== epochRef.current) return;
        const st = pc.connectionState;
        if (st === 'connected') setStatus('live');
        else if (st === 'failed') {
          setError('connection failed');
          setStatus('error');
        } else if (st === 'closed') {
          setStatus((prev) => (prev === 'ended' ? 'ended' : 'idle'));
        }
      };

      // 3. Mic capture (secure-context only — fine under HTTPS/Tailscale).
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      if (epoch !== epochRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      streamRef.current = stream;
      stream.getTracks().forEach((t) => pc.addTrack(t, stream));

      // 4. Offer + complete ICE gathering (server wants a non-trickle offer).
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await waitForIceGathering(pc);
      if (epoch !== epochRef.current) return;

      // 5. Exchange SDP and connect.
      const offerResp = await fetch(`${rtcBase}/sessions/${sessionId}/api/offer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sdp: pc.localDescription?.sdp,
          type: pc.localDescription?.type,
        }),
      });
      if (!offerResp.ok) throw new Error(`offer failed (${offerResp.status})`);
      const answer = (await offerResp.json()) as RTCSessionDescriptionInit;
      if (epoch !== epochRef.current) return;
      await pc.setRemoteDescription(answer);
      // status flips to 'live' when the connection state reaches 'connected'.
    } catch (e) {
      if (epoch !== epochRef.current) return;
      const msg =
        e instanceof DOMException && e.name === 'NotAllowedError'
          ? 'microphone permission denied'
          : e instanceof Error
            ? e.message
            : 'could not connect';
      setError(msg);
      setStatus('error');
      teardown();
    }
  }, [rtcBase, teardown]);

  const stop = useCallback(() => {
    teardown();
    setStatus('ended');
    setMuted(false);
  }, [teardown]);

  const toggleMute = useCallback(() => {
    const stream = streamRef.current;
    if (!stream) return;
    setMuted((prev) => {
      const next = !prev;
      stream.getAudioTracks().forEach((t) => (t.enabled = !next));
      return next;
    });
  }, []);

  // Always release the mic + peer connection on unmount.
  useEffect(() => () => teardown(), [teardown]);

  return { status, error, muted, audioRef, start, stop, toggleMute };
}
