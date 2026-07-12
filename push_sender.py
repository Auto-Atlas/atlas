"""FCM sender — fires the high-priority "wake up and run the morning ritual" push.

The push carries NO personal content — just `{"type":"morning_ritual"}`. The phone
receives it, wakes, launches EVE, and connects to the LOCAL voice loop for the actual
ritual. So the only thing that ever transits Google is a content-free wake trigger;
your whys, goals, briefing — all of it — stay on your machine.

Requires Firebase Admin credentials (a service-account JSON). Point EVE_FIREBASE_CREDENTIALS
at it, or set GOOGLE_APPLICATION_CREDENTIALS. Without creds, send() returns a clear error
instead of crashing — so the rest of the stack runs fine until Firebase is wired.
"""

from __future__ import annotations

import os

from loguru import logger

import push_registry

_APP = None
_INIT_ERR: str | None = None


def _ensure_app():
    """Lazily init firebase-admin from the service-account creds. Cached. Returns the app
    or raises with an actionable message."""
    global _APP, _INIT_ERR
    if _APP is not None:
        return _APP
    if _INIT_ERR is not None:
        raise RuntimeError(_INIT_ERR)
    try:
        import firebase_admin
        from firebase_admin import credentials

        cred_path = (os.getenv("EVE_FIREBASE_CREDENTIALS")
                     or os.getenv("GOOGLE_APPLICATION_CREDENTIALS"))
        if cred_path and os.path.isfile(cred_path):
            cred = credentials.Certificate(cred_path)
        else:
            cred = credentials.ApplicationDefault()  # GOOGLE_APPLICATION_CREDENTIALS / metadata
        _APP = firebase_admin.initialize_app(cred)
        return _APP
    except Exception as e:
        _INIT_ERR = (
            f"Firebase not initialized ({type(e).__name__}: {e}). Set EVE_FIREBASE_CREDENTIALS "
            "to your service-account JSON (Firebase console > Project settings > Service accounts "
            "> Generate new private key) and `pip install firebase-admin`."
        )
        raise RuntimeError(_INIT_ERR)


def _wake_text_for(token: str) -> str:
    """The spoken wake text for a device's tenant. Single-tenant today (reads the
    default dashboard); per-tenant lookup slots in here later."""
    try:
        import rituals
        import persona
        return rituals.wake_text(user_nick=persona.USER_NICK)
    except Exception as e:
        logger.debug(f"wake_text build skipped: {e}")
        return ""


def send_wake(token: str, kind: str = "morning_ritual", text: str = "") -> tuple[bool, str]:
    """Send ONE high-priority wake push. Carries the whys TEXT so the phone speaks it
    LOCALLY (Android TTS) — no voice connection, no echo, works from deep Doze. Returns
    (ok, detail). On an UNREGISTERED/invalid token, prunes it from the registry."""
    try:
        from firebase_admin import messaging
        _ensure_app()
        data = {"type": kind}
        if text:
            data["text"] = text  # spoken locally by the app; never rendered server-side
        msg = messaging.Message(
            token=token,
            data=data,
            android=messaging.AndroidConfig(
                priority="high",  # wake through Doze / app-standby
                ttl=300,          # if undelivered in 5 min, drop it (a stale wake is useless)
            ),
        )
        msg_id = messaging.send(msg)
        return True, msg_id
    except Exception as e:
        name = type(e).__name__
        if name in ("UnregisteredError", "SenderIdMismatchError"):
            push_registry.remove(token)
            return False, f"pruned dead token ({name})"
        return False, f"{name}: {e}"


def send_data(token: str, data: dict, ttl_s: int = 0) -> tuple[bool, str]:
    """Send ONE high-priority DATA push (no notification payload — the app decides
    what to show). Values are stringified: FCM data messages are string maps.
    ttl_s=0 means 'deliver now or drop' (right for time-anchored payloads like an
    alarm-set command: a stale one arriving hours late would ring a dead alarm).
    On an UNREGISTERED/invalid token, prunes it from the registry. Never raises."""
    try:
        from firebase_admin import messaging
        _ensure_app()
        msg = messaging.Message(
            token=token,
            data={k: str(v) for k, v in data.items()},
            android=messaging.AndroidConfig(priority="high", ttl=ttl_s),
        )
        return True, messaging.send(msg)
    except Exception as e:
        name = type(e).__name__
        if name in ("UnregisteredError", "SenderIdMismatchError"):
            push_registry.remove(token)
            return False, f"pruned dead token ({name})"
        return False, f"{name}: {e}"


def broadcast_data(data: dict, ttl_s: int = 0) -> list[dict]:
    """send_data() to EVERY registered device. Single-owner today, so 'all devices'
    IS the owner's phone(s); per-tenant targeting slots in when tenancy does.
    Returns per-device results; an empty registry returns [] (caller logs it)."""
    results = []
    for token in push_registry.all_devices():
        ok, detail = send_data(token, data, ttl_s=ttl_s)
        results.append({"token": token[:12] + "…", "ok": ok, "detail": detail})
        logger.info(f"push data [{data.get('type', '?')}] -> {token[:12]}… ok={ok} ({detail})")
    return results


def wake_due(now_utc=None) -> list[dict]:
    """Send wake pushes to every device due right now. Returns per-device results.
    Marks each fired device so it can't fire again today (no double-speak)."""
    results = []
    for token in push_registry.due_now(now_utc):
        text = _wake_text_for(token)
        ok, detail = send_wake(token, text=text)
        if ok:
            push_registry.mark_fired(token)  # one wake per day — dedups the multi-tick window
        results.append({"token": token[:12] + "…", "ok": ok, "detail": detail})
        logger.info(f"push wake -> {token[:12]}… ok={ok} ({detail})")
    return results
