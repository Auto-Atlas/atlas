/**
 * surface_visual event -> MentraOS layout call.
 *
 * Display-capable glasses (e.g. Even Realities G1, Vuzix Z100) get a real
 * layout: a reference card when we have both a title and body text, otherwise a
 * text wall. Mentra Live has NO display (`capabilities.hasDisplay === false`),
 * so we fall back to speaking the title through the speaker (and always log).
 *
 * The layout-writer code path stays real and unconditional for display devices,
 * so nothing changes when this same bridge runs on hardware that does have a
 * lens.
 */

import type { SurfaceVisualEvent } from "./eve-link.js";
import type { Logger } from "./logger.js";

/**
 * The subset of an AppSession we touch. The real `@mentra/sdk` AppSession
 * satisfies this structurally, and tests pass a mock.
 */
export interface DisplaySession {
  capabilities: { hasDisplay: boolean; hasSpeaker: boolean } | null;
  layouts: {
    showTextWall(text: string, options?: { durationMs?: number }): void;
    showReferenceCard(title: string, text: string, options?: { durationMs?: number }): void;
  };
  audio: {
    speak(text: string, options?: unknown): Promise<{ success: boolean; error?: string }>;
  };
}

export type SurfaceAction =
  | { kind: "referenceCard"; title: string; text: string }
  | { kind: "textWall"; text: string }
  | { kind: "speak"; text: string }
  | { kind: "logOnly"; text: string };

/** Human-readable line summarising a surface_visual for logs/speech/text. */
export function summariseVisual(evt: SurfaceVisualEvent): string {
  return evt.title || evt.text || evt.url || evt.kind || "EVE update";
}

/**
 * Pure decision: given the event and the device capabilities, what should we do?
 * Kept separate from I/O so it is directly unit-testable.
 */
export function decideSurfaceAction(
  evt: SurfaceVisualEvent,
  caps: { hasDisplay: boolean; hasSpeaker: boolean } | null,
): SurfaceAction {
  const hasDisplay = caps?.hasDisplay ?? false;
  const hasSpeaker = caps?.hasSpeaker ?? false;

  if (hasDisplay) {
    // note (or anything with a body): text wall of the text.
    // titled content: reference card.
    if (evt.title && evt.text) {
      return { kind: "referenceCard", title: evt.title, text: evt.text };
    }
    const body = evt.text ?? evt.title ?? summariseVisual(evt);
    return { kind: "textWall", text: body };
  }

  // No display: speak the title if we have a speaker, else just log.
  if (hasSpeaker) {
    return { kind: "speak", text: summariseVisual(evt) };
  }
  return { kind: "logOnly", text: summariseVisual(evt) };
}

/**
 * Render a surface_visual to whatever the current device supports.
 * Returns the action that was taken (useful for tests / metrics).
 *
 * TODO(url): when `evt.url` is present and the device has a display, render a
 * QR code (via layouts.showBitmapView with a generated QR bitmap) so the wearer
 * can open the link on their phone. For now the URL is folded into the text.
 */
export async function renderSurfaceVisual(
  session: DisplaySession,
  evt: SurfaceVisualEvent,
  logger: Logger,
): Promise<SurfaceAction> {
  const action = decideSurfaceAction(evt, session.capabilities);
  logger.info(
    { kind: evt.kind, title: evt.title, visualId: evt.visual_id, action: action.kind },
    "display: rendering surface_visual",
  );

  switch (action.kind) {
    case "referenceCard":
      session.layouts.showReferenceCard(action.title, action.text);
      break;
    case "textWall":
      session.layouts.showTextWall(action.text);
      break;
    case "speak": {
      const res = await session.audio.speak(action.text);
      if (!res.success) {
        logger.warn({ err: res.error }, "display: speak fallback failed");
      }
      break;
    }
    case "logOnly":
      logger.info({ text: action.text }, "display: no display/speaker, logged only");
      break;
  }
  return action;
}
