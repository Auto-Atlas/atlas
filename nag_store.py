# nag_store.py
#
# Open-reminder (ack-loop) store — "keep reminding me until I say it's done". A reminder or
# calendar nudge that fires once and vanishes treats SURFACED as DONE; this store holds the
# acknowledged-state so the initiative engine can RE-surface an open item on an interval
# until the owner confirms complete (complete_reminder), it expires, or it runs out of
# repeats. Spec home: the resurfacing discipline started for the standing link (agent
# messages resurface until spoken) — this is the same idea one level up: things the OWNER
# must act on resurface until the owner says so.
#
# Storage mirrors reminders_tool: one JSON list on disk, flock'd RMW (desktop + phone are
# separate processes), atomic replace. Import invariant: stdlib only — initiative.py (engine)
# and both bodies import this.
#
import json
import os
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

try:
    import fcntl  # posix-only; the real deploy is Linux
except ImportError:  # pragma: no cover - non-posix dev machines
    fcntl = None


def _store_path() -> Path:
    return Path(os.getenv("EVE_NAG_FILE", str(Path(__file__).parent / "nags.json")))


def interval_s() -> float:
    return float(os.getenv("EVE_NAG_INTERVAL_S", "600"))       # re-surface every 10 min


def max_repeats() -> int:
    return int(os.getenv("EVE_NAG_MAX_REPEATS", "6"))          # then one last brief mention


@contextmanager
def _locked():
    if fcntl is None:  # pragma: no cover - non-posix degraded path
        yield
        return
    lock = _store_path().with_name(_store_path().name + ".lock")
    fd = os.open(str(lock), os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def _load() -> list:
    p = _store_path()
    if not p.is_file():
        return []
    try:
        items = json.loads(p.read_text(encoding="utf-8"))
        return items if isinstance(items, list) else []
    except Exception:
        # Corrupt store: keep the evidence aside (reminders_tool rule) — never silently
        # become "nothing is open".
        try:
            p.replace(p.with_name(p.name + ".corrupt"))
        except OSError:
            pass
        return []


def _save(items: list) -> None:
    p = _store_path()
    tmp = p.with_name(f"{p.name}.{os.getpid()}.tmp")
    tmp.write_text(json.dumps(items, indent=1), encoding="utf-8")
    os.replace(str(tmp), str(p))


def add(what, *, source, ref, due, expire_at, now=None,
        first_at=None, interval=None, repeats_max=None) -> dict | None:
    """Register one open item needing the owner's confirmation. Idempotent on (source, ref):
    a calendar tick that fires twice must not mint two nag loops for one event. Returns the
    record, or None when a live record for (source, ref) already exists."""
    now = time.time() if now is None else now
    rec = {
        "id": uuid.uuid4().hex[:12],
        "what": str(what)[:200],
        "source": str(source),           # "calendar" | "reminder" (extensible)
        "ref": str(ref),                 # traceability to the underlying datum
        "due": float(due),               # when the THING is/was due (spoken context)
        "next_at": float(first_at if first_at is not None else now + (interval or interval_s())),
        "interval_s": float(interval or interval_s()),
        "expire_at": float(expire_at),   # moot after this — auto-close, no confirm needed
        "repeats": 0,
        "max_repeats": int(repeats_max if repeats_max is not None else max_repeats()),
        "created_at": now,
    }
    out = {}

    def fn(items):
        if any(x.get("source") == rec["source"] and x.get("ref") == rec["ref"] for x in items):
            out["dup"] = True
            return items
        return items + [rec]

    with _locked():
        _save(fn(_load()))
    return None if out.get("dup") else rec


def pending(now=None) -> list:
    """Every live (non-expired) open item, oldest due first."""
    now = time.time() if now is None else now
    with _locked():
        items = _load()
    return sorted([x for x in items if x.get("expire_at", 0) > now],
                  key=lambda x: x.get("due", 0))


def claim_due(now=None) -> tuple:
    """One engine pass: (due, exhausted, expired). due = live items whose next_at arrived,
    already bumped (repeats+1, next_at advanced) and persisted — a crash after this claims
    at most one missed repeat, never a stuck loop. exhausted = items that just ran out of
    max_repeats (removed; surface ONE honest 'last call'). expired = items past expire_at
    (removed silently — the moment passed, nagging is noise)."""
    now = time.time() if now is None else now
    due, exhausted, expired = [], [], []

    def fn(items):
        keep = []
        for x in items:
            if x.get("expire_at", 0) <= now:
                expired.append(x)
                continue
            if x.get("next_at", 0) <= now:
                if int(x.get("repeats", 0)) >= int(x.get("max_repeats", 0)):
                    exhausted.append(x)
                    continue
                x = dict(x, repeats=int(x.get("repeats", 0)) + 1,
                         next_at=now + float(x.get("interval_s", interval_s())))
                due.append(x)
            keep.append(x)
        return keep

    with _locked():
        _save(fn(_load()))
    return due, exhausted, expired


def find(text) -> list:
    """Case-insensitive match for the owner's words: exact id first, else substring on
    `what` (either direction — 'the dentist thing' vs 'dentist at 4pm'). DELIBERATELY
    no fuzzier than this: the voice model paraphrases, and the model — which heard the
    conversation — is the right fuzzy matcher, not a server-side heuristic. On a miss
    the tool hands the model the full open list and it retries with the exact id
    (see nag_tool). Pure lookup."""
    text = str(text or "").strip().lower()
    if not text:
        return []
    with _locked():
        items = _load()
    hit = [x for x in items if x.get("id") == text]
    if hit:
        return hit
    return [x for x in items
            if text in str(x.get("what", "")).lower()
            or str(x.get("what", "")).lower() in text]


def complete(nag_id) -> dict | None:
    """Owner confirmed done — remove by id. Returns the closed record (None if unknown)."""
    out = {}

    def fn(items):
        keep = []
        for x in items:
            if x.get("id") == nag_id and "rec" not in out:
                out["rec"] = x
                continue
            keep.append(x)
        return keep

    with _locked():
        _save(fn(_load()))
    return out.get("rec")


def snooze(nag_id, minutes, now=None) -> dict | None:
    """Push one item's next resurface out by `minutes` (doesn't consume a repeat)."""
    now = time.time() if now is None else now
    out = {}

    def fn(items):
        for x in items:
            if x.get("id") == nag_id:
                x["next_at"] = now + float(minutes) * 60.0
                out["rec"] = dict(x)
        return items

    with _locked():
        _save(fn(_load()))
    return out.get("rec")
