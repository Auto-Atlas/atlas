# eve-mentra-bridge

A self-hosted [MentraOS](https://mentra.glass) app that bridges **Mentra Live**
smart glasses to the self-hosted **EVE** assistant hub. It is a thin, testable
bridge with three verified end-to-end paths plus one hardware-gated interaction
stub.

```
EVE  ──capture_frame──▶  glasses camera  ──jpeg──▶  EVE POST /v1/vision/frame
EVE  ──surface_visual─▶  lens display  (or speaker fallback on Mentra Live)
EVE  ──POST /narrate──▶  glasses speaker (TTS via SDK audio.speak)
button press / wake  ──▶  (HARDWARE-GATED) ack only — LOOK flow is a TODO
```

Built against **`@mentra/sdk@2.1.29`** (latest stable at time of writing).

## How it works

- One `AppServer` (from `@mentra/sdk`) owns the app's HTTP server (the MentraOS
  webhook) and the session lifecycle. We mount our `POST /narrate` route on that
  same Express instance (`getExpressApp()`), so it shares the app's port.
- A single `EveLink` WebSocket client connects to EVE's `/v1/stream` with
  subprotocol auth (`Sec-WebSocket-Protocol: bearer, <token>`) and
  `?surface=glasses`. It auto-reconnects with exponential backoff.
- Inbound EVE events are dispatched onto whichever glasses session is currently
  active:
  - `capture_frame` (acted on only when `source` is `any` or `glasses`) →
    `session.camera.requestPhoto()` → base64 → `POST ${EVE_API_URL}/v1/vision/frame`
    with `{ request_id, jpeg_b64 }` (8 MiB cap, bounded retries).
  - `surface_visual` → a MentraOS layout. On display glasses: a reference card
    when we have title+text, else a text wall. **Mentra Live has no display**
    (`capabilities.hasDisplay === false`) so it speaks the title instead (and
    always logs). The layout-writer path stays real for display devices.
- `POST /narrate { text }` (bearer-checked with the shared EVE token) →
  `session.audio.speak(text)`.

## Confirmed SDK API surface (from `@mentra/sdk@2.1.29` type declarations)

| Concern | API |
| --- | --- |
| Server | `class AppServer` — `new AppServer({ packageName, apiKey, port })`, override `protected onSession(session, sessionId, userId)`, `getExpressApp(): Express`, `start()` |
| Photo | `session.camera.requestPhoto(opts?): Promise<PhotoData>` where `PhotoData = { buffer: Buffer, mimeType, filename, requestId, size, timestamp: Date }` |
| Stream | `session.camera.startStream({ rtmpUrl, video?, audio? })`, `stopStream()`, `startManagedStream()` |
| Speak | `session.audio.speak(text, opts?): Promise<{ success, error?, duration? }>`; `session.audio.playAudio({ audioUrl })` |
| Display | `session.layouts.showTextWall(text, opts?)`, `showReferenceCard(title, text, opts?)`, `showDoubleTextWall`, `showDashboardCard`, `showBitmapView` |
| Events | `session.events.onTranscription(cb)` → `{ text, isFinal }`; `session.events.onButtonPress(cb)` → `{ buttonId, pressType: 'short'|'long' }` (each returns an unsubscribe fn) |
| Capabilities | `session.capabilities: { modelName, hasCamera, hasDisplay, hasMicrophone, hasSpeaker } \| null` — Mentra Live = camera yes, display NO, speaker yes, mic yes |
| Env | `PACKAGE_NAME`, `MENTRAOS_API_KEY`, `PORT` (SDK default 7010) |

## Setup

### 1. Register the app in the MentraOS console

1. Log into **console.mentra.glass** with your MentraOS account.
2. **Create App**.
3. Set a unique **package name** (e.g. `com.yourorg.evementra`) — it must match
   `PACKAGE_NAME`.
4. Set the **Public URL** to where this server is reachable (your public
   hostname, or an ngrok/static tunnel during development). The SDK serves the
   MentraOS webhook at `/webhook` on that URL automatically.
5. Copy the generated **API key** into `MENTRAOS_API_KEY`.
6. Add the **permissions** this app uses: **Camera**, **Microphone**, and
   **Speaker** (Mentra Live has all three; it has no display).

For local development, expose your port with a static tunnel and use that as the
Public URL, e.g.:

```bash
ngrok http --url=<YOUR_STATIC_NGROK_URL> 7010
```

### 2. Configure environment

```bash
cp .env.example .env
# then edit .env — set EVE_API_URL, EVE_APP_TOKEN, PACKAGE_NAME, MENTRAOS_API_KEY
```

`EVE_APP_TOKEN` is the shared bearer used for both directions: this bridge
authenticates to EVE's REST + WS with it, and requires it on inbound `/narrate`
calls.

### 3. Install, build, test, run

```bash
npm install
npm run build      # tsc (strict) -> dist/
npm test           # vitest run
npm start          # node dist/index.js
# or, for iteration:
npm run dev        # tsx src/index.ts
```

## HARDWARE-GATED checklist

Everything below is unit-tested against a mocked SDK session, but the following
can only be **verified on real Mentra Live glasses paired to the MentraOS phone
app** — there is no emulator path for the camera/speaker/buttons:

- [ ] **Photo capture + latency** — `capture_frame` → `session.camera.requestPhoto()`
      actually returns JPEG bytes, and round-trip latency to EVE is acceptable.
- [ ] **Speaker playback** — `/narrate` and the `surface_visual` speak-fallback
      actually produce audible TTS on the glasses (`session.audio.speak`).
- [ ] **Button events** — `session.events.onButtonPress` fires with the real
      `buttonId` / `pressType` values so the ack (and future LOOK flow) triggers.
- [ ] **Wake phrase** — real `onTranscription` events on-device for a configured
      wake word.
- [ ] **RTMP / managed stream start** — `session.camera.startStream` /
      `startManagedStream` against your media server (not exercised by the core
      paths; wired types confirmed only).
- [ ] **`surface_visual` on a display device** — the text-wall / reference-card
      layout path (Mentra Live has no lens; needs e.g. Even Realities G1 to see
      it render).

## Not yet implemented (explicit TODOs in code)

- **Wake / button LOOK flow** (`src/index.ts`): a button press currently only
  logs and speaks an ack. The real flow (press/wake → photo → EVE) needs an
  EVE-issued `request_id`; posting to `/v1/vision/frame` without one is wrong.
  Once EVE exposes a LOOK-initiation endpoint, obtain a `request_id` there and
  reuse the existing frame-POST path.
- **URL → QR code** (`src/display.ts`): when `surface_visual.url` is present on a
  display device, render a QR bitmap via `layouts.showBitmapView`. For now the
  URL is folded into the spoken/text summary.

## Deploy

See `eve-mentra.service` — a commented systemd unit template (not installed).
Put secrets in a chmod-600 `EnvironmentFile`, never in the unit or the repo.

## Layout of `src/`

| File | Responsibility |
| --- | --- |
| `config.ts` | Env-driven config, validated fast at startup. No hardcoded hosts/tokens. |
| `eve-link.ts` | EVE WS client: subprotocol auth + `surface=glasses`, backoff reconnect, pure `dispatchEveMessage` / `computeBackoff`. |
| `frame-post.ts` | Photo bytes → base64 → `POST /v1/vision/frame` with cap + retries. |
| `display.ts` | `surface_visual` → layout call, with speaker/log fallback for displayless devices. |
| `narrate.ts` | `POST /narrate` bearer-checked → `session.audio.speak`. |
| `index.ts` | AppServer wiring: session lifecycle, EveLink, narrate route, capture handler. |
| `logger.ts` | Narrow logger interface (pino-compatible). |
