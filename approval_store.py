# approval_store.py
#
# The durable staging store for remote approvals (EVE Android app, spec §1.4).
#
# The voice loop's in-process `tool_policy._staged` (keyed by id(context), released in a
# live voice turn) can't carry a remote approval: the owner may approve minutes later, after
# the voice session ended, possibly after a sidecar restart. This store decouples staging
# (written by the voice-loop process when a known speaker is blocked) from release (done by
# the separate approval_api process when the owner approves).
#
# Why SQLite (stdlib, no new dependency):
#   - Durability: a staged draft survives a process restart until the owner is reachable.
#   - Atomic cross-process single-fire: the consume is a single conditional UPDATE
#     (pending -> releasing) and `rowcount == 1` is the unique winner. Two voice processes
#     plus the api process can write concurrently; this is genuine cross-process CAS, NOT
#     the in-memory single-event-loop delete that tool_policy._staged / sms_tool use.
#
# Hardening (BMAD review): WAL + busy_timeout=5000 on every connection; a fresh per-call
# connection (sqlite3.Connection is not thread-safe, and the async API runs these via
# asyncio.to_thread); expiry computed at READ time only (a SELECT never takes the write
# lock, so a real stage() can't lose to a read); PRAGMA user_version migrations.
#
# Wall-clock (time.time()) deliberately, NOT time.monotonic() like tool_policy._staged:
# the TTL must survive a restart, so it has to be absolute time.
#
import json
import os
import sqlite3
import time
import uuid

_SCHEMA_VERSION = 5

# Terminal vs live statuses. `releasing` is the reconciliation state: consume() flips
# pending->releasing BEFORE the handler runs, so a crash mid-release leaves the row
# `releasing` (never re-fires — it's no longer pending) to be surfaced as
# "Approved — outcome unverified", never a false success.
STATUS_PENDING = "pending"
STATUS_RELEASING = "releasing"
STATUS_CONSUMED = "consumed"
STATUS_DENIED = "denied"


def _db_path() -> str:
    """Read lazily at call time so tests can monkeypatch EVE_APPROVAL_DB without reload."""
    # Default lives next to the code (the repo checkout, gitignored) — no
    # install-location assumptions baked in.
    default = os.path.join(os.path.dirname(os.path.abspath(__file__)), "approvals.db")
    return os.path.expanduser(os.getenv("EVE_APPROVAL_DB", default))


def set_db_path(path: str) -> None:
    os.environ["EVE_APPROVAL_DB"] = path


def db_exists() -> bool:
    """True if the store file already exists. Lets callers (e.g. tool_policy's disabled
    hot path) avoid CREATING an empty DB just to read a setting that can't exist yet."""
    return os.path.exists(_db_path())


def _connect() -> sqlite3.Connection:
    path = _db_path()
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    conn = sqlite3.connect(path, timeout=5.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    _migrate(conn)
    return conn


def _migrate(conn: sqlite3.Connection) -> None:
    version = conn.execute("PRAGMA user_version").fetchone()[0]
    # Each step below sets a LITERAL user_version (never the _SCHEMA_VERSION constant — a
    # block that wrote the constant would skip newer blocks the moment the constant bumped;
    # that exact trap silently dropped a table once). _SCHEMA_VERSION names the latest schema
    # so an already-current DB skips the per-step checks entirely.
    if version >= _SCHEMA_VERSION:
        return
    if version < 1:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS approvals (
                id             TEXT PRIMARY KEY,
                tool           TEXT NOT NULL,
                args_json      TEXT NOT NULL,
                requester      TEXT,
                requester_tier TEXT NOT NULL,
                risk_level     TEXT NOT NULL,
                summary        TEXT,
                status         TEXT NOT NULL,
                created_at     REAL NOT NULL,
                ttl_s          INTEGER NOT NULL,
                decided_at     REAL,
                result_json    TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_approvals_status ON approvals(status);
            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT
            );
            """
        )
        conn.execute("PRAGMA user_version=1")  # literal: the v1 step only ever means v1
        conn.commit()
    if version < 2:
        # skill_feed: the api<->bot firewall queue for "feed EVE a skill" (skill_feed.py).
        # Same db, one migration owner; idempotent (IF NOT EXISTS) since _migrate runs per connect.
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS skill_feed (
                id            TEXT PRIMARY KEY,
                tool          TEXT NOT NULL,
                mode          TEXT NOT NULL,
                body_snapshot TEXT NOT NULL,
                status        TEXT NOT NULL,
                created_at    REAL NOT NULL,
                ttl_s         INTEGER NOT NULL,
                claim_token   TEXT,
                delivered_at  REAL
            );
            CREATE INDEX IF NOT EXISTS idx_skill_feed_claim ON skill_feed(status, mode);
            """
        )
        conn.execute("PRAGMA user_version=2")  # literal: the v2 step only ever means v2
        conn.commit()
    if version < 3:
        # agent_tasks: the unified delegate-task firewall store (EVE Agent Hub, agent_tasks.py).
        # ONE row = a delegated unit of work, across TWO distinct lifecycle axes kept separate:
        #   status   = work lifecycle (pending->claimed->resolving->resolved/failed/awaiting_user)
        #   delivery = push|poll  (only poll rows are claimable/reapable by EVE's poller)
        # delivered_at IS NULL on a resolved row == "still needs to be spoken" (session-start replay).
        # Same db, one migration owner; idempotent (IF NOT EXISTS) since _migrate runs per connect.
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS agent_tasks (
                id             TEXT PRIMARY KEY,
                agent          TEXT NOT NULL,
                task           TEXT NOT NULL,
                summary        TEXT,
                callback_token TEXT NOT NULL,
                delivery       TEXT NOT NULL,
                status         TEXT NOT NULL,
                claim_token    TEXT,
                claimed_at     REAL,
                claimed_until  REAL,
                claim_count    INTEGER NOT NULL DEFAULT 0,
                requester      TEXT,
                requester_tier TEXT,
                created_at     REAL NOT NULL,
                ttl_s          INTEGER NOT NULL,
                resolved_at    REAL,
                delivered_at   REAL,
                result_json    TEXT,
                trace_id       TEXT,
                depth          INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_claim ON agent_tasks(agent, status, delivery);
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_replay ON agent_tasks(delivered_at);
            """
        )
        conn.execute("PRAGMA user_version=3")
        conn.commit()
    if version < 4:
        # v4 — agent talk-back Q&A (agent_tasks.py): the outstanding question and its
        # (single-fire) answer live on the task row; the callback_token index serves native-A2A
        # push correlation (the a2a server mints its own task id, so pushes match by token).
        _add_column(conn, "agent_tasks", "question_json TEXT")
        _add_column(conn, "agent_tasks", "answer_json TEXT")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_agent_tasks_token "
                     "ON agent_tasks(callback_token)")
        conn.execute("PRAGMA user_version=4")  # literal: the v4 step only ever means v4
        conn.commit()
    if version < 5:
        # v5 — live-delegation-approvals: a pending owner steer ("redirect") rides the task
        # row and is delivered single-fire at the agent's next talk-back check-in.
        _add_column(conn, "agent_tasks", "redirect_json TEXT")
        conn.execute("PRAGMA user_version=5")  # literal: the v5 step only ever means v5
        conn.commit()


def _add_column(conn: sqlite3.Connection, table: str, coldef: str) -> None:
    """ALTER TABLE has no IF NOT EXISTS, and _migrate runs on every _connect from three
    processes (bot, approval_api, webhook) — two can both read the old user_version and race
    the ALTER. Treat 'duplicate column' as success (idempotent, like the IF NOT EXISTS steps)."""
    try:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {coldef}")
    except sqlite3.OperationalError as e:
        if "duplicate column" not in str(e).lower():
            raise


def _row_to_dict(row: sqlite3.Row, now: float) -> dict:
    expires_at = row["created_at"] + row["ttl_s"]
    seconds_left = max(0.0, expires_at - now)
    status = row["status"]
    # Compute (do not persist) expiry for a still-pending row whose TTL has passed.
    effective = "expired" if (status == STATUS_PENDING and seconds_left <= 0) else status
    return {
        "id": row["id"],
        "tool": row["tool"],
        "args": json.loads(row["args_json"]),
        "requester": row["requester"],
        "requester_tier": row["requester_tier"],
        "risk_level": row["risk_level"],
        "summary": row["summary"],
        "status": status,
        "effective_status": effective,
        "created_at": row["created_at"],
        "ttl_s": row["ttl_s"],
        "expires_at": expires_at,
        "seconds_left": seconds_left,
        "decided_at": row["decided_at"],
        "result": json.loads(row["result_json"]) if row["result_json"] else None,
    }


def stage(tool: str, args: dict, *, requester, tier: str, risk: str,
          summary: str, ttl_s: int) -> str:
    approval_id = uuid.uuid4().hex
    now = time.time()
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO approvals (id, tool, args_json, requester, requester_tier, "
            "risk_level, summary, status, created_at, ttl_s) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (approval_id, tool, json.dumps(args), requester, tier, risk, summary,
             STATUS_PENDING, now, int(ttl_s)),
        )
        conn.commit()
    finally:
        conn.close()
    return approval_id


def get(approval_id: str) -> dict | None:
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM approvals WHERE id=?", (approval_id,)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(row, time.time()) if row else None


def list_pending() -> list[dict]:
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM approvals WHERE status=? ORDER BY created_at DESC",
            (STATUS_PENDING,),
        ).fetchall()
    finally:
        conn.close()
    out = [_row_to_dict(r, now) for r in rows]
    return [r for r in out if r["seconds_left"] > 0]   # read-only expiry filter


def list_releasing() -> list[dict]:
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM approvals WHERE status=? ORDER BY created_at DESC",
            (STATUS_RELEASING,),
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def consume(approval_id: str) -> dict | None:
    """Atomic single-fire: flip pending -> releasing iff still pending AND not past TTL.
    Returns the row dict for the unique winner, else None (already consumed/releasing/
    denied/missing/expired). The handler is run by the caller; finish() records the result."""
    now = time.time()
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE approvals SET status=?, decided_at=? "
            "WHERE id=? AND status=? AND (created_at + ttl_s) > ?",
            (STATUS_RELEASING, now, approval_id, STATUS_PENDING, now),
        )
        conn.commit()
        if cur.rowcount != 1:
            return None
        row = conn.execute("SELECT * FROM approvals WHERE id=?", (approval_id,)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(row, now) if row else None


def finish(approval_id: str, result: dict) -> None:
    """releasing -> consumed, recording the real handler's result."""
    conn = _connect()
    try:
        conn.execute(
            "UPDATE approvals SET status=?, result_json=?, decided_at=? "
            "WHERE id=? AND status=?",
            (STATUS_CONSUMED, json.dumps(result), time.time(), approval_id, STATUS_RELEASING),
        )
        conn.commit()
    finally:
        conn.close()


def deny(approval_id: str) -> bool:
    """pending -> denied. Returns True iff this caller transitioned it (single decision)."""
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE approvals SET status=?, decided_at=? WHERE id=? AND status=?",
            (STATUS_DENIED, time.time(), approval_id, STATUS_PENDING),
        )
        conn.commit()
        return cur.rowcount == 1
    finally:
        conn.close()


def get_setting(key: str, default=None):
    conn = _connect()
    try:
        row = conn.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    finally:
        conn.close()
    return row["value"] if row else default


def set_setting(key: str, value: str) -> None:
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )
        conn.commit()
    finally:
        conn.close()
