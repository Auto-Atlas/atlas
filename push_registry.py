"""Push device registry — per-tenant FCM tokens + their wake schedule.

Multi-tenant by design: every device that wants the proactive wake-up registers its
FCM token plus the local time it wants to be woken (and its timezone). The scheduler
reads this to know WHO to wake and WHEN. Nothing user-specific is hardcoded — a tenant
id keys each device, times/tz come from the device's own config.

Storage is a plain JSON file (human-editable, git-ignorable). At scale this becomes a
per-tenant row in a real store; the interface stays the same.
"""

from __future__ import annotations

import json
import os
import threading
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

_LOCK = threading.Lock()


def _path() -> Path:
    return Path(os.getenv("EVE_PUSH_REGISTRY", str(Path.home() / "eve-push-registry.json")))


def _load() -> dict:
    p = _path()
    if not p.is_file():
        return {"devices": {}}
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
        if "devices" not in d:
            d = {"devices": {}}
        return d
    except Exception:
        return {"devices": {}}


def _save(data: dict) -> None:
    p = _path()
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(p)


_ALL_DAYS = [0, 1, 2, 3, 4, 5, 6]  # Mon=0 .. Sun=6 (Python weekday())


def register(token: str, *, tenant: str = "owner", platform: str = "android",
             wake_hour: int = 5, wake_minute: int = 0, tz: str = "America/New_York",
             enabled: bool = True, wake_days: list[int] | None = None) -> dict:
    """Upsert a device's push token + wake config, keyed by the FCM token. Re-registration
    (new token on reinstall) just adds a fresh row; stale tokens are pruned on send failure.

    wake_days = list of weekdays to fire (Mon=0..Sun=6); None PRESERVES the existing value
    (so the app re-registering — which doesn't send days — never wipes a server-set schedule),
    defaulting to all 7 for a brand-new device."""
    token = (token or "").strip()
    if not token:
        raise ValueError("empty token")
    with _LOCK:
        data = _load()
        existing = data["devices"].get(token, {})
        days = wake_days if wake_days is not None else existing.get("wake_days", _ALL_DAYS)
        data["devices"][token] = {
            "tenant": tenant,
            "platform": platform,
            "wake_hour": int(wake_hour),
            "wake_minute": int(wake_minute),
            "tz": tz,
            "enabled": bool(enabled),
            "wake_days": [int(d) for d in days],
            "registered_at": datetime.utcnow().isoformat() + "Z",
        }
        _save(data)
        return data["devices"][token]


def set_wake_days(token: str, wake_days: list[int]) -> dict:
    """Set just the wake-days for an existing device (Mon=0..Sun=6)."""
    with _LOCK:
        data = _load()
        if token not in data["devices"]:
            raise KeyError(token)
        data["devices"][token]["wake_days"] = [int(d) for d in wake_days]
        _save(data)
        return data["devices"][token]


def mark_fired(token: str) -> None:
    """Record that this device was just woken — so due_now won't fire it again today
    (dedups the multi-tick window). Best-effort."""
    with _LOCK:
        data = _load()
        if token in data["devices"]:
            data["devices"][token]["last_fired"] = datetime.utcnow().isoformat() + "Z"
            _save(data)


def remove(token: str) -> None:
    with _LOCK:
        data = _load()
        if data["devices"].pop(token, None) is not None:
            _save(data)


def all_devices() -> dict:
    return _load()["devices"]


def due_now(now_utc: datetime | None = None, window_s: int = 90) -> list[str]:
    """Tokens whose local wake time falls within [now, now+window). The scheduler ticks
    each minute and fires those due, so a device wakes once at its own local hour:minute
    regardless of the server's timezone (true multi-tenant)."""
    now_utc = now_utc or datetime.utcnow().replace(tzinfo=ZoneInfo("UTC"))
    due: list[str] = []
    for token, d in all_devices().items():
        if not d.get("enabled", True):
            continue
        try:
            local = now_utc.astimezone(ZoneInfo(d.get("tz", "America/New_York")))
        except Exception:
            continue
        if local.weekday() not in d.get("wake_days", _ALL_DAYS):
            continue  # not a wake day for this device (e.g. skip Saturdays)
        # Already woken today (this device's local day)? Skip. The scheduler ticks every
        # minute and the window spans >1 tick, so without this the same wake fires twice
        # = "double speak". One wake per local day.
        lf = d.get("last_fired")
        if lf:
            try:
                lf_local = datetime.fromisoformat(lf.replace("Z", "+00:00")).astimezone(local.tzinfo)
                if lf_local.date() == local.date():
                    continue
            except Exception:
                pass
        target_min = int(d.get("wake_hour", 5)) * 60 + int(d.get("wake_minute", 0))
        now_min = local.hour * 60 + local.minute
        # fire when we're at-or-just-past the target minute, within the tick window
        if 0 <= (now_min - target_min) * 60 + local.second < window_s:
            due.append(token)
    return due
