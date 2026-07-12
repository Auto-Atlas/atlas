/**
 * Centralised, env-driven configuration for the EVE <-> Mentra Live bridge.
 *
 * PRODUCT RULE: nothing is hardcoded. Every host, token, port and tuning knob
 * comes from the environment and is validated at startup. Missing required
 * values fail fast with an explicit message so a misconfigured deploy never
 * silently half-works.
 */

export interface BridgeConfig {
  // --- EVE hub contract ---
  /** EVE REST base, e.g. http://localhost:8799 (no trailing slash). */
  eveApiUrl: string;
  /** WebSocket URL for the EVE stream, e.g. ws://localhost:8799/v1/stream. */
  eveWsUrl: string;
  /** Shared bearer token used for EVE REST + WS auth and inbound /narrate auth. */
  eveAppToken: string;

  // --- MentraOS app identity ---
  /** MentraOS Cloud API key (from console.mentra.glass). */
  mentraosApiKey: string;
  /** App package name, must match the MentraOS console registration. */
  packageName: string;
  /** Port this app's HTTP server (webhook + /narrate) listens on. */
  port: number;

  // --- Reconnect / backoff knobs (EVE WS link) ---
  reconnectBaseMs: number;
  reconnectMaxMs: number;
  reconnectFactor: number;

  // --- Frame POST retry knobs ---
  framePostRetries: number;
  framePostRetryBaseMs: number;
  /** Hard cap on the JPEG payload we will POST to EVE (bytes). */
  maxFrameBytes: number;
}

/** Thrown when required configuration is missing or malformed. */
export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConfigError";
  }
}

type Env = Record<string, string | undefined>;

function requireStr(env: Env, key: string, missing: string[]): string {
  const v = env[key];
  if (v === undefined || v.trim() === "") {
    missing.push(key);
    return "";
  }
  return v.trim();
}

function optInt(env: Env, key: string, fallback: number): number {
  const raw = env[key];
  if (raw === undefined || raw.trim() === "") return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n)) {
    throw new ConfigError(`${key} must be a number, got "${raw}"`);
  }
  return n;
}

/**
 * Derive the WS stream URL from the REST base if EVE_WS_URL is not set.
 * http -> ws, https -> wss, appending /v1/stream.
 */
export function deriveWsUrl(eveApiUrl: string): string {
  const base = eveApiUrl.replace(/\/+$/, "");
  const swapped = base.replace(/^http(s?):\/\//i, (_m, s) => `ws${s ? "s" : ""}://`);
  return `${swapped}/v1/stream`;
}

/**
 * Load and validate configuration. Pure: takes the env map explicitly so it is
 * trivially testable and never reaches for process.env implicitly.
 */
export function loadConfig(env: Env = process.env): BridgeConfig {
  const missing: string[] = [];

  const eveApiUrl = requireStr(env, "EVE_API_URL", missing).replace(/\/+$/, "");
  const eveAppToken = requireStr(env, "EVE_APP_TOKEN", missing);
  const mentraosApiKey = requireStr(env, "MENTRAOS_API_KEY", missing);
  const packageName = requireStr(env, "PACKAGE_NAME", missing);

  if (missing.length > 0) {
    throw new ConfigError(
      `Missing required environment variables: ${missing.join(", ")}. ` +
        `See README.md / .env.example for the full list.`,
    );
  }

  const eveWsUrl =
    env.EVE_WS_URL && env.EVE_WS_URL.trim() !== ""
      ? env.EVE_WS_URL.trim()
      : deriveWsUrl(eveApiUrl);

  return {
    eveApiUrl,
    eveWsUrl,
    eveAppToken,
    mentraosApiKey,
    packageName,
    port: optInt(env, "PORT", 7010),
    reconnectBaseMs: optInt(env, "EVE_RECONNECT_BASE_MS", 500),
    reconnectMaxMs: optInt(env, "EVE_RECONNECT_MAX_MS", 30_000),
    reconnectFactor: optInt(env, "EVE_RECONNECT_FACTOR", 2),
    framePostRetries: optInt(env, "EVE_FRAME_POST_RETRIES", 3),
    framePostRetryBaseMs: optInt(env, "EVE_FRAME_POST_RETRY_MS", 500),
    maxFrameBytes: optInt(env, "EVE_MAX_FRAME_BYTES", 8 * 1024 * 1024),
  };
}
