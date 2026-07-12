"""LiveKit room/token helpers for the EVE voice mass-scale path (OPEN-SOURCE path).

PURE infra glue — no pipecat, no audio. Mints LiveKit access tokens so a phone
and the EVE bot can join the SAME LiveKit room. LiveKit's media server (which
YOU self-host) relays the audio, so NAT/firewall/weak-Wi-Fi traversal is solved
for every user on every network — without per-minute vendor fees or a vendor
account. See docs/EVE-VOICE-MASS-SCALE.md and docs/EVE-VOICE-LIVEKIT-INTEGRATION.md.

A LiveKit access token is just an HS256 JWT signed with YOUR api secret + a
`video` grant. We sign it with stdlib (hmac/hashlib/base64) so this:
  * needs NO third-party account, NO credit card, NO paid key,
  * needs NO extra pip dependency to GENERATE tokens,
  * works out-of-the-box against `livekit-server --dev` (default keys devkey/secret),
  * keeps working unchanged when you deploy real self-generated keys.

The keys are YOUR OWN (self-generated, free). Defaults below are LiveKit's
documented dev-mode keys — fine for local Docker testing, replace for anything
public. Override via env: LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os

# LiveKit dev-mode defaults (what `livekit-server --dev` uses). Override in .env
# the moment the server is reachable by anyone but you.
_DEFAULT_URL = "ws://localhost:7880"
_DEV_KEY = "devkey"
_DEV_SECRET = "secret"


def _cfg() -> tuple[str, str, str]:
    url = os.environ.get("LIVEKIT_URL", _DEFAULT_URL).strip()
    key = os.environ.get("LIVEKIT_API_KEY", _DEV_KEY).strip()
    secret = os.environ.get("LIVEKIT_API_SECRET", _DEV_SECRET).strip()
    return url, key, secret


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def access_token(
    room: str,
    identity: str,
    *,
    name: str | None = None,
    can_publish: bool = True,
    can_subscribe: bool = True,
    ttl_seconds: int = 3600,
    now: int | None = None,
) -> str:
    """Mint a LiveKit join token (HS256 JWT) for `identity` to join `room`.

    `now` is injectable for deterministic tests; defaults to wall-clock at call time.
    """
    if now is None:
        import time as _time

        now = int(_time.time())
    _, api_key, api_secret = _cfg()

    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": api_key,            # LiveKit identifies the signing key by issuer
        "sub": identity,
        "name": name or identity,
        "nbf": now,
        "exp": now + ttl_seconds,
        "video": {
            "room": room,
            "roomJoin": True,
            "canPublish": can_publish,
            "canSubscribe": can_subscribe,
            "canPublishData": True,
        },
    }
    signing_input = (
        _b64url(json.dumps(header, separators=(",", ":")).encode())
        + "."
        + _b64url(json.dumps(payload, separators=(",", ":")).encode())
    )
    sig = hmac.new(api_secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return signing_input + "." + _b64url(sig)


def new_call() -> dict:
    """One-shot for a single call: a room name + phone token + bot token.

    Returns {ws_url, room, phone_token, bot_token}. The phone joins `ws_url`/`room`
    with phone_token; an EVE bot worker joins the same room with bot_token. Both
    connect to YOUR LiveKit server, which relays media — no LAN/firewall/Tailscale
    dependence. Room naming is left simple here; wire a real per-session id +
    Phase-2A owner auth into the call-start endpoint.
    """
    url, _, _ = _cfg()
    room = "eve-call"
    return {
        "ws_url": url,
        "room": room,
        "phone_token": access_token(room, "owner-phone", name="owner"),
        "bot_token": access_token(room, "eve", name="EVE"),
    }


def _decode_unverified(token: str) -> dict:
    """Test helper: decode the JWT payload without verifying (for self-check)."""
    payload_b64 = token.split(".")[1]
    payload_b64 += "=" * (-len(payload_b64) % 4)
    return json.loads(base64.urlsafe_b64decode(payload_b64))


if __name__ == "__main__":
    # Self-check — NO server, NO account, NO key needed (uses dev defaults).
    call = new_call()
    print("ws_url:", call["ws_url"], "room:", call["room"])
    tok = call["phone_token"]
    print("token parts:", len(tok.split(".")), "(expect 3)")
    claims = _decode_unverified(tok)
    assert claims["video"]["room"] == "eve-call" and claims["video"]["roomJoin"] is True
    assert claims["iss"] and claims["exp"] > claims["nbf"]
    # verify the HMAC signature round-trips with the configured secret
    _, _, secret = _cfg()
    head, body, sig = tok.split(".")
    expect = _b64url(hmac.new(secret.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest())
    assert hmac.compare_digest(sig, expect), "signature mismatch"
    print("OK — valid LiveKit JWT, signature verifies, claims:", json.dumps(claims["video"]))
