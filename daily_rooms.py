"""Daily room + meeting-token helpers for the EVE voice mass-scale path.

PURE infra glue — no pipecat, no audio. Creates a short-lived Daily room and
mints owner/participant tokens so a phone and the EVE bot can join the SAME
room. This is the "managed WebRTC" replacement for the self-hosted SmallWebRTC
signaling: the media server is Daily's global infra, so NAT/firewall traversal
is solved for every user on every network (see docs/EVE-VOICE-MASS-SCALE.md).

Activation (no code change needed here):
  1. Create a Daily account, copy the API key.
  2. Set DAILY_API_KEY in .env (and optionally DAILY_DOMAIN=<your-subdomain>).
  3. `pip install daily-python` into .venv (the pipecat DailyTransport SDK).
  4. Set JARVIS_VOICE_TRANSPORT=daily and wire build_transport() per
     docs/EVE-VOICE-DAILY-INTEGRATION.md.

Until DAILY_API_KEY is set every call here raises a clear RuntimeError — nothing
runs or bills without an explicit, real key. No placeholders, no mock rooms.

Uses stdlib urllib only (no extra dependency) so it imports cleanly even before
the Daily SDK is installed.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

_API_BASE = "https://api.daily.co/v1"


def _api_key() -> str:
    key = os.environ.get("DAILY_API_KEY", "").strip()
    if not key:
        raise RuntimeError(
            "DAILY_API_KEY is not set. Add it to .env (from https://dashboard.daily.co) "
            "before creating rooms/tokens. Nothing runs against Daily without a real key."
        )
    return key


def _post(path: str, payload: dict) -> dict:
    """POST JSON to the Daily REST API with bearer auth; return parsed JSON."""
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{_API_BASE}{path}",
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {_api_key()}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"Daily API {path} failed ({e.code}): {detail}") from e


def create_room(*, exp_seconds: int = 3600, enable_chat: bool = False) -> dict:
    """Create a short-lived, private room. Returns the Daily room object (has 'url', 'name').

    exp_seconds: room auto-expires after this many seconds (default 1h) so we
    never leak long-lived rooms. Owner mints tokens against room['name'].
    """
    import time as _time  # local import: Date/time is a side effect, keep it out of import path

    props = {
        "privacy": "private",
        "properties": {
            "exp": int(_time.time()) + exp_seconds,
            "enable_chat": enable_chat,
            "start_audio_off": False,
            "start_video_off": True,  # voice-only: cheaper + no camera
            "enable_prejoin_ui": False,
        },
    }
    return _post("/rooms", props)


def create_token(
    room_name: str, *, is_owner: bool, user_name: str, exp_seconds: int = 3600
) -> str:
    """Mint a meeting token for room_name. Phone = participant, EVE bot = owner."""
    import time as _time

    props = {
        "properties": {
            "room_name": room_name,
            "is_owner": is_owner,
            "user_name": user_name,
            "exp": int(_time.time()) + exp_seconds,
        }
    }
    return _post("/meeting-tokens", props)["token"]


def new_call() -> dict:
    """One-shot: create a room + a phone token + a bot token for a single call.

    Returns {room_url, room_name, phone_token, bot_token}. This is exactly what
    the "start a call" endpoint hands back: the phone joins room_url with
    phone_token; a bot worker joins the same room with bot_token. Both reach
    Daily's media servers directly, so no LAN/firewall/Tailscale dependence.
    """
    room = create_room()
    name = room["name"]
    return {
        "room_url": room["url"],
        "room_name": name,
        "phone_token": create_token(name, is_owner=False, user_name="owner-phone"),
        "bot_token": create_token(name, is_owner=True, user_name="eve"),
    }


if __name__ == "__main__":
    # Smoke test — requires a REAL DAILY_API_KEY in the environment.
    import pprint

    pprint.pprint(new_call())
