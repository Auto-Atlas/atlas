// Dedicated build for the phone-viewable JARVIS avatar page.
//
// Kept separate from vite.config.ts on purpose:
//   - entry is phone.html (mounts the stage directly, no router/App/setup),
//   - outputs to ./dist-phone so it never clobbers the Tauri/OpenJarvis static
//     bundle that the main config writes to ../src/openjarvis/server/static,
//   - no PWA/service-worker (the phone page is a thin view-only client).
//
// Build:  vite build --config vite.phone.config.ts   (npm run build:phone)
// Serve:  `tailscale serve` exposes ./dist-phone directly (no extra server) and
//         proxies /ws to the phone voice bridge (:8766), all on :8445.
import fs from 'fs';
import path from 'path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Emit the entry as index.html so a plain static file server (here, Tailscale's
// built-in directory serving) returns the page at "/" instead of a file listing.
function emitAsIndexHtml() {
  return {
    name: 'phone-html-as-index',
    closeBundle() {
      const dist = path.resolve(__dirname, 'dist-phone');
      const src = path.join(dist, 'phone.html');
      const dst = path.join(dist, 'index.html');
      if (fs.existsSync(src)) fs.renameSync(src, dst);
    },
  };
}

export default defineConfig({
  base: '/',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  plugins: [react(), tailwindcss(), emitAsIndexHtml()],
  build: {
    outDir: 'dist-phone',
    emptyOutDir: true,
    minify: 'esbuild',
    rollupOptions: {
      input: path.resolve(__dirname, 'phone.html'),
    },
  },
});
