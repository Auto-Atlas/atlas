/**
 * Minimal structured-logger interface.
 *
 * The MentraOS SDK hands us a pino `Logger` on the AppServer/session; pino
 * already satisfies this shape (`logger.info(obj, msg)`), so we can pass it
 * straight through. Internally we depend only on this narrow interface so the
 * bridge is decoupled from pino and trivially mockable in tests.
 */
export interface Logger {
  debug(obj: unknown, msg?: string): void;
  debug(msg: string): void;
  info(obj: unknown, msg?: string): void;
  info(msg: string): void;
  warn(obj: unknown, msg?: string): void;
  warn(msg: string): void;
  error(obj: unknown, msg?: string): void;
  error(msg: string): void;
}

/** A console-backed fallback logger used only if the SDK gives us nothing. */
export const consoleLogger: Logger = {
  debug: (a: unknown, b?: string) => console.debug(a, b ?? ""),
  info: (a: unknown, b?: string) => console.info(a, b ?? ""),
  warn: (a: unknown, b?: string) => console.warn(a, b ?? ""),
  error: (a: unknown, b?: string) => console.error(a, b ?? ""),
};
