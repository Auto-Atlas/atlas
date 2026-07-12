import { describe, it, expect, vi } from "vitest";
import { renderSurfaceVisual, decideSurfaceAction, type DisplaySession } from "../src/display.js";
import { mockLogger } from "./helpers.js";

function makeSession(caps: { hasDisplay: boolean; hasSpeaker: boolean } | null): DisplaySession {
  return {
    capabilities: caps,
    layouts: {
      showTextWall: vi.fn(),
      showReferenceCard: vi.fn(),
    },
    audio: {
      speak: vi.fn(async () => ({ success: true })),
    },
  };
}

describe("decideSurfaceAction", () => {
  it("note on a display device -> text wall", () => {
    expect(decideSurfaceAction({ type: "surface_visual", kind: "note", text: "hello" }, { hasDisplay: true, hasSpeaker: true })).toEqual({ kind: "textWall", text: "hello" });
  });
  it("title+text on a display device -> reference card", () => {
    expect(decideSurfaceAction({ type: "surface_visual", title: "T", text: "B" }, { hasDisplay: true, hasSpeaker: true })).toEqual({ kind: "referenceCard", title: "T", text: "B" });
  });
  it("no display but speaker -> speak", () => {
    expect(decideSurfaceAction({ type: "surface_visual", title: "Reminder" }, { hasDisplay: false, hasSpeaker: true })).toEqual({ kind: "speak", text: "Reminder" });
  });
  it("no display, no speaker -> log only", () => {
    expect(decideSurfaceAction({ type: "surface_visual", title: "x" }, { hasDisplay: false, hasSpeaker: false }).kind).toBe("logOnly");
  });
});

describe("renderSurfaceVisual", () => {
  it("surface_visual note -> showTextWall called with the text (display device)", async () => {
    const s = makeSession({ hasDisplay: true, hasSpeaker: true });
    await renderSurfaceVisual(s, { type: "surface_visual", kind: "note", text: "battery low" }, mockLogger());
    expect(s.layouts.showTextWall).toHaveBeenCalledWith("battery low");
    expect(s.layouts.showReferenceCard).not.toHaveBeenCalled();
    expect(s.audio.speak).not.toHaveBeenCalled();
  });

  it("no-display device (Mentra Live) -> speaks the title, no layout call", async () => {
    const s = makeSession({ hasDisplay: false, hasSpeaker: true });
    const action = await renderSurfaceVisual(s, { type: "surface_visual", kind: "note", title: "Standup in 5" }, mockLogger());
    expect(action.kind).toBe("speak");
    expect(s.audio.speak).toHaveBeenCalledWith("Standup in 5");
    expect(s.layouts.showTextWall).not.toHaveBeenCalled();
  });
});
