/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL: string;
  /** Optional override for the Jarvis metrics-bridge WebSocket URL (phone build). */
  readonly VITE_JARVIS_WS?: string;
  /** Optional override for the WebRTC signaling base URL (phone build). */
  readonly VITE_JARVIS_RTC?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
