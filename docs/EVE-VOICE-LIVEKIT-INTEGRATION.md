# EVE Voice — Self-Hosted LiveKit Integration (CHOSEN PATH, implementation-ready)

> The open-source, self-hosted path: no vendor account, no per-minute fees, no credit card.
> Keys are YOUR OWN (self-generated). Helper written + tested: `livekit_rooms.py`.
> Context: docs/EVE-VOICE-MASS-SCALE.md. (Daily kept only as a managed fallback reference.)

## Why LiveKit self-host
Phone + EVE bot join the same LiveKit **room**; YOUR LiveKit server relays the audio. NAT/
firewall/weak-Wi-Fi traversal solved for every user, every network — with **zero per-minute
fees** and media on **your** infra (private-first). Trade: you run the server. Same pipecat
STT→LLM→TTS pipeline; only the transport changes.

## Token generation — ALREADY DONE (`livekit_rooms.py`)
LiveKit access tokens are HS256 JWTs signed with your API secret. `livekit_rooms.py` mints them
with **stdlib only** — no dep, no account. Verified locally:
`.venv/Scripts/python.exe livekit_rooms.py` → prints a valid, signature-checked token using the
dev keys. Same code works unchanged with real keys (set `LIVEKIT_API_KEY/SECRET/URL` in `.env`).

## ✅ VALIDATED 2026-06-23 (connection layer)
`livekit_smoke.py` joined a real room — `CONNECTED identity='eve' room='eve-call'`, server logged
participant "eve" — using our stdlib-signed token + dev server. **No account/card/paid key.** Token
gen + server + join all proven. Remaining: media node-ip config, `phone_bot` wire-up, app SDK.

## Phase A — prove it locally (free, no cloud)
1. **Run the server (Docker).** Two gotchas learned the hard way:
   - **`--bind 0.0.0.0`** — without it, `--dev` binds the container's localhost and Docker's
     port-forward can't reach it (`Handshake not finished`).
   - **`--node-ip <host-LAN-ip>`** — needed for *media* (audio) to reach the host/phone; otherwise the
     server advertises its internal container IP (172.x) and DTLS/ICE times out. Signaling/join works
     without it; audio does not.
   ```
   docker run -d --name eve-livekit -p 7880:7880 -p 7881:7881 -p 7882:7882/udp \
     livekit/livekit-server --dev --bind 0.0.0.0 --node-ip <host-lan-ip>
   ```
   `--dev` uses keys `devkey` / `secret` (what `livekit_rooms.py` defaults to).
2. **Install the pipecat transport:** `.venv/Scripts/python.exe -m pip install "pipecat-ai[livekit]"`
   (additive — verified it does NOT change pipecat 1.3.0; safe alongside the SmallWebRTC path).
3. **Server change — `phone_bot.py`** (additive; SmallWebRTC stays default):
   ```python
   transport = build_transport(runner_args)   # in bot()

   def build_transport(runner_args):
       mode = os.getenv("JARVIS_VOICE_TRANSPORT", "smallwebrtc").lower()
       if mode == "smallwebrtc":
           from pipecat.transports.smallwebrtc.transport import SmallWebRTCTransport
           return SmallWebRTCTransport(webrtc_connection=runner_args.webrtc_connection, params=...)
       if mode == "livekit":
           # verified path in pipecat 1.3.0:
           from pipecat.transports.livekit.transport import LiveKitTransport, LiveKitParams
           # runner_args carries ws_url + room + bot_token (from livekit_rooms.new_call())
           return LiveKitTransport(url=runner_args.ws_url, token=runner_args.bot_token,
                                   room_name=runner_args.room,
                                   params=LiveKitParams(audio_in_enabled=True, audio_out_enabled=True,
                                                        vad_analyzer=...))
       raise RuntimeError(f"unknown JARVIS_VOICE_TRANSPORT={mode!r}")
   ```
   **Launch difference:** LiveKit doesn't use the `-t webrtc` dev runner. A small "start call"
   endpoint calls `livekit_rooms.new_call()` → `{ws_url, room, phone_token, bot_token}`. The phone
   joins with `phone_token`; a bot worker runs `bot()` joining the same room with `bot_token`.
4. **App change — `eve-app`:** swap `WebRtcVoiceClient`/SmallWebRTC for the **LiveKit Android SDK**
   (`io.livekit:livekit-android`): get `ws_url`+`phone_token` from the start-call endpoint →
   `room.connect(wsUrl, token)` → audio auto-flows. Deletes the fragile hand-rolled ICE/SDP code.

## Phase B — make it work for everyone, anywhere
Local Docker only works on your LAN/Tailscale (same reachability limits). For "anywhere, any
user," deploy LiveKit on a **public host**:
- A small always-on VM (cheap, ~a few $/mo) with a public IP, or LiveKit's deploy guides.
- Generate **real keys**: `livekit-server generate-keys` (or a `config.yaml`), set them in `.env`.
- Enable LiveKit's built-in **TURN + TLS** so any phone on any network connects.
- Then `LIVEKIT_URL=wss://your-host` and it just works globally. No per-minute fees — only the VM.

## Auth seam (multi-tenant from day one)
The "start call" endpoint mints tokens — that's where **per-user auth** lives. Build it on the
seamless **Phase 2A** identity contracts (per-device credential → authorized to mint a room
token, scoped to that user's room). One server, many isolated users.

## Cost reminder
LiveKit transport = infra-only (your VM). The dominant cost at scale is **STT+LLM+TTS inference
per minute** — model/host choice decides unit economics (mass-scale doc §4). Build that 1-pager
before scaling.

## Status / what's left
- ✅ Token generation (`livekit_rooms.py`) — written, tested, no deps.
- ✅ Integration spec (this doc).
- ⏳ Needs Docker on the box + `pip install pipecat-ai[livekit]`, then the `build_transport` wire-up.
- ⏳ App rebuild with the LiveKit Android SDK + on-device test.
- ⏳ Public VM deploy for true anywhere-access (Phase B).
