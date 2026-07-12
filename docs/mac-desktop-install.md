# EVE desktop on the Mac — install runbook

Goal: install EVE on the Mac and verify it works. This is the Tauri desktop app
built natively for macOS, pointed at the sidecar running on the host over the
tailnet. Items marked ☐verify should be proven during execution.

## Pre-verified (unified main)

- `app/frontend` builds clean: `npm run build:tauri` → fresh `dist/` (NOT plain
  `npm run build` — that targets the openjarvis server static dir and leaves
  `dist/` stale; Tauri's `frontendDist` is `../dist`).
- Backend URLs are build-time knobs, no code changes needed:
  `VITE_JARVIS_WS` (event bridge, default `ws://127.0.0.1:8765`),
  `VITE_JARVIS_RTC`, `VITE_API_URL` (`src/lib/jarvisWs.ts`, `src/lib/api.ts`).

## The one architectural fact

your-host's bridge binds **loopback** (`ws://127.0.0.1:8765`) — a remote Mac
cannot reach it directly. Expose it over the tailnet the same way the phone
voice path does (`tailscale serve`), then build the Mac app against the served
URL:

```bash
# on your-host (once; pick a free serve port, 8445 suggested — 8444 is phone voice)
tailscale serve --bg --https=8445 http://localhost:8765
# → wss://<your-host>:8445  ☐verify WS upgrade passes through serve
```

## Mac steps (via `ssh mac`, per the sshmac skill: nvm Node 22, brew paths)

```bash
# 1. toolchain probe  ☐verify
ssh mac "xcodebuild -version && (command -v cargo || echo NO-RUST) && (command -v rustup || echo NO-RUSTUP)"
# rust missing → install: ssh mac "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y"

# 2. clone unified main (<your-org>)  ☐verify gh/ssh auth on the Mac
ssh mac "git clone git@github.com:<your-org>/jarvis-sidecar.git ~/Projects/jarvis-sidecar || (cd ~/Projects/jarvis-sidecar && git checkout main && git pull)"

# 3. build (nvm 22; VITE_ overrides point at your-host's served bridge)
ssh mac "export PATH=/opt/homebrew/bin:\$HOME/.cargo/bin:\$PATH; export NVM_DIR=\$HOME/.nvm; source /opt/homebrew/opt/nvm/nvm.sh; nvm use 22; \
  cd ~/Projects/jarvis-sidecar/app/frontend && npm ci && \
  VITE_JARVIS_WS=wss://<your-host>:8445 npm run build:tauri && \
  npm run tauri build"
# → bundle at src-tauri/target/release/bundle/macos/*.app (+ .dmg)  ☐verify names

# 4. install + launch
ssh mac "cp -r ~/Projects/jarvis-sidecar/app/frontend/src-tauri/target/release/bundle/macos/*.app /Applications/ && open -a <AppName>"
```

## Verify "it works" (the acceptance)

1. App launches on the Mac (unsigned local build: first launch may need
   right-click→Open or `xattr -dr com.apple.quarantine`  ☐verify Tahoe behavior).
2. Live events flow: speak to EVE near your-host → transcript/status appears in
   the Mac app (proves the WS leg end-to-end).
3. Dashboard/history/approvals tabs render with real data.
4. Record a short screen capture for docs.

## Known limits (be honest in the report)

- CSP: `tauri.conf.json` `connect-src` allows `'self' http://localhost:* http://127.0.0.1:*`
  — a remote `wss://<your-host>:8445` needs that origin added for the Mac build
  ☐verify (one-line change; keep it env/branch-scoped or add the specific host).
- Voice on the Mac: the desktop app's talk path targets the loopback voice
  loop; full remote voice parity may need the phone-voice (:8444) route —
  assess during execution, don't promise it in v1.
- Signing/notarization: not needed for the owner's own Mac (local build);
  required only if we distribute the .dmg.

## Verified outcome

- Rust 1.96.1 installed (rustup, user-local); repo cloned to
  `~/Projects/jarvis-sidecar`; `npm ci` clean.
- Built with `VITE_JARVIS_WS=wss://your-host.<tailnet>.ts.net:8445` →
  `OpenJarvis.app` (ad-hoc signed; **.dmg bundling fails headless over SSH —
  expected, the .app is the artifact**). Installed to `/Applications`,
  quarantine cleared, launched (verified running), live WS connection to the
  bridge confirmed server-side.
- your-host serve leg added: `tailscale serve --bg --https=8445 http://localhost:8765`.
- CSP: tightened to loopback + `*.ts.net` after security review
  (see docs/eve-wireguard-transport.md §Desktop CSP).
- Not done: notarization (needs Apple Developer credentials — owner-gated,
  same gate as the iOS goal); remote voice parity (assess separately).
