# EVE Voice — Daily Integration (implementation-ready spec)

> The concrete wire-up to move voice from self-hosted SmallWebRTC → **Daily** managed WebRTC.
> Prereq context: docs/EVE-VOICE-MASS-SCALE.md. Helper already written: `daily_rooms.py`.
> Status: spec ready; **nothing runs/bills until a real `DAILY_API_KEY` is set.**

## Why this is the unlock (recap)
Phone and bot both join a Daily **room**; Daily's global media servers relay the audio. Neither
endpoint reaches the other directly, so NAT/firewall/weak-Wi-Fi traversal is solved for every
user, every network. Same pipecat STT→LLM→TTS pipeline; only the transport changes.

## What you do once (when you have the key)
1. Sign up at https://dashboard.daily.co, copy the **API key**.
2. Add to `.env`:  `DAILY_API_KEY=<your real key>`  (optionally `DAILY_DOMAIN=<subdomain>`).
3. Install the SDK into the venv:  `.venv/Scripts/python.exe -m pip install "pipecat-ai[daily]"`
4. Smoke-test the helper:  `.venv/Scripts/python.exe daily_rooms.py`  → prints a real room + tokens.
5. Set `JARVIS_VOICE_TRANSPORT=daily` and start phone_bot. Done.

## Server change — `phone_bot.py` (additive, default unchanged)

Add a transport switch so SmallWebRTC stays the default and Daily is opt-in:

```python
# in bot(), replace the hard-coded SmallWebRTCTransport with:
transport = build_transport(runner_args)

def build_transport(runner_args):
    mode = os.getenv("JARVIS_VOICE_TRANSPORT", "smallwebrtc").lower()
    params = TransportParams(audio_in_enabled=True, audio_out_enabled=True,
                             vad_analyzer=...)  # same params as today
    if mode == "smallwebrtc":
        from pipecat.transports.smallwebrtc.transport import SmallWebRTCTransport
        return SmallWebRTCTransport(webrtc_connection=runner_args.webrtc_connection,
                                    params=params)
    if mode == "daily":
        from pipecat.transports.services.daily import DailyTransport, DailyParams
        # runner_args carries room_url + bot_token (see launch change below)
        return DailyTransport(runner_args.room_url, runner_args.bot_token, "EVE",
                              DailyParams(audio_in_enabled=True, audio_out_enabled=True,
                                          vad_analyzer=...))
    raise RuntimeError(f"unknown JARVIS_VOICE_TRANSPORT={mode!r}")
```

**Launch model difference (important):** SmallWebRTC uses pipecat's `-t webrtc` dev runner,
which hands `bot()` a live `webrtc_connection`. Daily does NOT — instead:
- A small **"start call" endpoint** calls `daily_rooms.new_call()` → returns `{room_url,
  phone_token, bot_token}`.
- The phone joins `room_url` with `phone_token` (app change below).
- A **bot worker** runs `bot()` joining the same room with `bot_token`.

For Phase A (just you), the simplest bot launch is a tiny script that calls `new_call()`, prints
the phone join info (or pushes it to the app), and runs `bot()` against `room_url`+`bot_token`.
At scale this becomes a pooled worker that joins rooms on demand.

## App change — `eve-app` (Android)
Swap the hand-rolled `WebRtcVoiceClient`/SmallWebRTC signaling for the **Daily Android SDK**
(`co.daily:client`). The Talk screen:
1. Calls the "start call" endpoint → gets `room_url` + `phone_token`.
2. `CallClient.join(room_url, phone_token)` → joins; audio auto-flows.
This **deletes** the fragile ICE/SDP/disposal code that caused this session's on-device pain.
Needs a rebuild + on-device test (Wi-Fi, cellular, foreign network).

## Token/room service = the auth seam
The "start call" endpoint is where **per-user auth** lives — build it on the seamless **Phase 2A**
identity contracts (per-device credential → authorized to mint a token). Multi-tenant from day one.

## LiveKit parallel (if you switch after pricing)
Identical shape, swap the nouns: LiveKit **room** + **access token** (signed with *your own*
self-generated API key/secret), `LiveKitTransport(url, token, ...)` on the server, LiveKit
Android SDK on the app. `daily_rooms.py` → `livekit_rooms.py` (token via the livekit-server-sdk).
Everything else — pipeline, app flow, auth seam — is the same. So Phase-A work transfers.

## Cost reminder
Transport (Daily per-minute, or self-host LiveKit infra) is the *small* line item. The dominant
cost at scale is **STT + LLM + TTS inference per minute** — model/host choice decides unit
economics. Build the 1-page cost sheet before scaling (see mass-scale doc §4).
