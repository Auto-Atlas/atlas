<div align="center">

# 👓 Atlas for smart glasses — EVE on your face

### Camera, speaker, and lens display for a self-hosted assistant, via MentraOS.

![typescript](https://img.shields.io/badge/TypeScript-strict-3178C6?logo=typescript&logoColor=white)
![sdk](https://img.shields.io/badge/%40mentra%2Fsdk-2.1.29-purple)
![tests](https://img.shields.io/badge/tests-33%20passing-brightgreen)

Part of the **[Atlas](https://github.com/Auto-Atlas/atlas)** family ·
[Android app](https://github.com/Auto-Atlas/atlas-android) ·
[iOS app](https://github.com/Auto-Atlas/atlas-ios)

</div>

---

This repo bridges **[MentraOS](https://mentra.glass) smart glasses** (Mentra Live, Even
Realities G1, …) to your self-hosted [Atlas](https://github.com/Auto-Atlas/atlas) server, so
EVE can see through the glasses camera, speak through their speaker, and surface cards on a
lens display:

```
EVE  ──capture_frame──▶  glasses camera  ──jpeg──▶  EVE POST /v1/vision/frame
EVE  ──surface_visual─▶  lens display  (speaker fallback on display-less devices)
EVE  ──POST /narrate──▶  glasses speaker (TTS)
```

The bridge lives in [`mentra-eve/`](mentra-eve/) — a thin, strictly-typed TypeScript app
with three verified end-to-end paths. Everything is env-driven; no hosts or tokens in code.

## 🚀 Quick start

**Prerequisites:** Node 22+, a [MentraOS console](https://console.mentra.glass) account, a
running Atlas server.

```bash
git clone https://github.com/Auto-Atlas/atlas-glasses && cd atlas-glasses/mentra-eve
cp .env.example .env      # set EVE_API_URL, EVE_APP_TOKEN, PACKAGE_NAME, MENTRAOS_API_KEY
npm install
npm run build && npm test # tsc strict + vitest (33 tests)
npm start                 # or: npm run dev
```

Full setup — registering the app in the MentraOS console, permissions, tunneling during
development, and the systemd deploy template — is in
**[`mentra-eve/README.md`](mentra-eve/README.md)**.

### ✅ Setup tracker

```markdown
- [ ] App registered at console.mentra.glass (package name, public URL, API key)
- [ ] Permissions granted: Camera, Microphone, Speaker
- [ ] .env filled (EVE_API_URL, EVE_APP_TOKEN, PACKAGE_NAME, MENTRAOS_API_KEY)
- [ ] npm run build && npm test green
- [ ] Bridge connects to EVE's /v1/stream (watch the logs for the surface=glasses hello)
— with glasses in hand: —
- [ ] Photo capture round-trip (capture_frame → JPEG lands at /v1/vision/frame)
- [ ] Speaker TTS audible (/narrate)
- [ ] Button / wake events fire on-device
```

The hardware-gated items above are unit-tested against a mocked SDK but can only be finally
verified on real glasses — the honest checklist lives in
[`mentra-eve/README.md`](mentra-eve/README.md#hardware-gated-checklist).

## ⭐ Star tracker

If Atlas is useful to you, a star helps other people find it.

[![Star History Chart](https://api.star-history.com/svg?repos=Auto-Atlas/atlas,Auto-Atlas/atlas-android,Auto-Atlas/atlas-ios,Auto-Atlas/atlas-glasses&type=Date)](https://star-history.com/#Auto-Atlas/atlas&Auto-Atlas/atlas-android&Auto-Atlas/atlas-ios&Auto-Atlas/atlas-glasses&Date)
