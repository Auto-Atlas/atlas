import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

// @mentra/sdk's package "exports" map exposes a "development" condition that
// points at TypeScript sources (./src/*.ts) which are NOT in the published
// tarball. Vite's dev resolver prefers that condition and fails. Alias the
// package to its built entry so resolution works; tests that need the SDK
// still `vi.mock` it, so this alias is only a resolution fallback.
const mentraSdkDist = fileURLToPath(
  new URL("./node_modules/@mentra/sdk/dist/index.js", import.meta.url),
);

export default defineConfig({
  resolve: {
    alias: {
      "@mentra/sdk": mentraSdkDist,
    },
  },
  test: {
    include: ["__tests__/**/*.test.ts"],
    environment: "node",
    globals: false,
  },
});
