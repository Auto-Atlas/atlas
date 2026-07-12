/**
 * eve-mentra-bridge — entry point.
 *
 * Bridges Mentra Live smart glasses <-> the self-hosted EVE assistant hub:
 *
 *   EVE  --capture_frame-->  glasses camera  --jpeg-->  EVE /v1/vision/frame
 *   EVE  --surface_visual--> lens display (or speaker fallback on Mentra Live)
 *   EVE  --POST /narrate-->  glasses speaker (TTS)
 *   button press / wake word --> (HARDWARE-GATED) ack; LOOK flow is a TODO
 *
 * Wiring: one AppServer (from @mentra/sdk) owns the app's HTTP server + session
 * lifecycle. A single EveLink WS client talks to EVE and dispatches inbound
 * events onto whichever glasses session is currently active.
 */

import { AppServer, type AppSession } from "@mentra/sdk";
import WebSocket from "ws";
import { loadConfig, type BridgeConfig } from "./config.js";
import {
  EveLink,
  shouldHandleCapture,
  type CaptureFrameEvent,
  type SurfaceVisualEvent,
} from "./eve-link.js";
import { postFrame, type FetchLike } from "./frame-post.js";
import { renderSurfaceVisual, type DisplaySession } from "./display.js";
import { registerNarrateRoute, type SpeakSession } from "./narrate.js";
import { consoleLogger, type Logger } from "./logger.js";

// ---------------------------------------------------------------------------
// Capture handler (exported for tests).
// ---------------------------------------------------------------------------

/** Subset of AppSession the capture flow needs. */
export interface CaptureSession {
  camera: {
    requestPhoto(options?: unknown): Promise<{ buffer: Uint8Array }>;
  };
}

export interface CaptureDeps {
  config: BridgeConfig;
  getSession: () => CaptureSession | null;
  postFrame: (
    config: BridgeConfig,
    requestId: string,
    jpeg: Uint8Array,
    deps: { fetch: FetchLike; logger: Logger },
  ) => Promise<void>;
  fetch: FetchLike;
  logger: Logger;
}

/**
 * Build the capture_frame handler. Honours the source filter (only "any" /
 * "glasses"), takes a photo on the active session, and POSTs it to EVE under
 * the same request id.
 */
export function makeCaptureFrameHandler(deps: CaptureDeps) {
  return async (evt: CaptureFrameEvent): Promise<void> => {
    if (!shouldHandleCapture(evt.source)) {
      deps.logger.info({ source: evt.source, requestId: evt.request_id }, "capture: ignored (source)");
      return;
    }
    const session = deps.getSession();
    if (!session) {
      deps.logger.warn({ requestId: evt.request_id }, "capture: no active glasses session");
      return;
    }
    try {
      deps.logger.info({ requestId: evt.request_id, prompt: evt.prompt }, "capture: taking photo");
      const photo = await session.camera.requestPhoto();
      await deps.postFrame(deps.config, evt.request_id, photo.buffer, {
        fetch: deps.fetch,
        logger: deps.logger,
      });
    } catch (err) {
      deps.logger.error({ err, requestId: evt.request_id }, "capture: failed");
    }
  };
}

// ---------------------------------------------------------------------------
// Bridge server.
// ---------------------------------------------------------------------------

export class MentraEveBridge extends AppServer {
  private eveLink: EveLink | null = null;
  private activeSession: AppSession | null = null;
  private readonly bridgeLogger: Logger;

  constructor(private readonly bridgeConfig: BridgeConfig) {
    super({
      packageName: bridgeConfig.packageName,
      apiKey: bridgeConfig.mentraosApiKey,
      port: bridgeConfig.port,
    });
    // The SDK exposes a pino logger; fall back to console if unavailable.
    this.bridgeLogger = (this.logger as unknown as Logger) ?? consoleLogger;
    this.setupNarrate();
    this.setupEveLink();
  }

  /** Mount POST /narrate on the AppServer's Express instance. */
  private setupNarrate(): void {
    registerNarrateRoute(this.getExpressApp(), {
      token: this.bridgeConfig.eveAppToken,
      getSession: () => this.activeSession as unknown as SpeakSession | null,
      logger: this.bridgeLogger,
    });
  }

  /** Start the single EVE WS link and route its events to the active session. */
  private setupEveLink(): void {
    const captureHandler = makeCaptureFrameHandler({
      config: this.bridgeConfig,
      getSession: () => this.activeSession as unknown as CaptureSession | null,
      postFrame,
      fetch: fetch as unknown as FetchLike,
      logger: this.bridgeLogger,
    });

    this.eveLink = new EveLink(
      {
        wsUrl: this.bridgeConfig.eveWsUrl,
        token: this.bridgeConfig.eveAppToken,
        reconnectBaseMs: this.bridgeConfig.reconnectBaseMs,
        reconnectMaxMs: this.bridgeConfig.reconnectMaxMs,
        reconnectFactor: this.bridgeConfig.reconnectFactor,
      },
      {
        onCaptureFrame: captureHandler,
        onSurfaceVisual: (evt: SurfaceVisualEvent) => this.handleSurfaceVisual(evt),
      },
      // `ws` accepts subprotocols as an array -> sends
      //   Sec-WebSocket-Protocol: bearer, <token>
      // Constructing it does not open a socket until the event loop turns, so
      // this stays import-safe; tests inject their own factory anyway.
      (url, protocols) => new WebSocket(url, protocols) as unknown as import("./eve-link.js").WsLike,
      this.bridgeLogger,
    );
    this.eveLink.start();
    this.addCleanupHandler(() => this.eveLink?.stop());
  }

  private async handleSurfaceVisual(evt: SurfaceVisualEvent): Promise<void> {
    const session = this.activeSession;
    if (!session) {
      this.bridgeLogger.warn({ visualId: evt.visual_id }, "surface_visual: no active session");
      return;
    }
    await renderSurfaceVisual(session as unknown as DisplaySession, evt, this.bridgeLogger);
  }

  protected override async onSession(
    session: AppSession,
    sessionId: string,
    userId: string,
  ): Promise<void> {
    this.activeSession = session;
    this.bridgeLogger.info({ sessionId, userId }, "session: started");

    // --- Wake / interaction (HARDWARE-GATED) ---
    // The real "LOOK" flow (button/wake -> photo -> EVE) needs an EVE-issued
    // request_id to POST a frame against; posting to /v1/vision/frame WITHOUT
    // one is wrong. Until EVE exposes a LOOK-initiation endpoint, we only ack.
    // Verified end-to-end paths are: capture_frame, surface_visual, /narrate.
    session.events.onButtonPress((data) => {
      this.bridgeLogger.info({ buttonId: data.buttonId, pressType: data.pressType }, "button: press (ack only — LOOK flow TODO)");
      // TODO(look-flow): call EVE's LOOK-initiation endpoint to obtain a
      // request_id, then take a photo and POST it as a capture_frame reply.
      void session.audio.speak("On it.").catch(() => {});
    });

    // Optional wake phrase from transcription (configurable trigger word).
    // Left as a log hook until the LOOK flow exists; keeps the code path real.
    session.events.onTranscription((data) => {
      if (data.isFinal) {
        this.bridgeLogger.debug({ text: data.text }, "transcription (final)");
      }
    });
  }

  protected override async onStop(sessionId: string, userId: string, reason: string): Promise<void> {
    this.bridgeLogger.info({ sessionId, userId, reason }, "session: stopped");
    this.activeSession = null;
  }
}

// ---------------------------------------------------------------------------
// Boot.
// ---------------------------------------------------------------------------

export async function main(): Promise<void> {
  const config = loadConfig();
  const bridge = new MentraEveBridge(config);
  await bridge.start();
  // eslint-disable-next-line no-console
  console.log(`eve-mentra-bridge listening on :${config.port} (package ${config.packageName})`);
}

// Only auto-boot when run as a real process, never on import (e.g. in vitest).
if (!process.env.VITEST) {
  main().catch((err) => {
    // eslint-disable-next-line no-console
    console.error("eve-mentra-bridge failed to start:", err);
    process.exit(1);
  });
}
