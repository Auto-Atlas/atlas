// Resolve the metrics-bridge WebSocket URL for the live Jarvis avatar.
//
// The desktop app connects to the loopback bridge directly (ws://127.0.0.1:8765),
// but the phone-viewable build is served over Tailscale TLS — so it must reach
// the bridge as a *secure* socket (wss://), because an HTTPS page cannot open an
// insecure ws:// connection (browsers block it as mixed content).
//
// `tailscale serve` terminates TLS on the tailnet host and proxies a path on the
// same origin (https://<host>:<port>/ws) down to the loopback bridge, so the page
// just connects to wss://<same-host>/ws. The Python bridge's WS handler ignores
// the request path, so the proxied upgrade lands fine regardless of the /ws prefix.
//
// Resolution order:
//   1. VITE_JARVIS_WS build-time override, if set (explicit wins).
//   2. Served over HTTPS  -> wss://<host>/ws        (Tailscale serve path proxy).
//   3. Served over HTTP from another device -> ws://<host>:8766/ws (trusted LAN).
//   4. Running on the PC itself (dev/preview) -> ws://127.0.0.1:8766 (phone bridge).
//
// Note 2-4 default to the PHONE voice bridge (:8766) so the avatar animates to a
// phone session, not the desktop mic. The desktop /stage route does NOT use this
// helper — it keeps its built-in ws://127.0.0.1:8765 default.

// Phone voice loop's metrics bridge (see phone_bot.py: JARVIS_PHONE_WS_PORT).
const PHONE_WS_PORT = 8766;

export function resolveJarvisWsUrl(): string {
  const explicit = import.meta.env.VITE_JARVIS_WS as string | undefined;
  if (explicit && explicit.trim()) return explicit.trim();

  if (typeof window !== 'undefined' && window.location) {
    const { protocol, host, hostname } = window.location;
    // TLS origin (tailscale serve): reuse the same host:port, secure socket.
    if (protocol === 'https:') return `wss://${host}/ws`;
    // Plain HTTP from a phone/other box on the LAN: hit the bridge directly.
    if (hostname && hostname !== 'localhost' && hostname !== '127.0.0.1') {
      return `ws://${hostname}:${PHONE_WS_PORT}/ws`;
    }
  }
  // Dev / preview on the PC itself.
  return `ws://127.0.0.1:${PHONE_WS_PORT}`;
}

// Phone voice loop's WebRTC signaling server (phone_bot.py / pipecat
// SmallWebRTCTransport on 127.0.0.1:8788). The page POSTs /start then
// /sessions/{id}/api/offer here to set up the call.
const PHONE_RTC_PORT = 8788;

/**
 * Base URL for the WebRTC signaling endpoints (no trailing slash).
 *
 * Over TLS (tailscale serve) the page and the signaling share one origin —
 * phone_gateway.py reverse-proxies /start and /sessions/* to :8788 — so we
 * return '' and fetch same-origin relative paths (no CORS preflight, no mixed
 * content). Off-box over plain HTTP we hit the signaling server directly.
 */
export function resolveRtcBase(): string {
  const explicit = import.meta.env.VITE_JARVIS_RTC as string | undefined;
  if (explicit && explicit.trim()) return explicit.trim().replace(/\/$/, '');

  if (typeof window !== 'undefined' && window.location) {
    const { protocol, hostname } = window.location;
    if (protocol === 'https:') return ''; // same-origin via the gateway proxy
    if (hostname && hostname !== 'localhost' && hostname !== '127.0.0.1') {
      return `http://${hostname}:${PHONE_RTC_PORT}`;
    }
  }
  return `http://127.0.0.1:${PHONE_RTC_PORT}`;
}
