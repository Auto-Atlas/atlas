#
# Texting — real SMS from the owner's own number via SMS Gate (capcom6
# android-sms-gateway) running in Local Server mode on his phone, reached
# over the tailnet. Two-step by design:
#
#   1. prepare_text(name, message)  -> resolves the contact and STAGES the
#      message. Nothing is sent. Jarvis reads recipient + message back.
#   2. confirm_send_text()          -> sends EXACTLY what was staged.
#
# The stage expires after 10 minutes and is cleared on every new prepare, so a
# stale "yes" can't fire an old message. The send only exists when the
# gateway confirms acceptance — failures are reported verbatim.
#
# .env: JARVIS_SMS_GATEWAY_URL (e.g. http://<phone-tailscale-ip>:8080)
#       JARVIS_SMS_USER / JARVIS_SMS_PASS (shown in the SMS Gate app)
#

import os
import time

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

from contacts import resolve

# Two send backends:
#   macrodroid — POST to MacroDroid's webhook URL on the phone (tailnet).
#                MacroDroid fires a native Send SMS action. No extra app needed.
#   smsgate   — POST to SMS Gate's Local Server. Original backend.
# Set JARVIS_SMS_BACKEND to pick; default is macrodroid.
SMS_BACKEND = os.getenv("JARVIS_SMS_BACKEND", "macrodroid").lower()

# macrodroid backend config
MACRODROID_SEND_URL = os.getenv("JARVIS_MACRODROID_SEND_URL", "")

# smsgate backend config (legacy)
GATEWAY_URL = os.getenv("JARVIS_SMS_GATEWAY_URL", "").rstrip("/")
GATEWAY_USER = os.getenv("JARVIS_SMS_USER", "")
GATEWAY_PASS = os.getenv("JARVIS_SMS_PASS", "")

_STAGE_TTL_S = 600
_pending: dict | None = None
_pending_at: float = 0.0
# Sent-once idempotency: stable keys of payloads already delivered to the gateway,
# for this process/session. The consume-before-await stage guard stops a double
# CONFIRM of one stage, but the model can re-stage the SAME message (a post-denial
# / threshold-lower retry) and confirm again — which re-sent the SMS (live bug,
# 2026-06-22). Keying off the payload makes a confirm send AT MOST ONCE per draft.
_sent_once: set[str] = set()


def _draft_key(phone: str, message: str) -> str:
    """Stable dedupe key for a staged draft — there is no draft id, so the
    (phone, message) pair IS the identity of what gets sent."""
    return f"{phone}\x00{message}"


async def _send_macrodroid(phone: str, message: str) -> None:
    """GET the MacroDroid webhook with phone and message as query params.
    MacroDroid webhooks pass variables via URL query string, not POST body."""
    if not MACRODROID_SEND_URL:
        raise RuntimeError(
            "MacroDroid send URL is not configured — set JARVIS_MACRODROID_SEND_URL "
            "in .env to the webhook URL from MacroDroid on the phone"
        )
    timeout = aiohttp.ClientTimeout(total=20)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        async with session.get(
            MACRODROID_SEND_URL,
            params={"phone": phone, "message": message},
        ) as resp:
            body = await resp.text()
            if resp.status not in (200, 201, 202):
                raise RuntimeError(f"MacroDroid webhook HTTP {resp.status}: {body[:160]}")


async def _send_smsgate(phone: str, message: str) -> None:
    """POST to SMS Gate's Local Server endpoint."""
    if not (GATEWAY_URL and GATEWAY_USER and GATEWAY_PASS):
        raise RuntimeError("SMS Gate gateway is not configured (.env JARVIS_SMS_*)")
    timeout = aiohttp.ClientTimeout(total=20)
    auth = aiohttp.BasicAuth(GATEWAY_USER, GATEWAY_PASS)
    async with aiohttp.ClientSession(timeout=timeout, auth=auth) as session:
        async with session.post(
            f"{GATEWAY_URL}/message",
            json={"message": message, "phoneNumbers": [phone]},
        ) as resp:
            body = await resp.text()
            if resp.status not in (200, 201, 202):
                raise RuntimeError(f"SMS Gate HTTP {resp.status}: {body[:160]}")


async def send_sms(phone: str, message: str) -> None:
    """Send an SMS via the configured backend. Raises on any failure — callers
    must report honestly, never assume delivery."""
    if SMS_BACKEND == "smsgate":
        await _send_smsgate(phone, message)
    else:
        await _send_macrodroid(phone, message)


PREPARE_TEXT_SCHEMA = FunctionSchema(
    name="prepare_text",
    description=(
        "Stage a text message (SMS) to someone in the user's contacts. Does NOT send. "
        "Returns the resolved recipient and exact message; read both back to the user "
        "and ask for confirmation. If multiple contacts match, list them and ask which. "
        "Use whenever the user asks to text, SMS, or message a person."
    ),
    properties={
        "name": {
            "type": "string",
            "description": "The contact to text, as the user said it, e.g. 'Mike' or 'Sarah Jones'.",
        },
        "message": {
            "type": "string",
            "description": (
                "The message to send. If the user dictated words, use them exactly. If "
                "they asked you to compose ('text her something sweet', 'reply for me'), "
                "write it yourself: warm, natural, first person as the user, the way a "
                "husband or friend actually texts — short, no AI-speak, no signatures. "
                "Your draft is read back for approval before anything sends."
            ),
        },
    },
    required=["name", "message"],
)

CONFIRM_SEND_SCHEMA = FunctionSchema(
    name="confirm_send_text",
    description=(
        "Actually send the text message staged by prepare_text. Call ONLY after the "
        "user explicitly confirmed out loud (said yes / send it). Never call it in the "
        "same turn as prepare_text."
    ),
    properties={},
    required=[],
)


async def handle_prepare_text(params: FunctionCallParams):
    global _pending, _pending_at
    _pending = None  # any new prepare invalidates whatever was staged before

    name = str(params.arguments.get("name", "")).strip()
    message = str(params.arguments.get("message", "")).strip()
    if not name or not message:
        await params.result_callback(
            {"ok": False, "error": "both a contact name and a message are required"}
        )
        return

    if SMS_BACKEND == "smsgate" and not (GATEWAY_URL and GATEWAY_USER and GATEWAY_PASS):
        await params.result_callback(
            {"ok": False, "error": "SMS Gate gateway is not configured (.env JARVIS_SMS_*)"}
        )
        return
    if SMS_BACKEND == "macrodroid" and not MACRODROID_SEND_URL:
        await params.result_callback(
            {"ok": False, "error": "MacroDroid send URL is not configured (.env JARVIS_MACRODROID_SEND_URL)"}
        )
        return

    r = resolve(name)
    if r["status"] == "none":
        await params.result_callback(
            {"ok": False, "error": r.get("error") or f"no contact found matching {name!r}"}
        )
        return
    if r["status"] == "ambiguous":
        await params.result_callback(
            {
                "ok": False,
                "ambiguous": True,
                "candidates": [m["name"] for m in r["matches"]],
                "instruction": "Ask the user which of these contacts they meant, then prepare again with the full name.",
            }
        )
        return

    match = r["matches"][0]
    _pending = {"name": match["name"], "phone": match["phone"], "message": message}
    _pending_at = time.monotonic()
    logger.info(f"prepare_text staged -> {match['name']} ({len(message)} chars)")
    await params.result_callback(
        {
            "ok": True,
            "staged": True,
            "recipient": match["name"],
            "number": match["phone"],
            "message": message,
            "instruction": (
                "Read the recipient and the exact message back to the user and ask if "
                "you should send it. Only call confirm_send_text after they say yes."
            ),
        }
    )


async def handle_confirm_send_text(params: FunctionCallParams):
    global _pending
    if _pending is None or (time.monotonic() - _pending_at) > _STAGE_TTL_S:
        _pending = None
        await params.result_callback(
            {"ok": False, "error": "nothing is staged to send — prepare the text again"}
        )
        return

    staged, _pending = _pending, None  # consume the stage: a yes fires at most once

    # Sent-once guard: if this exact payload already went to the gateway (e.g. the
    # model re-staged the same message after a denial and confirmed again), do NOT
    # re-send — report it was already delivered.
    key = _draft_key(staged["phone"], staged["message"])
    if key in _sent_once:
        logger.info(f"confirm_send_text: already sent to {staged['name']} — no re-send")
        await params.result_callback(
            {"ok": True, "already_sent": True, "recipient": staged["name"],
             "instruction": "That text was already sent — tell the user it's done; do not send it again."}
        )
        return

    try:
        await send_sms(staged["phone"], staged["message"])
    except Exception as e:
        logger.warning(f"confirm_send_text failed: {e}")
        await params.result_callback(
            {"ok": False, "error": f"the text was NOT sent — {e}"}
        )
        return

    _sent_once.add(key)  # mark sent only AFTER the gateway accepted it
    logger.info(f"SMS sent to {staged['name']}")
    await params.result_callback(
        {"ok": True, "sent": True, "recipient": staged["name"]}
    )
