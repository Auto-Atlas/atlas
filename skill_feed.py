# skill_feed.py
#
# The api<->bot firewall for "feed EVE a skill" (Skills-in-app spec §3.1).
#
# approval_api.py (which must NOT import the voice runtime) writes feed requests here; the
# voice process consumes them — claim_next() at session start, claim_live() via a watcher.
# SQLite (the existing approvals.db) is the membrane, exactly as approval_store decouples
# approval staging from release.
#
# Import invariant: this module may import only stdlib + approval_store + skill_loader.
# NEVER jarvis_core / bot / phone_bot / speaker_state — that would pull the voice runtime
# into the approval_api process. All framing here is pure string work; the engine-specific
# INSTR_ROLE wiring lives on the bot side.
#
import time
import uuid

from approval_store import _connect

STATUS_PENDING = "pending"
STATUS_DELIVERING = "delivering"   # live: claimed, not yet spoken (mirrors approval 'releasing')
STATUS_DELIVERED = "delivered"

_PREAMBLE = "The operator just loaded this skill for you — follow it now:\n\n"


def _row_to_dict(row, now: float) -> dict:
    expires_at = row["created_at"] + row["ttl_s"]
    seconds_left = max(0.0, expires_at - now)
    status = row["status"]
    effective = "expired" if (status == STATUS_PENDING and seconds_left <= 0) else status
    return {
        "id": row["id"],
        "tool": row["tool"],
        "mode": row["mode"],
        "body_snapshot": row["body_snapshot"],
        "status": status,
        "effective_status": effective,
        "created_at": row["created_at"],
        "ttl_s": row["ttl_s"],
        "seconds_left": seconds_left,
    }


def enqueue(tool: str, mode: str, body: str, ttl_s: int) -> str:
    feed_id = uuid.uuid4().hex
    now = time.time()
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO skill_feed (id, tool, mode, body_snapshot, status, created_at, ttl_s) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (feed_id, tool, mode, body, STATUS_PENDING, now, int(ttl_s)),
        )
        conn.commit()
    finally:
        conn.close()
    return feed_id


def list_pending() -> list[dict]:
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM skill_feed WHERE status IN (?, ?) ORDER BY created_at DESC",
            (STATUS_PENDING, STATUS_DELIVERING),
        ).fetchall()
    finally:
        conn.close()
    out = [_row_to_dict(r, now) for r in rows]
    # Hide pending rows whose TTL has passed (read-only expiry); keep delivering ones visible
    # so a stuck-mid-announce row surfaces as "loaded, unconfirmed" rather than vanishing.
    return [r for r in out if not (r["status"] == STATUS_PENDING and r["seconds_left"] <= 0)]


def clear_pending(tool: str | None = None) -> int:
    conn = _connect()
    try:
        if tool is None:
            cur = conn.execute("DELETE FROM skill_feed WHERE status=?", (STATUS_PENDING,))
        else:
            cur = conn.execute(
                "DELETE FROM skill_feed WHERE status=? AND tool=?", (STATUS_PENDING, tool)
            )
        conn.commit()
        return cur.rowcount
    finally:
        conn.close()


def _claim(mode: str, new_status: str) -> list[dict]:
    """Atomic single-winner claim via a per-call token (the multi-row analogue of
    approval_store.consume's single-row rowcount==1): flip this caller's matching pending
    rows to new_status stamping our token, then SELECT only the rows bearing that token."""
    token = uuid.uuid4().hex
    now = time.time()
    conn = _connect()
    try:
        conn.execute(
            "UPDATE skill_feed SET status=?, claim_token=?, delivered_at=? "
            "WHERE status=? AND mode=? AND (created_at + ttl_s) > ?",
            (new_status, token, now, STATUS_PENDING, mode, now),
        )
        conn.commit()
        rows = conn.execute(
            "SELECT * FROM skill_feed WHERE claim_token=?", (token,)
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def claim_next() -> list[dict]:
    """For build_context() at session start: 'next'-mode feeds become delivered immediately
    (they are injected synchronously into the protected head, not spoken later)."""
    return _claim("next", STATUS_DELIVERED)


def claim_live() -> list[dict]:
    """For the session watcher: 'live'-mode feeds become 'delivering' (claimed, not yet
    spoken); mark_delivered() finishes them only after announce() returns."""
    return _claim("live", STATUS_DELIVERING)


def mark_delivered(ids: list[str]) -> None:
    if not ids:
        return
    now = time.time()
    conn = _connect()
    try:
        conn.executemany(
            "UPDATE skill_feed SET status=?, delivered_at=? WHERE id=? AND status=?",
            [(STATUS_DELIVERED, now, fid, STATUS_DELIVERING) for fid in ids],
        )
        conn.commit()
    finally:
        conn.close()


def skill_feed_messages(feeds: list[dict]) -> list[dict]:
    """Pure: turn claimed feed rows into system messages the model will follow. Order
    preserved. No engine-specific role here — the bot maps to INSTR_ROLE via announce()."""
    return [
        {"role": "system", "content": _PREAMBLE + f["body_snapshot"]}
        for f in feeds
    ]


def pending_live_messages() -> tuple[list[dict], list[str]]:
    """Claim all live feeds and frame them; returns (messages, claimed_ids). Keeps the
    untested watcher loop to ~2 lines (the claim + framing here are unit-tested)."""
    feeds = claim_live()
    return skill_feed_messages(feeds), [f["id"] for f in feeds]
