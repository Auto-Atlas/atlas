import { describe, it, expect, vi } from "vitest";

// The bridge extends @mentra/sdk's AppServer. We only test the pure capture
// handler here, so mock the SDK to a trivial base class — no cloud, no sockets.
vi.mock("@mentra/sdk", () => ({
  AppServer: class {
    logger = { debug() {}, info() {}, warn() {}, error() {} };
    getExpressApp() {
      return { post() {} };
    }
    addCleanupHandler() {}
    start() {
      return Promise.resolve();
    }
  },
}));

import { makeCaptureFrameHandler, type CaptureSession } from "../src/index.js";
import type { BridgeConfig } from "../src/config.js";
import type { FetchLike } from "../src/frame-post.js";
import { mockLogger } from "./helpers.js";

const config: BridgeConfig = {
  eveApiUrl: "http://localhost:8799",
  eveWsUrl: "ws://localhost:8799/v1/stream",
  eveAppToken: "tok",
  mentraosApiKey: "mk",
  packageName: "com.eve.mentra",
  port: 7010,
  reconnectBaseMs: 500,
  reconnectMaxMs: 30000,
  reconnectFactor: 2,
  framePostRetries: 3,
  framePostRetryBaseMs: 1,
  maxFrameBytes: 8 * 1024 * 1024,
};

function makeSession(buffer: Uint8Array): CaptureSession & { camera: { requestPhoto: ReturnType<typeof vi.fn> } } {
  return {
    camera: {
      requestPhoto: vi.fn(async () => ({ buffer })),
    },
  };
}

describe("makeCaptureFrameHandler", () => {
  it("capture_frame(source=glasses) -> photo taken -> posted with same request_id and bytes", async () => {
    const buffer = new Uint8Array([10, 20, 30]);
    const session = makeSession(buffer);
    const postFrameSpy = vi.fn(async () => {});
    const handler = makeCaptureFrameHandler({
      config,
      getSession: () => session,
      postFrame: postFrameSpy,
      fetch: (async () => ({ ok: true, status: 200, text: async () => "" })) as unknown as FetchLike,
      logger: mockLogger(),
    });

    await handler({ type: "capture_frame", request_id: "hex123", source: "glasses", prompt: "what is this" });

    expect(session.camera.requestPhoto).toHaveBeenCalledOnce();
    expect(postFrameSpy).toHaveBeenCalledOnce();
    const [cfg, requestId, jpeg] = postFrameSpy.mock.calls[0];
    expect(cfg).toBe(config);
    expect(requestId).toBe("hex123");
    expect(jpeg).toBe(buffer);
  });

  it("acts on source=any", async () => {
    const session = makeSession(new Uint8Array([1]));
    const postFrameSpy = vi.fn(async () => {});
    const handler = makeCaptureFrameHandler({
      config,
      getSession: () => session,
      postFrame: postFrameSpy,
      fetch: (async () => ({})) as unknown as FetchLike,
      logger: mockLogger(),
    });
    await handler({ type: "capture_frame", request_id: "r", source: "any" });
    expect(session.camera.requestPhoto).toHaveBeenCalledOnce();
    expect(postFrameSpy).toHaveBeenCalledOnce();
  });

  it("IGNORES source=phone: no photo, no post", async () => {
    const session = makeSession(new Uint8Array([1]));
    const postFrameSpy = vi.fn(async () => {});
    const handler = makeCaptureFrameHandler({
      config,
      getSession: () => session,
      postFrame: postFrameSpy,
      fetch: (async () => ({})) as unknown as FetchLike,
      logger: mockLogger(),
    });
    await handler({ type: "capture_frame", request_id: "r", source: "phone" });
    expect(session.camera.requestPhoto).not.toHaveBeenCalled();
    expect(postFrameSpy).not.toHaveBeenCalled();
  });

  it("no active session -> no throw, no post", async () => {
    const postFrameSpy = vi.fn(async () => {});
    const handler = makeCaptureFrameHandler({
      config,
      getSession: () => null,
      postFrame: postFrameSpy,
      fetch: (async () => ({})) as unknown as FetchLike,
      logger: mockLogger(),
    });
    await handler({ type: "capture_frame", request_id: "r", source: "glasses" });
    expect(postFrameSpy).not.toHaveBeenCalled();
  });
});
