import { describe, it, expect, vi } from "vitest";
import {
  buildFramePayload,
  postFrame,
  FrameTooLargeError,
  type FetchLike,
} from "../src/frame-post.js";
import type { BridgeConfig } from "../src/config.js";
import { mockLogger } from "./helpers.js";

const config: BridgeConfig = {
  eveApiUrl: "http://localhost:8799",
  eveWsUrl: "ws://localhost:8799/v1/stream",
  eveAppToken: "tok_secret",
  mentraosApiKey: "mk",
  packageName: "com.eve.mentra",
  port: 7010,
  reconnectBaseMs: 500,
  reconnectMaxMs: 30000,
  reconnectFactor: 2,
  framePostRetries: 3,
  framePostRetryBaseMs: 1,
  maxFrameBytes: 16,
};

describe("buildFramePayload", () => {
  it("base64-encodes and enforces the byte cap", () => {
    const jpeg = new Uint8Array([1, 2, 3, 4]);
    const p = buildFramePayload("abcd", jpeg, 16);
    expect(p.request_id).toBe("abcd");
    expect(p.jpeg_b64).toBe(Buffer.from(jpeg).toString("base64"));
  });

  it("throws FrameTooLargeError over the cap", () => {
    const big = new Uint8Array(17);
    expect(() => buildFramePayload("x", big, 16)).toThrow(FrameTooLargeError);
  });
});

describe("postFrame", () => {
  it("POSTs correct url, auth header and body, then returns on 200", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, text: async () => "" }));
    const jpeg = new Uint8Array([9, 8, 7]);
    await postFrame(config, "req_hex_1", jpeg, {
      fetch: fetchMock as unknown as FetchLike,
      logger: mockLogger(),
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, { method: string; headers: Record<string, string>; body: string }];
    expect(url).toBe("http://localhost:8799/v1/vision/frame");
    expect(init.method).toBe("POST");
    expect(init.headers.Authorization).toBe("Bearer tok_secret");
    expect(init.headers["Content-Type"]).toBe("application/json");
    const body = JSON.parse(init.body);
    expect(body).toEqual({ request_id: "req_hex_1", jpeg_b64: Buffer.from(jpeg).toString("base64") });
  });

  it("retries on 5xx then succeeds", async () => {
    let n = 0;
    const fetchMock = vi.fn(async () => {
      n++;
      return n < 2 ? { ok: false, status: 503, text: async () => "" } : { ok: true, status: 200, text: async () => "" };
    });
    await postFrame(config, "r", new Uint8Array([1]), {
      fetch: fetchMock as unknown as FetchLike,
      logger: mockLogger(),
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("does NOT retry a 4xx and throws", async () => {
    const fetchMock = vi.fn(async () => ({ ok: false, status: 401, text: async () => "bad token" }));
    await expect(
      postFrame(config, "r", new Uint8Array([1]), {
        fetch: fetchMock as unknown as FetchLike,
        logger: mockLogger(),
      }),
    ).rejects.toThrow(/non-retryable 401/);
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});
