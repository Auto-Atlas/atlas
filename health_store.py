"""The owner's latest health snapshot — one JSON file, last-writer-wins, staleness-aware.

The phone app reads Samsung-Health-fed data from Android's Health Connect and POSTs a compact
snapshot to approval_api (/v1/health/snapshot); EVE's `health_status` tool reads it here.
Deliberately boring: one file, atomic replace, a written-at stamp the tool uses to tell the
user how fresh the numbers are. Health data honesty rule: the AGE is part of the data — a
reader that hides it turns "your heart rate is 62" into a lie when the sample is hours old.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path


def _path() -> Path:
    return Path(os.getenv("EVE_HEALTH_STORE", str(Path.home() / ".eve" / "health_snapshot.json")))


def save(snapshot: dict) -> None:
    """Persist the snapshot atomically with a server-side written-at stamp (the phone's own
    clock is not trusted for staleness math)."""
    p = _path()
    p.parent.mkdir(parents=True, exist_ok=True)
    payload = {"written_at": time.time(), "snapshot": snapshot}
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload))
    tmp.replace(p)


def load() -> tuple[dict | None, float | None]:
    """Return (snapshot, age_seconds) — (None, None) when nothing has ever been uploaded or
    the file is unreadable (a corrupt store reads as missing, never as fake-fresh data)."""
    p = _path()
    try:
        payload = json.loads(p.read_text())
        snapshot = payload["snapshot"]
        age = max(0.0, time.time() - float(payload["written_at"]))
        return snapshot, age
    except Exception:
        return None, None
