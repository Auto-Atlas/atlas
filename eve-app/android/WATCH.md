# Atlas on your wrist — Wear OS setup guide

This guide takes you from a paired Galaxy/Wear OS watch to a working Atlas wrist experience:
approvals with hold-to-approve, push-to-talk voice notes, and the **live call** — the animated
orb, a real streamed conversation with your assistant in its own voice, tap-to-interrupt —
working from anywhere your watch or its phone has internet. Nothing here is specific to any
one person's setup: every host, port, token, and voice flows from your own configuration.

> Env vars below use the legacy `EVE_*`/`JARVIS_*` names, which the code reads directly;
> every one of them can also be set with the `ATLAS_` prefix instead (`ATLAS_WATCH_VOICE_PORT`
> etc.) — the server maps them automatically.

## What you need first

- The [Atlas](https://github.com/Auto-Atlas/atlas) server running on your Linux box
  (`approval_api.py` and friends), with its `.env` configured and `approval_token.txt`
  present (the app token — fail-closed everywhere).
- **Tailscale** on that box and on your phone, with MagicDNS. The watch itself never joins the
  tailnet — that's the point of the design.
- The Atlas voice stack you already use for the phone: the brain server (`:8000`), your STT
  (faster-whisper) and TTS (`JARVIS_TTS_BASE_URL`, `JARVIS_TTS_VOICE`) — the watch speaks
  with the SAME voice.
- Android Studio SDK or at least `adb` + a JDK 17 for building the apps.

## 1. Build and install the two apps

Both apps must come from the same build so they share a signature (the watch⇄phone Data Layer
requires it):

```bash
cd eve-app/android      # inside your Atlas checkout
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug :wear:assembleDebug
```

**Phone** (USB, developer mode + USB debugging on; on Samsung also disable Auto Blocker while
installing):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Watch** (wireless debugging — watches have no USB port; watch and computer must be on the
**same Wi-Fi network**):

1. On the watch: Settings → About watch → Software → tap the software version 7× to unlock
   Developer options → enable **ADB debugging** and **Wireless debugging**.
2. Keep the watch's Wi-Fi awake while you work: Settings → Connections → Wi-Fi → **Always on**
   — without this the watch drops Wi-Fi when the screen sleeps and the install dies halfway.
   (Flip it back to Auto afterwards — Auto is also the mode the live call is designed for.)
3. Wireless debugging → **Pair new device**. Note there are **two different ports**: the
   pairing port on the pair screen, and the connect port on the main Wireless-debugging
   screen — mixing them up is the #1 failure. Then on your box:

```bash
adb pair <watch-ip>:<pair-port>      # asks for the 6-digit code shown on the watch
adb connect <watch-ip>:<connect-port>  # port from the MAIN Wireless debugging screen
adb devices                          # the watch should show as "device"
adb -s <watch-ip>:<connect-port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

The first launch on the watch takes ~15–20 s (one-time debug-build verification).

## 2. Server pieces (on your Linux box)

The watch adds two server surfaces beside what the phone app already uses:

**a. Text + voice-note turns** — already part of `approval_api.py` (`POST /v1/ask`,
`POST /v1/voice/turn`). Restart your `atlas-approval-api` service after updating so they're
live. One-shot STT for `/v1/voice/turn` uses `speech_oneshot.py` (env: `EVE_API_WHISPER_MODEL`,
`EVE_API_WHISPER_COMPUTE` default `int8`, plus your existing `WHISPER_DEVICE`,
`JARVIS_TTS_BASE_URL`, `JARVIS_TTS_VOICE`).

**b. The live call** — `watch_bot.py`, a standalone voice loop (your phone's voice loop is
never touched):

```bash
cp deploy/systemd/atlas-watch-voice.service ~/.config/systemd/user/
# Check the port first: EVE_WATCH_VOICE_PORT (default 8791). Pick any free localhost port.
systemctl --user daemon-reload
systemctl --user enable --now atlas-watch-voice.service
journalctl --user -u atlas-watch-voice.service -f   # watch it warm up
```

Env knobs (all optional): `EVE_WATCH_VOICE_PORT` (8791), `EVE_WATCH_PRELOAD=1` (warm models at
boot), `EVE_WATCH_HALF_DUPLEX=1` (default — the assistant never hears its own voice on the
wrist speaker; interruption is the tap), `EVE_WATCH_ALLOW_INTERRUPTIONS=1` (voice barge-in;
best with a headset), `EVE_WATCH_AUTH_TIMEOUT_S` (10).

## 3. The voice door (works away from home, no VPN on the watch)

The live call streams over ONE encrypted WebSocket. Give it a public, token-locked address
served from your box through Tailscale Funnel — no router changes, no LAN exposure, your home
IP stays hidden:

```bash
tailscale funnel --bg --https=10000 http://127.0.0.1:8791
```

Port **10000** is the convention (Funnel only allows 443/8443/10000, and the phone app derives
the door address as `wss://<your-approval-host>:10000/v1/watch/voice` automatically). If your
tailnet hasn't enabled Funnel yet, the command prints the admin link to turn it on.

Auth is enforced by `watch_bot` itself (first frame must carry your app token), so the public
address alone admits nobody.

## 4. Pairing — there is none

Open the Atlas app on the watch once. The phone derives the voice-door address from the
connection you already configured, and hands the watch everything (address + token) over the
Data Layer. Zero typing. (A "Watch voice door URL" override field exists in the phone's
connect settings for custom setups only.)

## 5. Use it

- **Approvals:** pending approvals appear on the wrist; hold-to-approve (520 ms) for anything
  money-shaped; deny is one tap. The Status tile and complication show the pending count.
- **Voice note** (chip): one push-to-talk utterance through your own STT → the full brain →
  its voice back. No Google services in the loop.
- **Live** (chip): tap the orb. First connect takes ~10 s (session warm-up; taps during
  "Connecting" are deliberately ignored). Then talk naturally — the orb condenses when it
  hears you, goes neural-purple while it thinks, flares amber as it speaks. **Tap while it
  speaks to cut it off.** The screen stays awake for the whole call. Network handoffs
  (Wi-Fi ⇄ Bluetooth-behind-phone) reconnect automatically.

## Honest limitations (current)

- The live call is **half-duplex by default**: interruption is the tap, not shouting over the
  assistant (wrist speaker + mic echo makes voice barge-in unreliable without a headset).
- First connect ~10 s while the session warms; subsequent turns respond in ~3–7 s depending on
  what the brain does with the ask.
- One live session at a time — a second caller preempts the first, honestly.
- Every failure is named on-screen (no network, unauthorized, connection lost, phone
  unreachable, "no voice door configured"). If you see a state that lies, that's a bug —
  report it.

## Verify your install

1. `curl -s localhost:<approval-port>/v1/health -H "Authorization: Bearer <token>"` → `ok`.
2. Watch app opens to the approvals screen ("All clear" when empty) — proves the Data Layer.
3. Voice note chip: speak, get the assistant's voice back — proves STT/brain/TTS one-shots.
4. Live chip: hold a multi-turn conversation — proves the door, the stream, and the session.
5. Turn the watch's Wi-Fi off and call again — proves the Bluetooth-behind-phone path your
   watch will use out in the world.
