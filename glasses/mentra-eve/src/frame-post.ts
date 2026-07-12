/**
 * Photo bytes -> base64 -> POST to EVE's vision-frame endpoint.
 *
 * Contract (EVE side):
 *   POST ${EVE_API_URL}/v1/vision/frame
 *   Authorization: Bearer <EVE_APP_TOKEN>
 *   { "request_id": "<hex>", "jpeg_b64": "<base64>" }   (8MB cap on the raw JPEG)
 *
 * The payload builder is a pure function (cap enforced there); the POST wraps it
 * with bounded retries. `fetch` is injected so tests never hit the network.
 */

import type { BridgeConfig } from "./config.js";
import type { Logger } from "./logger.js";
import { computeBackoff } from "./eve-link.js";

export interface FramePayload {
  request_id: string;
  jpeg_b64: string;
}

/** Narrow fetch shape we actually use — lets tests pass a stub. */
export type FetchLike = (
  url: string,
  init: {
    method: string;
    headers: Record<string, string>;
    body: string;
  },
) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>;

export class FrameTooLargeError extends Error {
  constructor(size: number, cap: number) {
    super(`frame is ${size} bytes, exceeds ${cap} byte cap`);
    this.name = "FrameTooLargeError";
  }
}

/**
 * Build the JSON body EVE expects. Enforces the byte cap on the *raw* JPEG
 * (before base64 expansion), matching the documented contract.
 */
export function buildFramePayload(
  requestId: string,
  jpeg: Uint8Array,
  maxBytes: number,
): FramePayload {
  if (jpeg.byteLength > maxBytes) {
    throw new FrameTooLargeError(jpeg.byteLength, maxBytes);
  }
  return {
    request_id: requestId,
    jpeg_b64: Buffer.from(jpeg).toString("base64"),
  };
}

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

/**
 * POST a captured frame to EVE. Retries transient failures (network errors and
 * 5xx) up to `config.framePostRetries` times with exponential backoff. Does NOT
 * retry a 4xx (a client/auth error will not fix itself). Throws on final
 * failure so the caller can log it against the request id.
 */
export async function postFrame(
  config: BridgeConfig,
  requestId: string,
  jpeg: Uint8Array,
  deps: { fetch: FetchLike; logger: Logger },
): Promise<void> {
  const payload = buildFramePayload(requestId, jpeg, config.maxFrameBytes);
  const url = `${config.eveApiUrl}/v1/vision/frame`;
  const body = JSON.stringify(payload);
  const headers = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${config.eveAppToken}`,
  };

  let lastErr: unknown;
  const maxAttempts = Math.max(1, config.framePostRetries);

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await deps.fetch(url, { method: "POST", headers, body });
      if (res.ok) {
        deps.logger.info({ requestId, bytes: jpeg.byteLength }, "frame-post: delivered");
        return;
      }
      if (res.status >= 400 && res.status < 500) {
        const text = await res.text().catch(() => "");
        throw new Error(`frame-post: non-retryable ${res.status}: ${text.slice(0, 200)}`);
      }
      lastErr = new Error(`frame-post: server ${res.status}`);
    } catch (err) {
      // A 4xx above is rethrown as a plain Error and must not be retried.
      if (err instanceof Error && err.message.startsWith("frame-post: non-retryable")) {
        throw err;
      }
      lastErr = err;
    }

    if (attempt < maxAttempts) {
      const delay = computeBackoff(
        attempt,
        config.framePostRetryBaseMs,
        config.framePostRetryBaseMs * 20,
        2,
      );
      deps.logger.warn(
        { requestId, attempt, nextDelayMs: delay, err: String(lastErr) },
        "frame-post: retrying",
      );
      await sleep(delay);
    }
  }

  deps.logger.error({ requestId, err: String(lastErr) }, "frame-post: gave up");
  throw lastErr instanceof Error ? lastErr : new Error(String(lastErr));
}
