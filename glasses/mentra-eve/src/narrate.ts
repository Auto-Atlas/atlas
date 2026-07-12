/**
 * Inbound narration endpoint.
 *
 *   POST /narrate
 *   Authorization: Bearer <EVE_APP_TOKEN>
 *   { "text": "..." }
 *
 * EVE (or any authorised caller) pushes text here and we speak it through the
 * glasses' speaker via the MentraOS SDK (`session.audio.speak`). The route is
 * mounted on the AppServer's own Express instance so it shares the app's port.
 */

import type { Express, Request, Response } from "express";
import express from "express";
import type { Logger } from "./logger.js";

/** Subset of AppSession the narrate flow needs. */
export interface SpeakSession {
  capabilities: { hasSpeaker: boolean } | null;
  audio: {
    speak(text: string, options?: unknown): Promise<{ success: boolean; error?: string }>;
  };
}

/** Constant-time-ish bearer check. */
export function extractBearer(header: string | undefined): string | null {
  if (!header) return null;
  const m = /^Bearer\s+(.+)$/i.exec(header.trim());
  return m ? m[1].trim() : null;
}

export interface NarrateDeps {
  token: string;
  /** Returns the session to speak on, or null if no glasses are connected. */
  getSession: () => SpeakSession | null;
  logger: Logger;
}

/**
 * Build the Express handler. Exported (not just mounted) so it can be unit
 * tested with fake req/res objects, no HTTP server required.
 */
export function narrateHandler(deps: NarrateDeps) {
  return async (req: Request, res: Response): Promise<void> => {
    const token = extractBearer(req.headers["authorization"]);
    if (!token || token !== deps.token) {
      res.status(401).json({ error: "unauthorized" });
      return;
    }

    const text = (req.body as { text?: unknown } | undefined)?.text;
    if (typeof text !== "string" || text.trim() === "") {
      res.status(400).json({ error: "missing 'text'" });
      return;
    }

    const session = deps.getSession();
    if (!session) {
      deps.logger.warn("narrate: no active glasses session");
      res.status(503).json({ error: "no active glasses session" });
      return;
    }

    if (session.capabilities && !session.capabilities.hasSpeaker) {
      deps.logger.warn("narrate: device has no speaker");
      res.status(422).json({ error: "device has no speaker" });
      return;
    }

    try {
      const result = await session.audio.speak(text);
      if (!result.success) {
        deps.logger.warn({ err: result.error }, "narrate: speak failed");
        res.status(502).json({ error: result.error ?? "speak failed" });
        return;
      }
      res.status(200).json({ ok: true });
    } catch (err) {
      deps.logger.error({ err }, "narrate: speak threw");
      res.status(500).json({ error: "internal error" });
    }
  };
}

/** Mount POST /narrate on the given Express app. */
export function registerNarrateRoute(app: Express, deps: NarrateDeps): void {
  app.post("/narrate", express.json({ limit: "64kb" }), narrateHandler(deps));
}
