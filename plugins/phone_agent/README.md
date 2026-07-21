# phone_agent — Atlas answers the business phone

Gives Atlas a phone presence: callers dial a real Twilio number and talk to
Atlas — the same persona, running on the local model. One bridge serves many
businesses: each number maps to a profile (business name, services, greeting,
who takes messages) in a TOML file.

This plugin has two halves:

| Half | Runs where | Tools |
|---|---|---|
| `service.py` — the phone bridge | Its own systemd service (`deploy/systemd/atlas-phone-bridge.service`) | **NONE for callers** — sandboxed by design |
| `plugin.py` — `phone_line_status` | Inside Atlas's brain, via the plugin loader | One low-risk read-only tool for the OWNER |

## The caller sandbox (the point of the design)

Anyone on Earth can dial the number, and callers are **unverified**. So the
phone-facing model gets:

- **No tools, ever.** It cannot touch Atlas's tool registry — no invoices,
  no calendar, no files, no messages. It takes a message instead. This is
  not a config flag; caller tool access would be a separate reviewed feature.
- **Its own settings**, separate from Atlas's: `~/.config/atlas-phone/env`
  (secrets, ports, model) and `~/.config/atlas-phone/businesses.toml`
  (per-business identity). Nothing about the resident Atlas changes when you
  reconfigure the phone agent, and vice versa.
- **A non-thinking model** (`qwen2.5:7b-instruct` by default). Phone callers
  expect an answer in about a second; a thinking model reasons silently and
  callers hang up. If you point this at a thinking-capable model you must
  disable thinking explicitly.
- **A self-contained receptionist persona** built ONLY from the business
  profile — the resident Atlas persona is never imported (it carries the
  owner's private context, and on a live call it leaked the owner's nickname
  and role-played having tools). The prompt forbids inventing contact
  details, prices, or availability, and forbids claiming actions — the only
  facts it may state come from the profile's optional `facts` field, and its
  one promise is that a confirmed message reaches the owner.

Security on the wire: Twilio webhooks are HMAC signature-checked, the
websocket carries a secret token, and relay sessions from foreign Twilio
accounts are dropped. Config is validated at boot — a broken
`businesses.toml` stops the service loudly rather than mis-greeting a
customer. Calls to unmapped numbers hear a spoken config error and land in
the journal at ERROR.

## Call flow

1. Caller dials a mapped number.
2. Twilio POSTs `PUBLIC_BASE/voice/incoming` (signature-checked); the dialed
   number selects the business profile.
3. The bridge replies with TwiML pointing Twilio's ConversationRelay at
   `wss://…/voice/relay` (token + profile pinned in the URL). Twilio does STT.
4. The bridge streams a reply from the local model (persona + profile rules).
   Twilio does TTS. The model never leaves the machine — Twilio only sees text.

## Setup

1. `pip install aiohttp` into the Atlas venv (already present for core).
2. Copy `businesses.example.toml` → `~/.config/atlas-phone/businesses.toml`
   and fill in your real business(es).
3. Create `~/.config/atlas-phone/env` (chmod 600) with:
   `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_PHONE`, `BRIDGE_PORT`,
   `PUBLIC_BASE`, `WS_TOKEN` (long random), `OLLAMA_URL`, `MODEL`,
   `ATLAS_REPO` (see the docstring in `service.py` for each).
4. Expose `BRIDGE_PORT` publicly at `PUBLIC_BASE`. With Tailscale Funnel
   (ports 443/8443/10000 only), a path mount keeps existing ports intact:
   `tailscale funnel --bg --https=10000 --set-path=/phone http://127.0.0.1:8890`
   → `PUBLIC_BASE=https://<host>.ts.net:10000/phone`. **Warning:** running
   `tailscale serve` on a port can silently drop that port's existing Funnel —
   re-check `tailscale funnel status` after any change, and never reassign a
   port another app depends on.
5. Point the Twilio number's voice webhook (POST) at
   `PUBLIC_BASE/voice/incoming`.
6. `cp deploy/systemd/atlas-phone-bridge.service ~/.config/systemd/user/ &&
   systemctl --user daemon-reload && systemctl --user enable --now
   atlas-phone-bridge`
7. Verify: `curl -s http://127.0.0.1:<BRIDGE_PORT>/health` shows
   `"model_backend": "ok"` and your profiles — then call the number.

## Messages, caller ID, and hanging up

- After every call with caller turns, the bridge summarizes the transcript
  (one non-streamed pass on the same local model) into a structured note —
  name, callback number, email, what they need — appended to the message pad
  (`MESSAGES_FILE`, default `~/atlas-phone-messages.md`). Extraction failure
  writes a loud fallback entry pointing at the journal transcript; a message
  never vanishes silently. Optional `NTFY_URL` + `NTFY_TOPIC` (set together
  or not at all) also push each note as a phone notification.
- The caller's real number comes from the phone network, not speech-to-text:
  it's injected into the call context so the agent *confirms* the callback
  number instead of transcribing digits.
- To hang up, the model ends its goodbye with the literal `[END CALL]`; the
  bridge scrubs the marker from speech, lets the goodbye play, then sends
  Twilio's end-session message. A caller speaking during that window cancels
  the hangup and the conversation continues.

## Operating

- Every call is transcribed in `journalctl --user -u atlas-phone-bridge`.
- Ask Atlas "is the phone line up?" — that's the `phone_line_status` tool in
  this plugin (override the probe URL with `ATLAS_PHONE_HEALTH_URL`; loopback
  only).
- Add a business: new `[profiles.*]` + `[numbers]` entry, restart the service.
- Rotated the Twilio auth token? Update the env file and restart, or every
  webhook will be rejected as a bad signature.
