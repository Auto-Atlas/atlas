import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  computeBackoff,
  shouldHandleCapture,
  dispatchEveMessage,
  EveLink,
  type EveLinkHandlers,
  type WsLike,
} from "../src/eve-link.js";
import { mockLogger, FakeWs } from "./helpers.js";

describe("computeBackoff", () => {
  it("grows exponentially and clamps to max", () => {
    expect(computeBackoff(1, 500, 30000, 2)).toBe(500);
    expect(computeBackoff(2, 500, 30000, 2)).toBe(1000);
    expect(computeBackoff(3, 500, 30000, 2)).toBe(2000);
    expect(computeBackoff(99, 500, 30000, 2)).toBe(30000);
  });
});

describe("shouldHandleCapture", () => {
  it("acts only on any/glasses", () => {
    expect(shouldHandleCapture("any")).toBe(true);
    expect(shouldHandleCapture("glasses")).toBe(true);
    expect(shouldHandleCapture("phone")).toBe(false);
    expect(shouldHandleCapture(undefined)).toBe(false);
  });
});

describe("dispatchEveMessage", () => {
  let handlers: EveLinkHandlers;
  beforeEach(() => {
    handlers = { onCaptureFrame: vi.fn(), onSurfaceVisual: vi.fn() };
  });

  it("routes capture_frame", () => {
    const t = dispatchEveMessage(
      JSON.stringify({ type: "capture_frame", request_id: "ab12", source: "any" }),
      handlers,
      mockLogger(),
    );
    expect(t).toBe("capture_frame");
    expect(handlers.onCaptureFrame).toHaveBeenCalledOnce();
  });

  it("routes surface_visual", () => {
    const t = dispatchEveMessage(
      JSON.stringify({ type: "surface_visual", kind: "note", text: "hi" }),
      handlers,
      mockLogger(),
    );
    expect(t).toBe("surface_visual");
    expect(handlers.onSurfaceVisual).toHaveBeenCalledOnce();
  });

  it("accepts Buffer payloads", () => {
    const t = dispatchEveMessage(
      Buffer.from(JSON.stringify({ type: "capture_frame", request_id: "x", source: "glasses" })),
      handlers,
      mockLogger(),
    );
    expect(t).toBe("capture_frame");
  });

  it("drops non-JSON and unknown types", () => {
    expect(dispatchEveMessage("not json", handlers, mockLogger())).toBeNull();
    expect(dispatchEveMessage(JSON.stringify({ type: "nope" }), handlers, mockLogger())).toBeNull();
  });
});

describe("EveLink reconnect/backoff", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("reconnects with backoff after a close", () => {
    const sockets: FakeWs[] = [];
    const factory = vi.fn((_url: string, _protocols: string[]): WsLike => {
      const ws = new FakeWs();
      sockets.push(ws);
      return ws;
    });

    const link = new EveLink(
      { wsUrl: "ws://host/v1/stream", token: "tok", reconnectBaseMs: 500, reconnectMaxMs: 30000, reconnectFactor: 2 },
      { onCaptureFrame: vi.fn(), onSurfaceVisual: vi.fn() },
      factory,
      mockLogger(),
    );

    link.start();
    expect(factory).toHaveBeenCalledTimes(1);
    // surface=glasses must be appended.
    expect(factory.mock.calls[0][0]).toContain("surface=glasses");
    // subprotocol auth: ["bearer", token]
    expect(factory.mock.calls[0][1]).toEqual(["bearer", "tok"]);

    // First socket closes -> schedule reconnect at base delay (500ms).
    sockets[0].emit("close", 1006);
    expect(factory).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(499);
    expect(factory).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1);
    expect(factory).toHaveBeenCalledTimes(2);

    // Second close -> next backoff is 1000ms (attempt 2).
    sockets[1].emit("close", 1006);
    vi.advanceTimersByTime(999);
    expect(factory).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(1);
    expect(factory).toHaveBeenCalledTimes(3);

    // A successful open resets the backoff counter.
    sockets[2].emit("open");
    sockets[2].emit("close", 1006);
    vi.advanceTimersByTime(500);
    expect(factory).toHaveBeenCalledTimes(4);

    link.stop();
  });

  it("stop() prevents further reconnects", () => {
    const factory = vi.fn((): WsLike => new FakeWs());
    const link = new EveLink(
      { wsUrl: "ws://host", token: "t", reconnectBaseMs: 100, reconnectMaxMs: 1000, reconnectFactor: 2 },
      { onCaptureFrame: vi.fn(), onSurfaceVisual: vi.fn() },
      factory,
      mockLogger(),
    );
    link.start();
    link.stop();
    vi.advanceTimersByTime(5000);
    expect(factory).toHaveBeenCalledTimes(1);
  });
});
