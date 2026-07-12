import { vi } from "vitest";
import type { Logger } from "../src/logger.js";
import type { WsLike } from "../src/eve-link.js";

/** A no-op logger that records nothing but satisfies the interface. */
export function mockLogger(): Logger {
  return {
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  } as unknown as Logger;
}

/** A controllable fake WebSocket implementing WsLike. */
export class FakeWs implements WsLike {
  handlers: Record<string, ((...args: unknown[]) => void)[]> = {};
  closed = false;

  on(event: string, cb: (...args: unknown[]) => void): void {
    (this.handlers[event] ??= []).push(cb);
  }
  close(): void {
    this.closed = true;
  }
  emit(event: string, ...args: unknown[]): void {
    (this.handlers[event] ?? []).forEach((cb) => cb(...args));
  }
}
