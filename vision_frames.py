# vision_frames.py
#
# Frame spool — the file artifact that carries a camera frame across the process
# boundary (approval_api receives the phone's upload; the voice loop's look tool
# consumes it). Same pattern as skill_feed/reminders: the two processes must not
# import each other, so they meet on disk. Import invariant: stdlib only.
#
# Privacy contract: frames are TRANSIENT. take() deletes on read, and sweep()
# drops anything older than a few minutes, so a missed pickup never leaves a
# camera photo sitting on disk.
#
import os
import re
import time
from pathlib import Path

_ID_RE = re.compile(r"^[a-f0-9]{8,32}$")
_MAX_AGE_S = float(os.getenv("EVE_VISION_FRAME_TTL_S", "300"))


def spool_dir() -> Path:
    d = Path(os.getenv("EVE_VISION_SPOOL", str(Path(__file__).parent / "vision_frames")))
    d.mkdir(parents=True, exist_ok=True)
    return d


def valid_id(request_id: str) -> bool:
    """Request ids become filenames — reject anything but plain lowercase hex so a
    hostile id can never traverse out of the spool."""
    return bool(_ID_RE.fullmatch(str(request_id or "")))


def save(request_id: str, data: bytes) -> Path:
    """Atomic write (tmp + os.replace) so a poller can never read a half frame."""
    if not valid_id(request_id):
        raise ValueError("bad request_id")
    dest = spool_dir() / f"{request_id}.jpg"
    tmp = dest.with_name(f"{dest.name}.{os.getpid()}.tmp")
    tmp.write_bytes(data)
    os.replace(str(tmp), str(dest))
    return dest


def take(request_id: str) -> bytes | None:
    """Read AND delete the frame (transient by contract). None if not (yet) there."""
    if not valid_id(request_id):
        return None
    p = spool_dir() / f"{request_id}.jpg"
    try:
        data = p.read_bytes()
    except FileNotFoundError:
        return None
    try:
        p.unlink()
    except OSError:
        pass
    return data


def sweep(max_age_s: float | None = None) -> int:
    """Delete stale frames (uncollected uploads). Returns how many were dropped."""
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
