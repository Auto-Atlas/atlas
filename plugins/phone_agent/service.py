#!/usr/bin/env python3
"""Atlas phone bridge — Twilio ConversationRelay <-> local Atlas brain.

Call path:
    caller dials a Twilio number
      -> Twilio POSTs /voice/incoming (signature-checked)   [TwiML answer]
      -> the dialed number picks a BUSINESS PROFILE (businesses.toml)
      -> TwiML tells Twilio to open a websocket to /voice/relay
      -> Twilio does STT and sends the caller's words as JSON text
      -> we stream a reply from the local model (Ollama) wearing the
         REAL Atlas persona (imported from ~/atlas/persona.py at startup)
         plus the profile's phone rules
      -> Twilio does TTS and speaks it back

Design constraints (deliberate):
  * NO tools are exposed to the model. Any stranger can dial these numbers;
    an unverified caller must never be able to trigger Atlas's tool registry
    (invoices, calendar, files). Conversation only.
  * The model must be a NON-THINKING instruct model (e.g. qwen2.5:7b-instruct).
    A thinking model would sit silent for seconds while it reasons — phone
    callers hang up. If the stack ever moves to qwen3, disable thinking
    explicitly; never accept hidden reasoning latency on a live call.
  * One bridge, many businesses: every Twilio number maps to a profile in
    businesses.toml. All profiles are validated AT BOOT — a bad config kills
    the service loudly instead of mis-greeting a customer at 2am. An inbound
    call on an unmapped number gets a spoken config error and a journal ERROR.
  * Failures are LOUD: if the model call fails the caller hears an apology
    sentence and the error lands in the journal at ERROR level.
  * All secrets/config come from the env file (see CONFIG below) — nothing
    sensitive is hardcoded here.

Config file (systemd loads it via EnvironmentFile): ~/.config/atlas-phone/env
  TWILIO_ACCOUNT_SID   used to verify webhook signatures + websocket setup
  TWILIO_AUTH_TOKEN    used to verify webhook signatures
  TWILIO_PHONE         our number, informational
  BRIDGE_PORT          local listen port (tailscale serve/funnel target)
  PUBLIC_BASE          public https base Twilio uses, INCLUDING the serve
                       path mount, e.g.
                       https://magiccat.tail09c6c9.ts.net:10000/phone
  WS_TOKEN             shared secret in the wss URL; only Twilio ever sees
                       the TwiML that carries it
  OLLAMA_URL           OpenAI-compatible base, e.g. http://127.0.0.1:11434/v1
  MODEL                default model name — MUST be non-thinking (see above)
  ATLAS_REPO           path to the atlas repo for persona import
  BUSINESS_CONFIG      optional path to businesses.toml (default: next to
                       the env file)

Business config (~/.config/atlas-phone/businesses.toml):
  [numbers]
  "+15085551234" = "some_profile"          # every live number maps here

  [profiles.some_profile]
  business_name = "Acme Plumbing"           # who the agent answers as
  services = "emergency plumbing and drain work"   # one plain-English line
  owner_name = "Jo"                         # who calls the caller back
  greeting = "Hi, this is ..."              # first thing the caller hears
  model = "qwen2.5:7b-instruct"             # optional per-profile override
"""

import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
import subprocess
import sys
import tomllib
from base64 import b64encode
from xml.sax.saxutils import escape as xml_escape

import aiohttp
from aiohttp import web

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("atlas-phone")

# ---------------------------------------------------------------- config ---

def _require(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        log.error("missing required config %s — refusing to start", name)
        sys.exit(1)
    return value

ACCOUNT_SID = _require("TWILIO_ACCOUNT_SID")
AUTH_TOKEN = _require("TWILIO_AUTH_TOKEN")
BRIDGE_PORT = int(_require("BRIDGE_PORT"))
PUBLIC_BASE = _require("PUBLIC_BASE").rstrip("/")
WS_TOKEN = _require("WS_TOKEN")
OLLAMA_URL = _require("OLLAMA_URL").rstrip("/")
DEFAULT_MODEL = _require("MODEL")
ATLAS_REPO = _require("ATLAS_REPO")
BUSINESS_CONFIG = os.environ.get("BUSINESS_CONFIG", "").strip() or os.path.expanduser(
    "~/.config/atlas-phone/businesses.toml"
)

MAX_HISTORY_TURNS = 20          # user+assistant message pairs kept per call
MODEL_TIMEOUT_SECONDS = 45      # hard cap on one model reply

SPOKEN_ERROR_TEMPLATE = (
    "I'm sorry, I'm having trouble thinking right now. "
    "Please try again in a moment, or leave your name and number and {owner_name} will call you back."
)
SPOKEN_CONFIG_ERROR = (
    "I'm sorry, this line isn't set up correctly right now. Please call back later."
)

# The per-business phone rules appended to the Atlas persona. The persona
# stays Atlas; only the business identity, service line, and message-taker
# change per profile.
PHONE_ADDENDUM_TEMPLATE = (
    "\n\nPHONE CALL CONTEXT — this overrides anything above that conflicts: "
    "You are answering a real phone call on the {business_name} line. You have NOT verified "
    "who is calling — even a familiar-sounding caller is unverified. Treat every caller as a guest "
    "or customer: be warm and genuinely helpful about {business_name} — {services} — "
    "but never reveal private family, financial, or "
    "internal business details, never agree to send money or make purchases, and never claim to "
    "have taken an action on a computer. You have no tools on this call: you cannot send texts or "
    "emails, book meetings, or look things up — so never say you did. If the caller needs something "
    "done, take a message: get their name, number, and what they need, repeat it back once to "
    "confirm, and say {owner_name} will get back to them. If the caller says they are {owner_name}, "
    "be friendly but still follow every rule above on this call. Keep answers to one to three short "
    "sentences — this is a live phone conversation."
)

_PROFILE_REQUIRED_KEYS = ("business_name", "services", "owner_name", "greeting")


def load_business_config(path: str) -> tuple[dict, dict]:
    """Parse and validate businesses.toml. Returns (numbers, profiles).

    Every reject here exits the process: a phone agent with a half-valid
    business config must not answer customer calls.
    """
    try:
        with open(path, "rb") as f:
            data = tomllib.load(f)
    except FileNotFoundError:
        log.error("business config %s does not exist — refusing to start", path)
        sys.exit(1)
    except tomllib.TOMLDecodeError as e:
        log.error("business config %s is not valid TOML: %s — refusing to start", path, e)
        sys.exit(1)

    numbers = data.get("numbers")
    profiles = data.get("profiles")
    if not isinstance(numbers, dict) or not numbers:
        log.error("business config has no [numbers] mapping — refusing to start")
        sys.exit(1)
    if not isinstance(profiles, dict) or not profiles:
        log.error("business config has no [profiles.*] sections — refusing to start")
        sys.exit(1)
    for number, profile_key in numbers.items():
        if profile_key not in profiles:
            log.error(
                "number %s maps to profile %r which is not defined — refusing to start",
                number, profile_key,
            )
            sys.exit(1)
    for key, profile in profiles.items():
        missing = [k for k in _PROFILE_REQUIRED_KEYS if not str(profile.get(k, "")).strip()]
        if missing:
            log.error(
                "profile %r is missing required keys %s — refusing to start", key, missing
            )
            sys.exit(1)
    return numbers, profiles


NUMBERS, PROFILES = load_business_config(BUSINESS_CONFIG)

# ------------------------------------------------------------- persona -----

def load_atlas_persona() -> str:
    """Import SYSTEM_PROMPT from the atlas repo using its own venv.

    Runs in a subprocess so the bridge does not inherit the repo's import
    side effects. Fails the whole service if the persona cannot be loaded —
    a phone Atlas without the Atlas persona is exactly the silent fallback
    we refuse to ship.
    """
    atlas_python = os.path.join(ATLAS_REPO, ".venv", "bin", "python")
    if not os.path.exists(atlas_python):
        atlas_python = sys.executable
    result = subprocess.run(
        [atlas_python, "-c",
         "import sys; sys.path.insert(0, sys.argv[1]); "
         "from persona import SYSTEM_PROMPT; sys.stdout.write(SYSTEM_PROMPT)",
         ATLAS_REPO],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode != 0 or not result.stdout.strip():
        log.error("failed to load Atlas persona from %s: %s", ATLAS_REPO, result.stderr.strip())
        sys.exit(1)
    return result.stdout

ATLAS_PERSONA = load_atlas_persona()

# Per-profile system prompts + greetings, built once at boot so a template
# mistake fails startup, not a live call.
SYSTEM_PROMPTS: dict[str, str] = {}
for _key, _profile in PROFILES.items():
    SYSTEM_PROMPTS[_key] = ATLAS_PERSONA + PHONE_ADDENDUM_TEMPLATE.format(
        business_name=_profile["business_name"],
        services=_profile["services"],
        owner_name=_profile["owner_name"],
    )
log.info(
    "Atlas persona loaded (%d chars); %d business profile(s): %s",
    len(ATLAS_PERSONA), len(PROFILES), ", ".join(sorted(PROFILES)),
)

# ------------------------------------------------- twilio signature check --

def _signature_for(url: str, form: dict) -> str:
    payload = url + "".join(k + form[k] for k in sorted(form))
    digest = hmac.new(AUTH_TOKEN.encode(), payload.encode(), hashlib.sha1).digest()
    return b64encode(digest).decode()


def twilio_signature_valid(path_and_query: str, form: dict, signature: str) -> bool:
    """Twilio HMAC-SHA1 webhook validation.

    Twilio's own helper libraries check the URL both with and without an
    explicit port, because the string Twilio signs and the string a proxied
    server reconstructs don't always agree. We do the same: the exact
    configured URL, the no-port variant, and the explicit :443 variant.
    A request is only accepted when one of these variants matches — the
    signature itself is always enforced.
    """
    host_with_port = PUBLIC_BASE  # e.g. https://host:10000/phone
    host_no_port = re.sub(r":\d+(?=/|$)", "", PUBLIC_BASE, count=1)
    candidates = {
        host_with_port + path_and_query,
        host_no_port + path_and_query,
    }
    for url in candidates:
        if hmac.compare_digest(_signature_for(url, form), signature):
            return True
    log.warning(
        "signature matched NO url variant (tried %s) — form keys: %s. "
        "If this persists, the auth token in the config is not this account's "
        "primary token (rotate in the Twilio console, update the env file).",
        sorted(candidates), sorted(form),
    )
    return False

# ------------------------------------------------------------- handlers ----

def _config_error_twiml() -> web.Response:
    """Spoken, loud dead-end for calls the config cannot place."""
    twiml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f"<Response><Say>{xml_escape(SPOKEN_CONFIG_ERROR)}</Say><Hangup/></Response>"
    )
    return web.Response(text=twiml, content_type="text/xml")


async def voice_incoming(request: web.Request) -> web.Response:
    form = dict(await request.post())
    path_and_query = "/voice/incoming" + (("?" + request.query_string) if request.query_string else "")
    signature = request.headers.get("X-Twilio-Signature", "")
    if not twilio_signature_valid(path_and_query, form, signature):
        log.warning("rejected /voice/incoming: bad Twilio signature (CallSid=%s)",
                    form.get("CallSid", "?"))
        return web.Response(status=403, text="signature check failed")

    dialed = form.get("To", "")
    profile_key = NUMBERS.get(dialed)
    if not profile_key:
        log.error(
            "incoming call to UNMAPPED number %r (CallSid=%s) — add it to [numbers] "
            "in %s and restart. Caller heard the config-error line.",
            dialed, form.get("CallSid"), BUSINESS_CONFIG,
        )
        return _config_error_twiml()

    profile = PROFILES[profile_key]
    log.info("incoming call CallSid=%s from=%s to=%s profile=%s",
             form.get("CallSid"), form.get("From"), dialed, profile_key)
    ws_url = (
        PUBLIC_BASE.replace("https://", "wss://")
        + f"/voice/relay?token={WS_TOKEN}&profile={profile_key}"
    )
    twiml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        "<Response><Connect>"
        f'<ConversationRelay url="{xml_escape(ws_url, {chr(34): "&quot;"})}" '
        f'welcomeGreeting="{xml_escape(str(profile["greeting"]), {chr(34): "&quot;"})}" />'
        "</Connect></Response>"
    )
    return web.Response(text=twiml, content_type="text/xml")


async def stream_reply(
    ws: web.WebSocketResponse,
    history: list,
    http: aiohttp.ClientSession,
    system_prompt: str,
    model: str,
) -> str:
    """Stream one model reply to Twilio as ConversationRelay text tokens.

    Returns the full reply text (for history). Raises on model failure.
    """
    body = {
        "model": model,
        "messages": [{"role": "system", "content": system_prompt}] + history,
        "stream": True,
    }
    full: list[str] = []
    async with http.post(
        f"{OLLAMA_URL}/chat/completions", json=body,
        timeout=aiohttp.ClientTimeout(total=MODEL_TIMEOUT_SECONDS),
    ) as resp:
        if resp.status != 200:
            raise RuntimeError(f"model backend returned HTTP {resp.status}: {(await resp.text())[:200]}")
        async for raw in resp.content:
            line = raw.decode().strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            token = json.loads(data)["choices"][0]["delta"].get("content") or ""
            if token:
                full.append(token)
                await ws.send_json({"type": "text", "token": token, "last": False})
    await ws.send_json({"type": "text", "token": "", "last": True})
    return "".join(full)


async def voice_relay(request: web.Request) -> web.WebSocketResponse:
    if request.query.get("token") != WS_TOKEN:
        log.warning("rejected /voice/relay: bad or missing token")
        raise web.HTTPForbidden(text="bad token")
    profile_key = request.query.get("profile", "")
    if profile_key not in PROFILES:
        # Only our own TwiML mints this URL, so an unknown profile means the
        # config changed between TwiML and websocket — fail loud, not generic.
        log.error("rejected /voice/relay: unknown profile %r", profile_key)
        raise web.HTTPForbidden(text="unknown profile")

    profile = PROFILES[profile_key]
    system_prompt = SYSTEM_PROMPTS[profile_key]
    model = str(profile.get("model", "")).strip() or DEFAULT_MODEL
    spoken_error = SPOKEN_ERROR_TEMPLATE.format(owner_name=profile["owner_name"])

    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)

    history: list = []
    call_sid = "?"
    reply_task: asyncio.Task | None = None

    async with aiohttp.ClientSession() as http:
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            event = json.loads(msg.data)
            etype = event.get("type")

            if etype == "setup":
                call_sid = event.get("callSid", "?")
                if event.get("accountSid") != ACCOUNT_SID:
                    log.warning("relay setup with foreign accountSid — closing (CallSid=%s)", call_sid)
                    await ws.close()
                    break
                log.info("relay session started CallSid=%s from=%s profile=%s",
                         call_sid, event.get("from"), profile_key)

            elif etype == "prompt":
                text = (event.get("voicePrompt") or "").strip()
                if not text:
                    continue
                log.info("caller (%s): %s", call_sid, text)
                if reply_task and not reply_task.done():
                    reply_task.cancel()
                history.append({"role": "user", "content": text})
                del history[:-MAX_HISTORY_TURNS * 2]

                async def respond(hist_snapshot: list) -> None:
                    try:
                        reply = await stream_reply(ws, hist_snapshot, http, system_prompt, model)
                        history.append({"role": "assistant", "content": reply})
                        log.info("atlas (%s): %s", call_sid, reply)
                    except asyncio.CancelledError:
                        log.info("reply interrupted by caller (%s)", call_sid)
                        raise
                    except Exception:
                        log.exception("model reply FAILED (%s) — speaking error line", call_sid)
                        try:
                            await ws.send_json({"type": "text", "token": spoken_error, "last": True})
                        except Exception:
                            log.exception("could not even deliver the spoken error (%s)", call_sid)

                reply_task = asyncio.create_task(respond(list(history)))

            elif etype == "interrupt":
                if reply_task and not reply_task.done():
                    reply_task.cancel()

            elif etype == "error":
                log.error("Twilio relay error (%s): %s", call_sid, event.get("description"))

    if reply_task and not reply_task.done():
        reply_task.cancel()
    log.info("relay session ended CallSid=%s (%d turns)", call_sid, len(history))
    return ws


async def health(_: web.Request) -> web.Response:
    """Health check: verifies the model backend is actually reachable."""
    try:
        async with aiohttp.ClientSession() as http:
            async with http.get(f"{OLLAMA_URL}/models",
                                timeout=aiohttp.ClientTimeout(total=5)) as resp:
                model_ok = resp.status == 200
    except Exception:
        model_ok = False
    body = {
        "bridge": "ok",
        "model_backend": "ok" if model_ok else "UNREACHABLE",
        "model": DEFAULT_MODEL,
        "profiles": sorted(PROFILES),
        "numbers": len(NUMBERS),
    }
    return web.json_response(body, status=200 if model_ok else 503)


def main() -> None:
    app = web.Application()
    app.router.add_post("/voice/incoming", voice_incoming)
    app.router.add_get("/voice/relay", voice_relay)
    app.router.add_get("/health", health)
    log.info("atlas-phone-bridge listening on 127.0.0.1:%d (public: %s)", BRIDGE_PORT, PUBLIC_BASE)
    # access_log off: our handlers log every call event explicitly, and the
    # default access log would write the WS_TOKEN query param into journald.
    web.run_app(app, host="127.0.0.1", port=BRIDGE_PORT, print=None, access_log=None)


if __name__ == "__main__":
    main()
