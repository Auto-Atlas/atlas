#!/usr/bin/env python3
"""Central conversation archive — the "second brain" store.

One SQLite database that holds EVERY conversation from every Jarvis surface in a
normalized shape, so a single hub UI can list them all (with per-source titles)
and the assistant can search its own past to improve.

Sources:
  - desktop-voice : the desktop voice loop (bot.py), transcripts src="local"
  - phone-voice   : the phone voice loop (phone_bot.py), transcripts src="phone"
  - typed-chat    : the OpenJarvis typed UI (synced in by the server)

This module owns the schema + the VOICE ingester (reads the JSONL that bot.py /
phone_bot.py already write to transcripts/ — it does NOT touch the voice loops)
+ read/search helpers used by the `search_history` recall tool. The OpenJarvis
server opens the same DB file for the hub UI and for typed-chat sync.

Delegation visibility: tool_call / tool_result events in the transcripts are
captured as `delegation`/`tool` messages with the target + args + result, so the
hub can show "what Jarvis handed to Hermes/Codex/a tool and what came back".

CLI:
    python conversation_archive.py ingest          # pull voice transcripts in
    python conversation_archive.py list             # recent conversations
    python conversation_archive.py search "pool app"
"""
from __future__ import annotations

import glob
import json
import os
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

# Shared DB location — both the voice sidecar and the OpenJarvis server reach it
# (~/.openjarvis is the house config dir, alongside sessions.db / memory.db).
DEFAULT_DB = Path(os.getenv("JARVIS_BRAIN_DB", Path.home() / ".openjarvis" / "history.db"))
DEFAULT_TRANSCRIPTS = Path(
    os.getenv("JARVIS_LOG_DIR", Path(__file__).resolve().parent / "transcripts")
)

# A new conversation starts after this much silence on the same surface. Voice
# has no explicit "new chat" button, so a gap is the only natural delimiter;
# 30 min keeps a back-and-forth session together but splits morning vs evening.
SESSION_GAP_MS = int(os.getenv("JARVIS_SESSION_GAP_MIN", "30")) * 60 * 1000
# Consecutive bot fragments within this window are one spoken turn (matches the UI).
BOT_MERGE_GAP_MS = 10 * 1000

SOURCE_BY_SRC = {"local": "desktop-voice", "phone": "phone-voice"}

# High-rate / state events that are not part of the readable transcript: they
# keep a session "warm" but never create or title one.
_NONCONTENT_TYPES = {
    "status", "metric", "thinking", "interim_transcript", "user_speaking",
    "bot_speaking", "mic_level", "bot_level", "token",
}

# Tool names that are really hand-offs to another agent/system, not a plain local
# function — shown as "delegation" (Jarvis -> X) in the hub.
_DELEGATION_HINTS = ("hermes", "message", "delegate", "agent", "codex", "claude", "glm", "sms", "text_")

CORE_DDL = """
CREATE TABLE IF NOT EXISTS conversations (
  id           TEXT PRIMARY KEY,
  source       TEXT NOT NULL,
  title        TEXT NOT NULL DEFAULT '',
  started_at   INTEGER NOT NULL,
  ended_at     INTEGER NOT NULL,
  msg_count    INTEGER NOT NULL DEFAULT 0,
  tool_count   INTEGER NOT NULL DEFAULT 0,
  total_tokens INTEGER NOT NULL DEFAULT 0,
  meta         TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS messages (
  id      TEXT PRIMARY KEY,
  conv_id TEXT NOT NULL,
  seq     INTEGER NOT NULL,
  role    TEXT NOT NULL,
  ts      INTEGER NOT NULL,
  text    TEXT NOT NULL DEFAULT '',
  meta    TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id, seq);
CREATE INDEX IF NOT EXISTS idx_messages_text ON messages(conv_id, role);
CREATE INDEX IF NOT EXISTS idx_conv_source_time ON conversations(source, started_at DESC);
"""
FTS_DDL = "CREATE VIRTUAL TABLE IF NOT EXISTS msg_fts USING fts5(conv_id UNINDEXED, source UNINDEXED, title, body);"

# Set per-connection in connect(): True when this SQLite build has FTS5. When
# False the archive still works — search falls back to a LIKE scan on messages.
_FTS_OK = False


def connect(db_path: Path | str = DEFAULT_DB) -> sqlite3.Connection:
    """Open (and initialize) the archive DB. WAL so the sidecar (writer) and the
    OpenJarvis server (reader) can use it concurrently without locking."""
    global _FTS_OK
    p = Path(db_path)
    p.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(p), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    # Several writers share this DB (the sidecar ingester, the search_history
    # tool, and the OpenJarvis server's refresh + typed-chat sync). WAL allows
    # one writer at a time, so make a blocked writer WAIT for the lock instead
    # of failing immediately with "database is locked". busy_timeout MUST be set
    # before the WAL pragma — that pragma is itself a write that can contend.
    conn.execute("PRAGMA busy_timeout=10000;")
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.executescript(CORE_DDL)
    try:
        conn.execute(FTS_DDL)
        _FTS_OK = True
    except sqlite3.OperationalError:
        _FTS_OK = False  # no FTS5 in this build; search() uses LIKE instead
    return conn


# --------------------------------------------------------------------------- #
# Voice transcript ingestion
# --------------------------------------------------------------------------- #
def _ts_ms(iso: str) -> int:
    try:
        return int(datetime.fromisoformat(iso).timestamp() * 1000)
    except Exception:
        return 0


def _target_of(tool: str) -> tuple[str, str]:
    """(kind, target) for a tool call. kind is 'delegation' for agent/messaging
    hand-offs, else 'tool'. target is a human-readable label."""
    low = (tool or "").lower()
    if any(h in low for h in _DELEGATION_HINTS):
        if "hermes" in low:
            return "delegation", "Hermes"
        if "codex" in low:
            return "delegation", "Codex"
        if "glm" in low:
            return "delegation", "GLM"
        if "claude" in low:
            return "delegation", "Claude"
        if "sms" in low or "text_" in low or "message" in low:
            return "delegation", "Messaging"
        return "delegation", tool
    return "tool", tool


class _Segment:
    """One in-progress conversation for a single surface."""

    def __init__(self, source: str, start: int):
        self.source = source
        self.start = start
        self.last = start
        self.msgs: list[dict] = []
        self.tokens = 0
        self.tools = 0
        self.title = ""
        self._pending: dict[str, dict] = {}  # tool name / "deleg:{id}" -> open msg
        self._saw_delegation = False  # suppress the generic jarvis_agent tool card

    def add(self, role: str, ts: int, text: str = "", meta: dict | None = None) -> dict:
        m = {"role": role, "ts": ts, "text": text, "meta": meta or {}}
        self.msgs.append(m)
        return m

    @property
    def has_content(self) -> bool:
        return any(m["role"] in ("user", "assistant", "sms") for m in self.msgs)


def _apply_event(seg: _Segment, ev: dict, ts: int) -> None:
    etype = ev.get("type")
    if etype == "user_transcript":
        text = (ev.get("text") or "").strip()
        if text:
            seg.add("user", ts, text)
            if not seg.title:
                seg.title = text[:80]
    elif etype == "bot_transcript":
        text = (ev.get("text") or "").strip()
        if not text:
            return
        last = seg.msgs[-1] if seg.msgs else None
        if last and last["role"] == "assistant" and ts - last["ts"] <= BOT_MERGE_GAP_MS:
            last["text"] = f"{last['text']} {text}".strip()
            last["ts"] = ts
        else:
            seg.add("assistant", ts, text)
        if not seg.title:
            seg.title = text[:80]
    elif etype == "sms_received":
        text = (ev.get("text") or ev.get("body") or "").strip()
        if text:
            seg.add("sms", ts, text, {"from": ev.get("from") or ev.get("sender")})
            if not seg.title:
                seg.title = text[:80]
    elif etype == "tool_call":
        tool = ev.get("tool") or "?"
        # The rich delegation_* stream supersedes the generic frame-derived
        # jarvis_agent tool card — skip it so the hub shows one delegation, not two.
        if tool == "jarvis_agent" and seg._saw_delegation:
            return
        kind, target = _target_of(tool)
        m = seg.add(
            kind, ts, "",
            {"tool": tool, "target": target, "args": ev.get("args") or "", "status": "running"},
        )
        seg.tools += 1
        seg._pending[tool] = m
    elif etype == "delegation_start":
        deleg_id = ev.get("deleg_id") or ""
        seg._saw_delegation = True
        m = seg.add(
            "delegation", ts, "",
            {"tool": "jarvis_agent", "target": "Agent chain", "task": ev.get("task") or "",
             "deleg_id": deleg_id, "status": "running", "steps": []},
        )
        seg.tools += 1
        seg._pending[f"deleg:{deleg_id}"] = m
    elif etype == "delegation_step":
        if ev.get("phase") == "working":
            return  # transient live heartbeat; not part of the saved step tree
        parent = seg._pending.get(f"deleg:{ev.get('deleg_id')}")
        if parent is not None:
            parent["meta"]["steps"].append({
                "brain": ev.get("brain"), "phase": ev.get("phase"),
                "detail": ev.get("detail") or "", "ok": ev.get("ok"),
                "latency_ms": ev.get("latency_ms"), "tokens": ev.get("tokens"),
            })
    elif etype == "delegation_end":
        parent = seg._pending.pop(f"deleg:{ev.get('deleg_id')}", None)
        if parent is not None:
            ok = bool(ev.get("ok"))
            brain = ev.get("brain")
            parent["meta"].update({
                "status": "ok" if ok else "error", "ok": ok,
                "brain": brain, "target": (brain or "Agent chain"),
                "result": ev.get("result") or "",
                "failures": ev.get("failures") or [],
                "total_latency_ms": ev.get("total_latency_ms"),
                "total_tokens": ev.get("total_tokens"),
            })
    elif etype == "tool_result":
        tool = ev.get("tool") or "?"
        ok = bool(ev.get("ok"))
        detail = ev.get("detail") or ""
        pending = seg._pending.pop(tool, None)
        if pending:
            pending["meta"].update({"status": "ok" if ok else "error", "ok": ok, "detail": detail})
        else:
            kind, target = _target_of(tool)
            seg.add(kind, ts, "", {"tool": tool, "target": target, "ok": ok, "detail": detail,
                                   "status": "ok" if ok else "error"})
    elif etype == "usage":
        seg.tokens += int(ev.get("total_tokens") or 0)


def _flush(conn: sqlite3.Connection, seg: _Segment) -> bool:
    """Write a finished segment as one conversation. Idempotent: re-ingesting the
    same transcripts REPLACES the conversation keyed by its stable start time."""
    if not seg.has_content:
        return False
    conv_id = f"voice:{'local' if seg.source == 'desktop-voice' else 'phone'}:{seg.start}"
    title = seg.title or "(voice session)"
    # Order-independent dedup: when a rich delegation (has "steps") exists, drop
    # the generic frame-derived jarvis_agent tool card, whichever arrived first.
    msgs = [
        m for m in seg.msgs
        if not (seg._saw_delegation and m["meta"].get("tool") == "jarvis_agent"
                and "steps" not in m["meta"])
    ]
    msg_count = sum(1 for m in msgs if m["role"] in ("user", "assistant", "sms"))
    tool_count = sum(1 for m in msgs if m["role"] in ("delegation", "tool"))
    cur = conn.cursor()
    cur.execute("DELETE FROM messages WHERE conv_id=?", (conv_id,))
    if _FTS_OK:
        cur.execute("DELETE FROM msg_fts WHERE conv_id=?", (conv_id,))
    cur.execute(
        """INSERT OR REPLACE INTO conversations
           (id, source, title, started_at, ended_at, msg_count, tool_count, total_tokens, meta)
           VALUES (?,?,?,?,?,?,?,?,?)""",
        (conv_id, seg.source, title, seg.start, seg.last, msg_count, tool_count, seg.tokens, "{}"),
    )
    for i, m in enumerate(msgs):
        cur.execute(
            "INSERT INTO messages (id, conv_id, seq, role, ts, text, meta) VALUES (?,?,?,?,?,?,?)",
            (f"{conv_id}:{i}", conv_id, i, m["role"], m["ts"], m["text"], json.dumps(m["meta"])),
        )
        body = m["text"]
        if not body and m["meta"].get("tool"):
            meta = m["meta"]
            # Index the delegation's task + full result so search_history can
            # find a conversation by what was actually delegated and answered.
            body = " ".join(str(meta.get(k, "")) for k in
                            ("target", "tool", "args", "task", "result")).strip()
        if _FTS_OK and body.strip():
            cur.execute(
                "INSERT INTO msg_fts (conv_id, source, title, body) VALUES (?,?,?,?)",
                (conv_id, seg.source, title, body),
            )
    return True


def ingest_transcripts(
    conn: sqlite3.Connection, transcripts_dir: Path | str = DEFAULT_TRANSCRIPTS
) -> dict:
    """Read every transcripts/*.jsonl and (re)build voice conversations. Returns
    {conversations, files} counts. Safe to run repeatedly (idempotent upsert)."""
    files = sorted(glob.glob(str(Path(transcripts_dir) / "*.jsonl")))
    events: list[tuple[int, dict]] = []
    for fp in files:
        try:
            with open(fp, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        ev = json.loads(line)
                    except Exception:
                        continue
                    events.append((_ts_ms(ev.get("ts", "")), ev))
        except OSError:
            continue
    events.sort(key=lambda x: x[0])

    open_segs: dict[str, _Segment | None] = {}
    written = 0
    for ts, ev in events:
        src = ev.get("src") or "local"
        source = SOURCE_BY_SRC.get(src, "desktop-voice")
        etype = ev.get("type")
        seg = open_segs.get(source)

        # A long gap of silence on this surface closes the current session.
        if seg is not None and ts - seg.last > SESSION_GAP_MS:
            written += 1 if _flush(conn, seg) else 0
            open_segs[source] = None
            seg = None

        # Non-content events keep a live session warm but never start one. (status
        # is a UI-connect, not a turn — there are none in the log, but ignore it.)
        if etype in _NONCONTENT_TYPES:
            if seg is not None and ts > 0:
                seg.last = ts
            continue

        # Content event: user/bot transcript, tool_call/result, usage, sms.
        if seg is None:
            if ts <= 0:
                continue
            seg = _Segment(source, ts)
            open_segs[source] = seg
        if ts > 0:
            seg.last = ts
        _apply_event(seg, ev, ts)

    for seg in open_segs.values():
        if seg is not None:
            written += 1 if _flush(conn, seg) else 0
    conn.commit()
    return {"conversations": written, "files": len(files)}


# --------------------------------------------------------------------------- #
# Typed-chat sync (called by the OpenJarvis server)
# --------------------------------------------------------------------------- #
def upsert_typed_conversation(conn: sqlite3.Connection, conv: dict) -> str:
    """Upsert one OpenJarvis typed-chat conversation. `conv` matches the frontend
    Conversation shape: {id, title, createdAt, updatedAt, messages:[{role,content,
    timestamp,usage,toolCalls}]}. Idempotent on the frontend id."""
    conv_id = f"typed:{conv['id']}"
    msgs = conv.get("messages") or []
    started = int(conv.get("createdAt") or (msgs[0].get("timestamp") if msgs else 0) or 0)
    ended = int(conv.get("updatedAt") or (msgs[-1].get("timestamp") if msgs else started) or started)
    title = (conv.get("title") or "").strip() or "(untitled chat)"
    tokens = sum(int((m.get("usage") or {}).get("total_tokens") or 0) for m in msgs)
    tools = sum(len(m.get("toolCalls") or []) for m in msgs)

    cur = conn.cursor()
    cur.execute("DELETE FROM messages WHERE conv_id=?", (conv_id,))
    if _FTS_OK:
        cur.execute("DELETE FROM msg_fts WHERE conv_id=?", (conv_id,))
    cur.execute(
        """INSERT OR REPLACE INTO conversations
           (id, source, title, started_at, ended_at, msg_count, tool_count, total_tokens, meta)
           VALUES (?,?,?,?,?,?,?,?,?)""",
        (conv_id, "typed-chat", title, started, ended, len(msgs), tools, tokens,
         json.dumps({"model": conv.get("model")})),
    )
    seq = 0
    for m in msgs:
        ts = int(m.get("timestamp") or 0)
        role = m.get("role") or "user"
        text = m.get("content") or ""
        cur.execute(
            "INSERT INTO messages (id, conv_id, seq, role, ts, text, meta) VALUES (?,?,?,?,?,?,?)",
            (f"{conv_id}:{seq}", conv_id, seq, role, ts, text,
             json.dumps({"usage": m.get("usage")} if m.get("usage") else {})),
        )
        if _FTS_OK and text.strip():
            cur.execute("INSERT INTO msg_fts (conv_id, source, title, body) VALUES (?,?,?,?)",
                        (conv_id, "typed-chat", title, text))
        seq += 1
        for tc in m.get("toolCalls") or []:
            tool = tc.get("tool") or "?"
            kind, target = _target_of(tool)
            cur.execute(
                "INSERT INTO messages (id, conv_id, seq, role, ts, text, meta) VALUES (?,?,?,?,?,?,?)",
                (f"{conv_id}:{seq}", conv_id, seq, kind, ts, "",
                 json.dumps({"tool": tool, "target": target, "args": tc.get("arguments"),
                             "ok": tc.get("status") == "success", "detail": tc.get("result"),
                             "status": tc.get("status")})),
            )
            seq += 1
    conn.commit()
    return conv_id


# --------------------------------------------------------------------------- #
# Reads (hub UI + search_history recall tool)
# --------------------------------------------------------------------------- #
def list_conversations(conn, source: str | None = None, limit: int = 100, offset: int = 0) -> list[dict]:
    q = "SELECT * FROM conversations"
    args: list = []
    if source:
        q += " WHERE source=?"
        args.append(source)
    q += " ORDER BY started_at DESC LIMIT ? OFFSET ?"
    args += [limit, offset]
    return [dict(r) for r in conn.execute(q, args).fetchall()]


def get_conversation(conn, conv_id: str) -> dict | None:
    row = conn.execute("SELECT * FROM conversations WHERE id=?", (conv_id,)).fetchone()
    if not row:
        return None
    msgs = conn.execute(
        "SELECT seq, role, ts, text, meta FROM messages WHERE conv_id=? ORDER BY seq", (conv_id,)
    ).fetchall()
    out = dict(row)
    out["messages"] = [
        {"seq": m["seq"], "role": m["role"], "ts": m["ts"], "text": m["text"],
         "meta": json.loads(m["meta"] or "{}")}
        for m in msgs
    ]
    return out


def search(conn, query: str, limit: int = 20) -> list[dict]:
    """Full-text search across all conversations; returns conversation summaries
    (most relevant first) with a matching snippet."""
    if not query.strip():
        return []
    rows = []
    if _FTS_OK:
        try:
            rows = conn.execute(
                """SELECT conv_id, source, title,
                          snippet(msg_fts, 3, '[', ']', ' … ', 12) AS snippet,
                          bm25(msg_fts) AS rank
                   FROM msg_fts WHERE msg_fts MATCH ? ORDER BY rank LIMIT ?""",
                (query, limit * 4),
            ).fetchall()
        except sqlite3.OperationalError:
            rows = []  # FTS MATCH syntax error on raw user text — fall through to LIKE
    if not rows:
        like = f"%{query}%"
        rows = conn.execute(
            """SELECT conv_id, '' AS source, '' AS title, substr(text,1,120) AS snippet, 0 AS rank
               FROM messages WHERE text LIKE ? ORDER BY ts DESC LIMIT ?""",
            (like, limit * 4),
        ).fetchall()
    seen: dict[str, dict] = {}
    for r in rows:
        cid = r["conv_id"]
        if cid in seen:
            continue
        conv = conn.execute("SELECT * FROM conversations WHERE id=?", (cid,)).fetchone()
        if conv:
            d = dict(conv)
            d["snippet"] = r["snippet"]
            seen[cid] = d
        if len(seen) >= limit:
            break
    return list(seen.values())


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _fmt_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M") if ms else "?"


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "ingest"
    conn = connect()
    if cmd == "ingest":
        stats = ingest_transcripts(conn)
        print(f"ingested {stats['conversations']} conversations from {stats['files']} transcript files")
    elif cmd == "list":
        for c in list_conversations(conn, limit=40):
            print(f"  [{c['source']:<13}] {_fmt_time(c['started_at'])}  "
                  f"({c['msg_count']}msg {c['tool_count']}tool)  {c['title']}")
    elif cmd == "search":
        query = " ".join(argv[2:]) or ""
        hits = search(conn, query)
        print(f"{len(hits)} match(es) for {query!r}:")
        for c in hits:
            print(f"  [{c['source']:<13}] {_fmt_time(c['started_at'])}  {c['title']}")
            print(f"      … {c.get('snippet','')}")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
