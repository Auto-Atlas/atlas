import { describe, it, expect } from "vitest";
import { loadConfig, deriveWsUrl, ConfigError } from "../src/config.js";

const good = {
  EVE_API_URL: "http://localhost:8799",
  EVE_APP_TOKEN: "tok_abc",
  MENTRAOS_API_KEY: "mk_123",
  PACKAGE_NAME: "com.eve.mentra",
};

describe("config", () => {
  it("fails fast when required env vars are missing", () => {
    expect(() => loadConfig({})).toThrow(ConfigError);
    try {
      loadConfig({});
    } catch (e) {
      const msg = (e as Error).message;
      expect(msg).toContain("EVE_API_URL");
      expect(msg).toContain("EVE_APP_TOKEN");
      expect(msg).toContain("MENTRAOS_API_KEY");
      expect(msg).toContain("PACKAGE_NAME");
    }
  });

  it("treats blank/whitespace values as missing", () => {
    expect(() => loadConfig({ ...good, EVE_APP_TOKEN: "   " })).toThrow(/EVE_APP_TOKEN/);
  });

  it("loads a valid config with sane defaults", () => {
    const c = loadConfig(good);
    expect(c.eveApiUrl).toBe("http://localhost:8799");
    expect(c.eveWsUrl).toBe("ws://localhost:8799/v1/stream");
    expect(c.port).toBe(7010);
    expect(c.maxFrameBytes).toBe(8 * 1024 * 1024);
    expect(c.reconnectFactor).toBe(2);
  });

  it("derives wss from https and honours EVE_WS_URL override", () => {
    expect(deriveWsUrl("https://eve.example.com")).toBe("wss://eve.example.com/v1/stream");
    const c = loadConfig({ ...good, EVE_WS_URL: "ws://custom/host" });
    expect(c.eveWsUrl).toBe("ws://custom/host");
  });

  it("rejects non-numeric numeric knobs", () => {
    expect(() => loadConfig({ ...good, PORT: "abc" })).toThrow(ConfigError);
  });
});
