import { describe, it, expect, vi } from "vitest";
import { narrateHandler, extractBearer, type SpeakSession } from "../src/narrate.js";
import { mockLogger } from "./helpers.js";
import type { Request, Response } from "express";

function fakeRes() {
  const res = {
    statusCode: 0,
    body: undefined as unknown,
    status(code: number) {
      this.statusCode = code;
      return this;
    },
    json(payload: unknown) {
      this.body = payload;
      return this;
    },
  };
  return res as unknown as Response & { statusCode: number; body: unknown };
}

function fakeReq(auth: string | undefined, body: unknown): Request {
  return { headers: { authorization: auth }, body } as unknown as Request;
}

function speakSession(): SpeakSession & { audio: { speak: ReturnType<typeof vi.fn> } } {
  return {
    capabilities: { hasSpeaker: true },
    audio: { speak: vi.fn(async () => ({ success: true })) },
  };
}

describe("extractBearer", () => {
  it("parses a Bearer header", () => {
    expect(extractBearer("Bearer abc.def")).toBe("abc.def");
    expect(extractBearer("bearer xyz")).toBe("xyz");
    expect(extractBearer("Basic foo")).toBeNull();
    expect(extractBearer(undefined)).toBeNull();
  });
});

describe("narrateHandler", () => {
  const token = "tok_shared";

  it("rejects a bad token with 401 and never speaks", async () => {
    const session = speakSession();
    const handler = narrateHandler({ token, getSession: () => session, logger: mockLogger() });
    const res = fakeRes();
    await handler(fakeReq("Bearer WRONG", { text: "hi" }), res);
    expect(res.statusCode).toBe(401);
    expect(session.audio.speak).not.toHaveBeenCalled();
  });

  it("speaks on a good token", async () => {
    const session = speakSession();
    const handler = narrateHandler({ token, getSession: () => session, logger: mockLogger() });
    const res = fakeRes();
    await handler(fakeReq("Bearer tok_shared", { text: "Dinner is ready" }), res);
    expect(res.statusCode).toBe(200);
    expect(session.audio.speak).toHaveBeenCalledWith("Dinner is ready");
  });

  it("400 on missing text", async () => {
    const handler = narrateHandler({ token, getSession: () => speakSession(), logger: mockLogger() });
    const res = fakeRes();
    await handler(fakeReq("Bearer tok_shared", {}), res);
    expect(res.statusCode).toBe(400);
  });

  it("503 when no glasses session is active", async () => {
    const handler = narrateHandler({ token, getSession: () => null, logger: mockLogger() });
    const res = fakeRes();
    await handler(fakeReq("Bearer tok_shared", { text: "hi" }), res);
    expect(res.statusCode).toBe(503);
  });
});
