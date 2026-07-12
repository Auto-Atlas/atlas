# visual_store.py
#
# Surfaced-visual spool — rendered images EVE chooses to SHOW (desktop screenshot,
# a picture, later a chart) cross from the voice loop to approval_api here, and the
# app fetches them by id over the authenticated /v1/visual/{id} endpoint. Unlike
# vision_frames (camera frames, delete-on-read), a surfaced visual may be fetched
# late or twice (reconnect, both phone and desktop) — so reads DON'T consume, and a
# TTL sweep (default 1h) is the cleanup. Import invariant: stdlib only.
#
import os
import re
import time
import uuid
from pathlib import Path

_ID_RE = re.compile(r"^[a-f0-9]{8,32}$")
_MAX_AGE_S = float(os.getenv("EVE_VISUAL_TTL_S", "3600"))


def spool_dir() -> Path:
    d = Path(os.getenv("EVE_VISUAL_SPOOL", str(Path(__file__).parent / "visuals")))
    d.mkdir(parents=True, exist_ok=True)
    return d


def valid_id(visual_id: str) -> bool:
    """Ids become filenames — plain lowercase hex only, no traversal ever."""
    return bool(_ID_RE.fullmatch(str(visual_id or "")))


def save(data: bytes) -> str:
    """Store one JPEG, return its new id. Atomic write (tmp + os.replace)."""
    visual_id = uuid.uuid4().hex[:16]
    dest = spool_dir() / f"{visual_id}.jpg"
    tmp = dest.with_name(f"{dest.name}.{os.getpid()}.tmp")
    tmp.write_bytes(data)
    os.replace(str(tmp), str(dest))
    return visual_id


def read(visual_id: str) -> bytes | None:
    """Non-consuming read (late fetch / multiple surfaces). None if absent."""
    if not valid_id(visual_id):
        return None
    try:
        return (spool_dir() / f"{visual_id}.jpg").read_bytes()
    except FileNotFoundError:
        return None


def sweep(max_age_s: float | None = None) -> int:
    """Drop visuals older than the TTL. Returns how many were removed."""
    cutoff = time.time() - (max_age_s if max_age_s is not None else _MAX_AGE_S)
    dropped = 0
    for p in spool_dir().glob("*.jpg"):
        try:
            if p.stat().st_mtime < cutoff:
                p.unlink()
                dropped += 1
        except OSError:
            continue
    return dropped
