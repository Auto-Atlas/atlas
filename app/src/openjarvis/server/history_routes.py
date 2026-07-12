"""Conversation hub endpoints — the "second brain" history surface.

Serves the central conversation archive (the SQLite DB at ~/.openjarvis/history.db
built by the voice sidecar's conversation_archive.py) to the OpenJarvis UI, and
accepts typed-chat conversations to sync into it. Read-mostly; the heavy lifting
(schema, voice ingestion, search) lives in the shared archive module so there is
ONE source of truth.

The archive module lives in the voice sidecar repo (it owns the transcripts). We
import it by adding JARVIS_SIDECAR_DIR to sys.path — both run on the same machine.
If the sidecar isn't present, the endpoints degrade to "empty history" instead of
crashing the server.
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

history_router = APIRouter(prefix="/v1/history", tags=["history"])

# Where the sidecar (and its conversation_archive.py + transcripts/) lives.
# In this repo the sidecar layer IS the repo root (app/ is nested inside it);
# for pip installs fall back to the installer's default checkout dir.
def _default_sidecar() -> str:
    repo_root = Path(__file__).resolve().parents[4]
    if (repo_root / "conversation_archive.py").exists():
        return str(repo_root)
    return str(Path.home() / "atlas")


_DEFAULT_SIDECAR = _default_sidecar()
# Don't re-ingest the voice transcripts more than this often (cheap, but pointless).
_INGEST_THROTTLE_S = 20.0
_last_ingest = 0.0


def _archive():
    """Import the shared archive module, or None if the sidecar isn't reachable."""
    sidecar = os.getenv("JARVIS_SIDECAR_DIR", _DEFAULT_SIDECAR)
    if sidecar and Path(sidecar, "conversation_archive.py").is_file() and sidecar not in sys.path:
        sys.path.insert(0, sidecar)
    try:
        import conversation_archive as ca  # noqa: WPS433 (intentional dynamic import)

        return ca
    except Exception:
        return None


def _connect(ca):
    return ca.connect()


def _refresh(ca, conn) -> None:
    """Pull the latest voice transcripts into the archive, throttled."""
    global _last_ingest
    now = time.monotonic()
    if now - _last_ingest < _INGEST_THROTTLE_S:
        return
    try:
        ca.ingest_transcripts(conn)
    except Exception:
        pass
    _last_ingest = now


@history_router.get("/conversations")
def list_history(source: Optional[str] = None, limit: int = 100, offset: int = 0):
    """All conversations across surfaces, newest first. Optional source filter
    (phone-voice / desktop-voice / typed-chat)."""
    ca = _archive()
    if ca is None:
        return {"conversations": [], "note": "archive unavailable (sidecar not found)"}
    conn = _connect(ca)
    try:
        _refresh(ca, conn)
        rows = ca.list_conversations(conn, source=source, limit=min(limit, 500), offset=offset)
        return {"conversations": rows}
    finally:
        conn.close()


@history_router.get("/search")
def search_history_endpoint(q: str, limit: int = 20):
    """Full-text search across every saved conversation."""
    ca = _archive()
    if ca is None:
        return {"results": []}
    conn = _connect(ca)
    try:
        _refresh(ca, conn)
        return {"results": ca.search(conn, q, limit=min(limit, 100))}
    finally:
        conn.close()


@history_router.get("/conversations/{conv_id:path}")
def get_history(conv_id: str):
    """One conversation with its full message + delegation/tool timeline."""
    ca = _archive()
    if ca is None:
        raise HTTPException(status_code=503, detail="conversation archive unavailable")
    conn = _connect(ca)
    try:
        conv = ca.get_conversation(conn, conv_id)
    finally:
        conn.close()
    if not conv:
        raise HTTPException(status_code=404, detail="conversation not found")
    return conv


class SyncConversationRequest(BaseModel):
    id: str
    title: Optional[str] = None
    createdAt: Optional[int] = None
    updatedAt: Optional[int] = None
    model: Optional[str] = None
    messages: list[dict[str, Any]] = []


@history_router.post("/sync")
def sync_typed_conversation(req: SyncConversationRequest, request: Request):
    """Upsert one OpenJarvis typed-chat conversation into the archive so it shows
    up in the hub alongside the voice conversations. Called fire-and-forget by the
    frontend whenever a chat changes."""
    ca = _archive()
    if ca is None:
        return {"ok": False, "note": "archive unavailable"}
    conn = _connect(ca)
    try:
        conv_id = ca.upsert_typed_conversation(conn, req.model_dump())
        return {"ok": True, "id": conv_id}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
    finally:
        conn.close()


@history_router.post("/traces/ingest-voice")
def ingest_voice_traces(request: Request, limit: int = 200):
    """Project voice jarvis_agent delegations from the archive into the existing
    TraceStore, so the /v1/traces UI shows them next to managed-agent traces.
    Decoupled: the live voice loop never imports the OpenJarvis package — this
    runs server-side, on demand, reading the archive the sidecar already wrote."""
    import json as _json

    ca = _archive()
    if ca is None:
        return {"ok": False, "ingested": 0, "note": "archive unavailable"}
    store = getattr(request.app.state, "trace_store", None)
    try:
        from openjarvis.core.types import StepType, Trace, TraceStep

        if store is None:
            from openjarvis.core.config import DEFAULT_CONFIG_DIR
            from openjarvis.traces.store import TraceStore

            store = TraceStore(str(DEFAULT_CONFIG_DIR / "traces.db"))
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"trace store unavailable: {exc}")

    conn = _connect(ca)
    ingested = 0
    try:
        _refresh(ca, conn)
        rows = conn.execute(
            "SELECT conv_id, ts, meta FROM messages WHERE role='delegation' "
            "ORDER BY ts DESC LIMIT ?",
            (limit,),
        ).fetchall()
        for r in rows:
            meta = _json.loads(r["meta"] or "{}")
            steps_meta = meta.get("steps") or []
            if not steps_meta:
                continue
            t0 = (r["ts"] or 0) / 1000.0
            steps = [
                TraceStep(
                    step_type=StepType.GENERATE if s.get("phase") == "answer" else StepType.ROUTE,
                    timestamp=t0,
                    duration_seconds=(s.get("latency_ms") or 0) / 1000.0,
                    input={"brain": s.get("brain"), "phase": s.get("phase")},
                    output={"detail": s.get("detail"), "ok": s.get("ok")},
                )
                for s in steps_meta
            ]
            deleg_id = meta.get("deleg_id") or f"{r['conv_id']}:{r['ts']}"
            total_s = (meta.get("total_latency_ms") or 0) / 1000.0
            trace = Trace(
                trace_id=f"voice-{deleg_id}",
                query=meta.get("task", ""),
                agent="jarvis_agent",
                model=meta.get("brain", "") or "",
                engine=meta.get("brain", "") or "",
                steps=steps,
                result=meta.get("result", ""),
                outcome="success" if meta.get("ok") else "failure",
                started_at=t0,
                ended_at=t0 + total_s,
                total_tokens=int(meta.get("total_tokens") or 0),
                total_latency_seconds=total_s,
                metadata={"source": "voice-delegation", "conv_id": r["conv_id"]},
            )
            try:
                store.save(trace)
                ingested += 1
            except Exception:
                # Already projected (trace_id UNIQUE) or transient — skip.
                pass
        return {"ok": True, "ingested": ingested}
    finally:
        conn.close()
