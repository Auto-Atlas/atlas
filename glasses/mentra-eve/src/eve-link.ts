/**
 * EVE hub WebSocket client.
 *
 * Connects to `${EVE_WS_URL}?surface=glasses` with subprotocol auth
 * (`Sec-WebSocket-Protocol: bearer, <token>`), auto-reconnects with
 * exponential backoff, and dispatches inbound events to handlers.
 *
 * The wire parsing (`dispatchEveMessage`) and backoff math (`computeBackoff`)
 * are pure exported functions so they can be unit-tested without any sockets.
 * The socket itself is created through an injectable factory for the same
 * reason.
 */

import type { Logger } from "./logger.js";

// ---------------------------------------------------------------------------
// Inbound event contract (defined by the EVE side; we only consume it).
// ---------------------------------------------------------------------------

export type CaptureSource = "any" | "phone" | "glasses";

export interface CaptureFrameEvent {
  type: "capture_frame";
  request_id: string;
  prompt?: string;
  source: CaptureSource;
}

export interface SurfaceVisualEvent {
  type: "surface_visual";
  kind?: string;
  title?: string;
  visual_id?: string;
  url?: string;
  text?: string;
}

export type EveInboundEvent = CaptureFrameEvent | SurfaceVisualEvent;

export interface EveLinkHandlers {
  onCaptureFrame: (evt: CaptureFrameEvent) => void | Promise<void>;
  onSurfaceVisual: (evt: SurfaceVisualEvent) => void | Promise<void>;
}

// ---------------------------------------------------------------------------
// Minimal socket abstraction so we can inject a mock in tests.
// ---------------------------------------------------------------------------

export interface WsLike {
  on(event: "open", cb: () => void): void;
  on(event: "message", cb: (data: unknown) => void): void;
  on(event: "close", cb: (code?: number, reason?: unknown) => void): void;
  on(event: "error", cb: (err: unknown) => void): void;
  close(): void;
}

/** Creates a socket for `url` with the given subprotocols. */
export type WsFactory = (url: string, protocols: string[]) => WsLike;

// ---------------------------------------------------------------------------
// Pure helpers.
// ---------------------------------------------------------------------------

/**
 * Exponential backoff with a ceiling. `attempt` is 1-based (first retry = 1).
 */
export function computeBackoff(
  attempt: number,
  baseMs: number,
  maxMs: number,
  factor: number,
): number {
  const a = Math.max(1, Math.floor(attempt));
  const delay = baseMs * Math.pow(factor, a - 1);
  return Math.min(delay, maxMs);
}

/** True if this bridge should act on a capture with the given source. */
export function shouldHandleCapture(source: CaptureSource | string | undefined): boolean {
  return source === "any" || source === "glasses";
}

/**
 * Parse a raw WS payload and route it to the right handler. Returns the parsed
 * event type it dispatched (or null if it was ignored / unparseable), which
 * makes it easy to assert on in tests.
 */
export function dispatchEveMessage(
  raw: unknown,
  handlers: EveLinkHandlers,
  logger: Logger,
): string | null {
  let text: string;
  if (typeof raw === "string") {
    text = raw;
  } else if (raw instanceof Uint8Array || Buffer.isBuffer(raw)) {
    text = Buffer.from(raw as Uint8Array).toString("utf8");
  } else {
    text = String(raw);
  }

  let msg: unknown;
  try {
    msg = JSON.parse(text);
  } catch {
    logger.warn({ text: text.slice(0, 200) }, "eve-link: dropped non-JSON message");
    return null;
  }

  if (typeof msg !== "object" || msg === null || !("type" in msg)) {
    logger.warn("eve-link: message without a type field");
    return null;
  }

  const type = (msg as { type: unknown }).type;
  switch (type) {
    case "capture_frame":
      void handlers.onCaptureFrame(msg as CaptureFrameEvent);
      return "capture_frame";
    case "surface_visual":
      void handlers.onSurfaceVisual(msg as SurfaceVisualEvent);
      return "surface_visual";
    default:
      logger.debug({ type }, "eve-link: unhandled event type");
      return null;
  }
}

// ---------------------------------------------------------------------------
// The link itself.
// ---------------------------------------------------------------------------

export interface EveLinkOptions {
  wsUrl: string;
  token: string;
  reconnectBaseMs: number;
  reconnectMaxMs: number;
  reconnectFactor: number;
}

export class EveLink {
  private ws: WsLike | null = null;
  private reconnectAttempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;

  constructor(
    private readonly opts: EveLinkOptions,
    private readonly handlers: EveLinkHandlers,
    private readonly factory: WsFactory,
    private readonly logger: Logger,
  ) {}

  /** Full connect URL including the required surface query param. */
  get url(): string {
    const sep = this.opts.wsUrl.includes("?") ? "&" : "?";
    return `${this.opts.wsUrl}${sep}surface=glasses`;
  }

  start(): void {
    this.stopped = false;
    this.connect();
  }

  private connect(): void {
    if (this.stopped) return;
    // Subprotocol auth: the WS handshake sends
    //   Sec-WebSocket-Protocol: bearer, <token>
    const protocols = ["bearer", this.opts.token];
    this.logger.info({ url: this.opts.wsUrl }, "eve-link: connecting");

    let ws: WsLike;
    try {
      ws = this.factory(this.url, protocols);
    } catch (err) {
      this.logger.error({ err }, "eve-link: socket creation failed");
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;

    ws.on("open", () => {
      this.reconnectAttempt = 0;
      this.logger.info("eve-link: connected");
    });
    ws.on("message", (data: unknown) => {
      dispatchEveMessage(data, this.handlers, this.logger);
    });
    ws.on("close", (code?: number) => {
      this.logger.warn({ code }, "eve-link: closed");
      this.ws = null;
      this.scheduleReconnect();
    });
    ws.on("error", (err: unknown) => {
      this.logger.error({ err }, "eve-link: socket error");
      // 'close' typically follows; guard against sockets that only emit error.
    });
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.timer) return;
    this.reconnectAttempt += 1;
    const delay = computeBackoff(
      this.reconnectAttempt,
      this.opts.reconnectBaseMs,
      this.opts.reconnectMaxMs,
      this.opts.reconnectFactor,
    );
    this.logger.info(
      { attempt: this.reconnectAttempt, delayMs: delay },
      "eve-link: scheduling reconnect",
    );
    this.timer = setTimeout(() => {
      this.timer = null;
      this.connect();
    }, delay);
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.ws) {
      try {
        this.ws.close();
      } catch {
        /* ignore */
      }
      this.ws = null;
    }
  }
}
