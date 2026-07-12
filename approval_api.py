# approval_api.py
#
# The sidecar's remote-control + approval surface for the EVE native app (spec §1).
# A self-contained FastAPI app (REST + WebSocket) that runs as its OWN process beside
# jarvis_core — NOT inside bot.py/phone_bot.py — and is exposed to the tailnet via
#   tailscale serve --bg --https=8443 http://127.0.0.1:8799   (:8443 taken? any free HTTPS port works)
#
# Security spine (spec §5): the client can only REQUEST. It sends an approval `id`; the
# sidecar atomically consumes the frozen staged draft (single-fire, TTL-bounded) and runs
# the REAL handler headless via release.py. A forged/compromised client with a valid token
# can at most approve a draft EVE already staged for a known speaker — never synthesize a
# new create_invoice/send_to_channel. Auth is fail-closed: blank token -> refuse to start.
#
# Import invariant (BMAD): NO import of jarvis_core / bot / phone_bot / speaker_state.
#
import asyncio
import datetime as _dt
import hmac
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import (
    Depends,
    FastAPI,
    Header,
    HTTPException,
    Query,
    Request,
    Response,
    WebSocket,
    WebSocketDisconnect,
)
from loguru import logger
from pydantic import BaseModel

# Load .env BEFORE the local imports below — memory_tool reads JARVIS_MEMORY_PAGE at import, so
# the API must see the same config as the voice loop (else /v1/memory reads a different file than
# the bot writes). override=False: a test's monkeypatched env always wins over .env.
load_dotenv()

import atlas_env

atlas_env.apply_aliases()  # ATLAS_* public names fan into EVE_*/JARVIS_*

import agent_tasks
import approval_store
import memory_tool
import persona
import push_registry
import release
import skill_feed
import skill_loader
import health_store
import speech_oneshot
import transcript_review


# ---- Auth (fail-closed) -----------------------------------------------------
def _resolve_app_token() -> str:
    """Resolve the app token: env EVE_APP_TOKEN, else the contents of approval_token.txt.
    Blank -> the service refuses to start (never an open door). Called at server STARTUP
    (in _lifespan), not at import — so importing this module for tests/lint/reload doesn't
    require the secret to be present, while a running server still fails closed without it."""
    token = os.getenv("EVE_APP_TOKEN", "").strip()
    if not token:
        token_file = Path(os.getenv("EVE_APP_TOKEN_FILE", "approval_token.txt"))
        if token_file.is_file():
            token = token_file.read_text(encoding="utf-8").strip()
    if not token:
        raise RuntimeError(
            "EVE_APP_TOKEN is not set (and approval_token.txt is absent/empty). "
            "The approval API refuses to start without an app token — fail-closed."
        )
    return token


# Resolved lazily at first use / server startup (NOT at import — see _resolve_app_token).
_APP_TOKEN: str | None = None


def _ensure_app_token() -> str | None:
    """Return the resolved token, resolving once on first use. Fail-CLOSED: if no token is
    configured this returns None (every auth check then denies) rather than raising into a
    request handler. _lifespan additionally resolves at boot so a misconfigured server dies
    at startup instead of silently denying every request."""
    global _APP_TOKEN
    if _APP_TOKEN is None:
        try:
            _APP_TOKEN = _resolve_app_token()
        except RuntimeError:
            return None
    return _APP_TOKEN


def _check(token: str | None) -> bool:
    expected = _ensure_app_token()
    if not token or not expected:
        return False
    return hmac.compare_digest(token, expected)


async def require_token(authorization: str | None = Header(default=None)) -> None:
    scheme, _, value = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not _check(value.strip()):
        raise HTTPException(status_code=401, detail="invalid or missing app token")


# ---- Live event stream (foreground only; spec §2.2) -------------------------
# Surface-aware hub (goal 2026-07-05-glasses-integration): every /v1/stream client
# carries a SURFACE label (phone | glasses) so a capture_frame can be aimed at ONE
# camera when the user names it ("look through my glasses"). The label is NOT a
# secret — the token still rides the subprotocol header; surface only routes events.
# broadcast() stays surface-blind (all callers keep working); only count() and the
# vision/visual endpoints care which camera is listening.
VALID_SURFACES = ("phone", "glasses")


class _Hub:
    def __init__(self):
        # client -> surface label. A set membership check is still O(1) via keys().
        self._clients: dict[WebSocket, str] = {}

    async def join(self, ws: WebSocket, surface: str = "phone"):
        self._clients[ws] = surface

    def leave(self, ws: WebSocket):
        self._clients.pop(ws, None)

    def count(self, surfaces: set | None = None) -> int:
        """How many clients match `surfaces` (None => all). Used to tell the look
        tool whether ANY matching camera is live before it waits for a frame."""
        if surfaces is None:
            return len(self._clients)
        return sum(1 for s in self._clients.values() if s in surfaces)

    def surface_counts(self) -> dict:
        """Per-surface live counts, e.g. {"phone": 1, "glasses": 0} — additive
        detail for callers that want to name which camera is (not) connected."""
        counts = {s: 0 for s in VALID_SURFACES}
        for s in self._clients.values():
            counts[s] = counts.get(s, 0) + 1
        return counts

    async def broadcast(self, event: dict):
        dead = []
        for ws in list(self._clients):
            try:
                await ws.send_json(event)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.leave(ws)


hub = _Hub()


# ---- Live tool/delegation feed (republished onto the hub) -------------------
# The native app's transformative tool-call UI needs the same delegation/tool
# events that power the desktop + phone-web NeuralBrain. Those are produced by the
# voice loop's MetricsBridge (bot.py/phone_bot.py, ports 8765/8766) — a process this
# one must NOT import (the BMAD import invariant above). The bridge already appends
# every such event to a per-day JSONL transcript; we cross the process boundary
# through that real artifact: tail the file and republish the live event types onto
# the hub the app's /v1/stream is already subscribed to. Approval events keep flowing
# exactly as before — this only ADDS event types.
_LIVE_FORWARD_TYPES = {
    "tool_call",
    "tool_result",
    "thinking",
}

# Delegation activity is owner-global task state, not surface chatter: agent talk-back
# events AND jarvis_agent brain traces (claude code over ACP / codex / glm — the app's
# "see what it's doing" feed) arrive in the DESKTOP voice loop (src "local"), so the
# phone-only src filter below must not apply to them. Everything in _LIVE_FORWARD_TYPES
# keeps the src gate (the desktop tool-leak regression it exists for).
_AGENT_EVENT_TYPES = {
    "agent_progress",
    "agent_question",
    "agent_result",
    "agent_blocker",
    "agent_task_assigned",
    "agent_task_cancelled",
    "agent_task_redirected",
    "delegation_start",
    "delegation_step",
    "delegation_end",
}

# Both voice loops (desktop bot.py = src "local", phone_bot.py = src "phone") append to the SAME
# daily transcript. The native app is the PHONE surface, so it must only see the phone session's
# events — otherwise the always-on desktop loop's denials/tools leak onto the phone (e.g. a
# desktop "unknown -> denied" check_email showing as FAILED next to the phone's real success).
_FORWARD_SRC = os.getenv("EVE_FORWARD_SRC", "phone")


def _transcript_dir() -> Path:
    """Same resolution as bridge.TranscriptLogger so we tail the file the voice loop
    writes: JARVIS_LOG_DIR if set, else <repo>/transcripts."""
    return Path(os.getenv("JARVIS_LOG_DIR", str(Path(__file__).parent / "transcripts")))


async def _forward_live_events(target: _Hub, poll_interval: float = 0.2) -> None:
    """Tail today's JSONL transcript and republish live tool/delegation events onto
    `target`. Starts at end-of-file (no replay of the day's history), follows day
    rollover, and recovers from truncation/rotation. Best-effort and fully isolated:
    any failure backs off and retries — it never raises into the API, and with no app
    connected `hub.broadcast` is a no-op."""
    log_dir = _transcript_dir()
    cur_date: str | None = None
    attached = False  # have we ever skipped to EOF? (do it once, on the first file we open)
    fh = None
    try:
        while True:
            try:
                today = _dt.datetime.now().strftime("%Y-%m-%d")
                path = log_dir / f"{today}.jsonl"

                # (Re)open on first run or day rollover.
                if cur_date != today or fh is None:
                    if fh is not None:
                        fh.close()
                        fh = None
                    if path.is_file():
                        fh = open(path, "r", encoding="utf-8")
                        # First file we ever attach to: jump to EOF so we forward only NEW events
                        # (never replay the day's history, even if the file appeared after we
                        # started). A day-rollover file is fresh, so we read it from the start.
                        if not attached:
                            fh.seek(0, os.SEEK_END)
                            attached = True
                        cur_date = today
                    else:
                        # Voice loop hasn't written today's file yet — wait for it.
                        cur_date = today
                        await asyncio.sleep(poll_interval)
                        continue

                pos = fh.tell()
                line = fh.readline()
                if not line:
                    # At EOF: detect external truncation/replacement, then wait for more.
                    try:
                        if path.is_file() and path.stat().st_size < fh.tell():
                            fh.seek(0)
                    except OSError:
                        pass
                    await asyncio.sleep(poll_interval)
                    continue

                # A line without a trailing newline is a partial write mid-append: readline()
                # has already advanced past it, so we'd lose the event if we parsed-and-dropped.
                # Rewind to where this read began and wait for the writer to finish the line.
                if not line.endswith("\n"):
                    fh.seek(pos)
                    await asyncio.sleep(poll_interval)
                    continue

                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except Exception:
                    continue  # malformed complete line — skip it
                if isinstance(event, dict) and (
                    event.get("type") in _AGENT_EVENT_TYPES
                    or (
                        event.get("type") in _LIVE_FORWARD_TYPES
                        and event.get("src") == _FORWARD_SRC
                    )
                ):
                    await target.broadcast(event)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # never let the forwarder take down the API
                logger.debug(f"live-event forwarder hiccup: {e}")
                await asyncio.sleep(1.0)
    finally:
        if fh is not None:
            try:
                fh.close()
            except Exception:
                pass


# ---- Models -----------------------------------------------------------------
class MemoryAdd(BaseModel):
    speaker: str | None = None  # omitted -> owner page (the owner's real memory)
    fact: str


class SettingsUpdate(BaseModel):
    # All optional / apply-if-present: the app may toggle any independently.
    remote_approval_enabled: bool | None = None
    thinking_enabled: bool | None = None
    barge_in_enabled: bool | None = None
    silence_mode_enabled: bool | None = None  # "quiet unless I say the wake word" (silence_mode)
    voice_brain: str | None = None  # active LLM brain profile (gpu-box/zai/local-glm/ollama/...)


# ---- App --------------------------------------------------------------------
@asynccontextmanager
async def _lifespan(app: FastAPI):
    # Fail closed at startup: resolve the app token now (not at import) so the running
    # server never serves without one, while imports for tests/lint/reload stay cheap.
    global _APP_TOKEN
    _APP_TOKEN = _resolve_app_token()
    orphans = await asyncio.to_thread(approval_store.list_releasing)
    if orphans:
        logger.warning(
            f"approval_api: {len(orphans)} releasing orphan(s) from a prior crash — "
            f"surfaced as outcome-unverified via /v1/health, never re-fired"
        )
    # Warm the watch-voice STT model off-thread so the first wrist turn isn't paying the
    # model load; startup itself must not block on it (and a load failure surfaces on the
    # first /v1/voice/turn as a loud 502, not a dead server).
    def _warm_speech():
        try:
            speech_oneshot.warm()
        except Exception as e:
            logger.warning(f"approval_api: speech warm-up failed (first voice turn will retry): {e!r}")

    asyncio.get_running_loop().run_in_executor(None, _warm_speech)
    # Republish the voice loop's live tool/delegation events onto the hub so the
    # native app's /v1/stream carries the transformative tool-call feed.
    forwarder = asyncio.create_task(_forward_live_events(hub))
    try:
        yield
    finally:
        forwarder.cancel()
        try:
            await forwarder
        except (asyncio.CancelledError, Exception):
            pass


app = FastAPI(title=f"{persona.ASSISTANT_NAME} approval API", version="1.0", lifespan=_lifespan)

# Skill catalog for the app's "Skills" surface — the same skills/*.md EVE itself is fed via
# skill_loader. Loaded LIVE per request so a skill added to skills/*.md on the sidecar shows up
# in the app without a restart (Skills-in-app spec §2). Read-only.
@app.get("/v1/skills", dependencies=[Depends(require_token)])
async def list_skills():
    """The user-facing skill catalog: what EVE can do, with its risk + whether it confirms."""
    skills = await asyncio.to_thread(skill_loader.load_skills)
    return {
        "skills": [
            {
                "tool": s.tool,
                "catalog": s.catalog,
                "risk": s.risk,
                "requires_confirmation": s.requires_confirmation,
            }
            for s in skills.values()
            if s.catalog  # only skills with a human one-liner (the ones EVE is told about)
        ]
    }


class SkillFeedRequest(BaseModel):
    mode: str


@app.post("/v1/skills/{tool}/feed", dependencies=[Depends(require_token)])
async def feed_skill(tool: str, body: SkillFeedRequest):
    """Queue a skill to be fed into EVE — 'live' (current voice session) or 'next' (primed for
    the next session). The api can only enqueue; the voice process consumes (import invariant)."""
    if body.mode not in ("live", "next"):
        raise HTTPException(status_code=400, detail="mode must be 'live' or 'next'")
    skills = await asyncio.to_thread(skill_loader.load_skills)
    skill = skills.get(tool)
    if skill is None:
        raise HTTPException(status_code=404, detail="no such skill")
    ttl_s = 300 if body.mode == "live" else 86400
    feed_id = await asyncio.to_thread(
        skill_feed.enqueue, tool, body.mode, skill.body, ttl_s
    )
    await hub.broadcast({"type": "skill_fed", "tool": tool, "mode": body.mode})
    return {"ok": True, "tool": tool, "mode": body.mode, "id": feed_id}


@app.get("/v1/skills/feed", dependencies=[Depends(require_token)])
async def list_skill_feed():
    """Pending feeds, so the app can show durable 'Primed for next chat' / live status."""
    pending = await asyncio.to_thread(skill_feed.list_pending)
    return {
        "pending": [
            {
                "tool": p["tool"],
                "mode": p["mode"],
                "status": p["effective_status"],
                "seconds_left": p["seconds_left"],
            }
            for p in pending
        ]
    }


@app.delete("/v1/skills/feed/{tool}", dependencies=[Depends(require_token)])
async def clear_skill_feed(tool: str):
    """Un-prime: cancel a pending feed for a skill."""
    cleared = await asyncio.to_thread(skill_feed.clear_pending, tool)
    return {"ok": True, "cleared": cleared}


# ---- Phone camera vision (look_via_phone) ----------------------------------
# The voice loop can't reach the app directly; it POSTs /v1/vision/request here
# (loopback, same bearer) and we broadcast a capture_frame event on the hub the
# app already streams. The app answers with /v1/vision/frame; the JPEG crosses
# back to the voice loop through the vision_frames spool (transient by contract).
class VisionRequest(BaseModel):
    request_id: str
    prompt: str = ""
    # Which camera to ask: "any" (broadcast to all), or a named surface the user
    # called out ("phone" | "glasses"). Clients ignore capture_frame events whose
    # source names a DIFFERENT surface (contract: docs/glasses-endpoint-contract.md).
    source: str = "any"


class VisionFrame(BaseModel):
    request_id: str
    jpeg_b64: str


_VISION_MAX_BYTES = 8 * 1024 * 1024  # a phone JPEG snapshot, generously


@app.post("/v1/vision/request", dependencies=[Depends(require_token)])
async def vision_request(req: VisionRequest):
    import vision_frames
    if not vision_frames.valid_id(req.request_id):
        raise HTTPException(status_code=400, detail="request_id must be plain lowercase hex")
    if req.source not in ("any",) + VALID_SURFACES:
        raise HTTPException(status_code=400,
                            detail=f"source must be one of any|{'|'.join(VALID_SURFACES)}")
    # Broadcast to EVERY client (the hub is surface-blind); clients whose surface
    # differs from a named source are contractually expected to ignore the event.
    await hub.broadcast({"type": "capture_frame", "request_id": req.request_id,
                         "prompt": req.prompt[:500], "source": req.source})
    # listeners counts ONLY the cameras that will actually answer (any => all), so the
    # tool fails FAST with the RIGHT leg named instead of a 25s wait for a camera that
    # was never connected.
    want = None if req.source == "any" else {req.source}
    return {"ok": True, "listeners": hub.count(want)}


@app.post("/v1/vision/frame", dependencies=[Depends(require_token)])
async def vision_frame(req: VisionFrame):
    import base64

    import vision_frames
    if not vision_frames.valid_id(req.request_id):
        raise HTTPException(status_code=400, detail="request_id must be plain lowercase hex")
    try:
        data = base64.b64decode(req.jpeg_b64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="jpeg_b64 is not valid base64")
    if not data:
        raise HTTPException(status_code=400, detail="empty frame")
    if len(data) > _VISION_MAX_BYTES:
        raise HTTPException(status_code=413, detail="frame too large")
    await asyncio.to_thread(vision_frames.save, req.request_id, data)
    return {"ok": True, "bytes": len(data)}


# ---- Surfaced visuals (surface_visual) --------------------------------------
# The voice loop announces a visual here (loopback, same bearer); we broadcast the
# card event on the hub and the app fetches the image by id. Reads are
# non-consuming (late fetch / reconnect) — visual_store TTL-sweeps the spool.
class VisualAnnounce(BaseModel):
    type: str = "surface_visual"
    kind: str
    title: str = ""
    visual_id: str = ""
    url: str = ""
    text: str = ""


@app.post("/v1/visual/announce", dependencies=[Depends(require_token)])
async def visual_announce(req: VisualAnnounce):
    import visual_store
    if req.visual_id and not visual_store.valid_id(req.visual_id):
        raise HTTPException(status_code=400, detail="visual_id must be plain lowercase hex")
    await hub.broadcast({"type": "surface_visual", "kind": req.kind[:40],
                         "title": req.title[:120], "visual_id": req.visual_id,
                         "url": req.url[:200], "text": req.text[:4000]})
    # "surfaces" is additive per-camera detail; "listeners" stays the total so the
    # existing app contract never breaks.
    return {"ok": True, "listeners": hub.count(), "surfaces": hub.surface_counts()}


@app.get("/v1/visual/{visual_id}", dependencies=[Depends(require_token)])
async def get_visual(visual_id: str):
    import visual_store
    if not visual_store.valid_id(visual_id):
        raise HTTPException(status_code=400, detail="visual_id must be plain lowercase hex")
    data = await asyncio.to_thread(visual_store.read, visual_id)
    if data is None:
        raise HTTPException(status_code=404, detail="visual expired or unknown")
    return Response(content=data, media_type="image/jpeg",
                    headers={"Cache-Control": "private, max-age=3600"})


@app.get("/v1/health", dependencies=[Depends(require_token)])
async def health():
    pending = await asyncio.to_thread(approval_store.list_pending)
    releasing = await asyncio.to_thread(approval_store.list_releasing)
    return {
        "ok": True,
        "service": "eve-approval-api",
        "pending": len(pending),
        "releasing_orphans": len(releasing),
        "remote_approval_enabled": await asyncio.to_thread(_remote_enabled),
        "thinking_enabled": await asyncio.to_thread(_thinking_enabled),
        "barge_in_enabled": await asyncio.to_thread(_barge_in_enabled),
        "silence_mode_enabled": await asyncio.to_thread(_silence_mode_enabled),
    }


@app.get("/v1/approvals", dependencies=[Depends(require_token)])
async def list_approvals(status: str = Query(default="pending")):
    if status != "pending":
        raise HTTPException(status_code=400, detail="only status=pending is supported")
    rows = await asyncio.to_thread(approval_store.list_pending)
    return {"approvals": rows}


@app.get("/v1/approvals/{approval_id}", dependencies=[Depends(require_token)])
async def get_approval(approval_id: str):
    row = await asyncio.to_thread(approval_store.get, approval_id)
    if row is None:
        raise HTTPException(status_code=404, detail="no such approval")
    return row


@app.post("/v1/approvals/{approval_id}/approve", dependencies=[Depends(require_token)])
async def approve(approval_id: str):
    # 1) atomic single-fire consume (pending -> releasing). None == not available.
    row = await asyncio.to_thread(approval_store.consume, approval_id)
    if row is None:
        raise HTTPException(status_code=409, detail="approval not available (consumed, denied, expired, or missing)")
    # 2) server-side tier re-assertion — the remote path bypasses tool_policy._staged, so
    #    the lower-trust-can't-release guard is re-asserted here against the frozen row.
    if row["requester_tier"] != "known" or row["risk_level"] != "high":
        raise HTTPException(status_code=409, detail="approval not releasable (tier/risk mismatch)")
    # 3) run the REAL handler headless with the frozen args.
    result = await release.release(row["tool"], row["args"])
    # 4) releasing -> consumed, record the real result.
    await asyncio.to_thread(approval_store.finish, approval_id, result)
    await hub.broadcast({"type": "approval_resolved", "id": approval_id,
                         "ok": bool(result.get("ok")), "tool": row["tool"]})
    return {"ok": bool(result.get("ok")), "released_tool": row["tool"], "result": result}


@app.post("/v1/approvals/{approval_id}/deny", dependencies=[Depends(require_token)])
async def deny(approval_id: str):
    ok = await asyncio.to_thread(approval_store.deny, approval_id)
    if not ok:
        raise HTTPException(status_code=409, detail="approval not pending (already decided or missing)")
    await hub.broadcast({"type": "approval_resolved", "id": approval_id, "denied": True})
    return {"ok": True, "denied": True}


# ---- Agent tasks (live delegation activity + cancel; live-delegation-approvals) ----
# The app's Approvals screen shows every delegated task an agent is working RIGHT NOW and
# lets the owner stop one. Reads/writes the same agent_tasks store the fabric uses (shared
# approvals.db); the RUNNING agent learns of a cancel at its next check-in through the
# talk-back bridge (a2a_fabric.handle_push response — the cooperative kill signal).

_TASK_PUBLIC_FIELDS = (
    "id", "agent", "task", "summary", "status", "effective_status", "delivery",
    "requester", "created_at", "ttl_s", "seconds_left", "resolved_at", "delivered_at",
    "result", "question", "answer",
)


def _task_public(row: dict) -> dict:
    """App-safe projection: the callback capability and claim fencing token NEVER leave
    the server; the question keeps only its user-facing parts. `capabilities` tells the
    app which controls are real for THIS task so buttons are disabled with a reason, never
    a dead no-op (goal guardrail)."""
    out = {k: row.get(k) for k in _TASK_PUBLIC_FIELDS}
    q = row.get("question") or None
    if q:
        out["question"] = {k: q.get(k) for k in ("qid", "question", "approval_id",
                                                 "asked_at")}
    terminal = row["status"] in (agent_tasks.RESOLVED, agent_tasks.FAILED,
                                 agent_tasks.CANCELLED)
    can_redirect = (not terminal
                    and row["status"] != agent_tasks.CANCEL_REQUESTED
                    and row.get("delivery") == "push")
    if terminal:
        reason = "task already finished"
    elif row["status"] == agent_tasks.CANCEL_REQUESTED:
        reason = "cancel already requested — cancel outranks a steer"
    elif row.get("delivery") != "push":
        reason = (f"{row.get('agent', 'this agent')} runs without a talk-back channel — "
                  "it cannot take instructions mid-run")
    else:
        reason = None
    out["capabilities"] = {"cancel": not terminal,
                           "redirect": can_redirect,
                           "redirect_reason": reason}
    out["redirect_pending"] = bool(row.get("redirect"))
    return out


@app.get("/v1/agent-tasks", dependencies=[Depends(require_token)])
async def list_agent_tasks():
    active = await asyncio.to_thread(agent_tasks.list_active)
    recent = await asyncio.to_thread(agent_tasks.list_recent, 20)
    active_ids = {r["id"] for r in active}
    return {"active": [_task_public(r) for r in active],
            "recent": [_task_public(r) for r in recent if r["id"] not in active_ids]}


@app.post("/v1/agent-tasks/{task_id}/cancel", dependencies=[Depends(require_token)])
async def cancel_agent_task(task_id: str):
    row = await asyncio.to_thread(agent_tasks.get, task_id)
    if row is None:
        raise HTTPException(status_code=404, detail="no such agent task")
    new_status = await asyncio.to_thread(agent_tasks.request_cancel, task_id)
    if new_status is None:
        raise HTTPException(
            status_code=409,
            detail=f"task is not cancellable (status: {row['status']} — already finished, "
                   "failed, cancelled, or its result is landing right now)")
    # A cancelled task's outstanding question card is moot — close it so the app/watch
    # don't offer an answer button for a dead question.
    q = row.get("question") or {}
    if q.get("approval_id"):
        try:
            await asyncio.to_thread(approval_store.deny, q["approval_id"])
        except Exception as e:
            logger.warning(f"cancel {task_id}: closing question card failed: {e!r}")
    if new_status == agent_tasks.CANCELLED:
        detail = "cancelled — the task had not started; nothing was running"
    else:
        detail = (f"cancel requested — {row['agent']} will stop at its next check-in; "
                  "the card flips to Cancelled when the stop is confirmed")
    await hub.broadcast({"type": "agent_task_cancelled", "agent": row["agent"],
                         "task_id": task_id, "cid": task_id,
                         "summary": row.get("summary"), "status": new_status})
    return {"ok": True, "status": new_status, "detail": detail}


class RedirectRequest(BaseModel):
    instructions: str


@app.post("/v1/agent-tasks/{task_id}/redirect", dependencies=[Depends(require_token)])
async def redirect_agent_task(task_id: str, body: RedirectRequest):
    instructions = body.instructions.strip()
    if not instructions:
        raise HTTPException(status_code=400, detail="instructions must not be empty")
    row = await asyncio.to_thread(agent_tasks.get, task_id)
    if row is None:
        raise HTTPException(status_code=404, detail="no such agent task")
    caps = _task_public(row)["capabilities"]
    if not caps["redirect"]:
        raise HTTPException(status_code=409, detail=caps["redirect_reason"])
    ok = await asyncio.to_thread(agent_tasks.set_redirect, task_id, instructions)
    if not ok:
        # The row changed under us (finished/cancelled between read and write) — say so.
        fresh = await asyncio.to_thread(agent_tasks.get, task_id)
        raise HTTPException(status_code=409,
                            detail=f"task is no longer steerable (status: "
                                   f"{(fresh or row)['status']})")
    await hub.broadcast({"type": "agent_task_redirected", "agent": row["agent"],
                         "task_id": task_id, "cid": task_id,
                         "summary": row.get("summary"),
                         "text": instructions[:500], "status": "redirect_pending"})
    return {"ok": True, "status": "redirect_pending",
            "detail": (f"redirect staged — {row['agent']} gets it at its next check-in; "
                       "the feed shows it landing")}


# ---- Settings (the in-app activation front door, spec §1.9) -----------------
def _remote_enabled() -> bool:
    val = approval_store.get_setting("remote_approval_enabled")
    if val is not None:
        return str(val).strip().lower() == "true"
    return os.getenv("EVE_REMOTE_APPROVAL", "disabled").strip().lower() == "enabled"


def _thinking_enabled() -> bool:
    # The manual thinking toggle (Epic T); default OFF (fast). Shared with the voice loop via
    # the settings table — the voice loop reads it per-turn (thinking_state).
    val = approval_store.get_setting("thinking_enabled")
    return str(val).strip().lower() == "true" if val is not None else False


def _barge_in_enabled() -> bool:
    # "Let me interrupt EVE" toggle. Default OFF (speakerphone-safe — her own TTS echo can't
    # barge in on her). The voice loop reads it at session start: ON => allow_interruptions +
    # no half-duplex mic gate (best on a headset / with real AEC). Env JARVIS_PHONE_ALLOW_INTERRUPTIONS
    # is the fallback default when the user hasn't set the toggle.
    val = approval_store.get_setting("barge_in_enabled")
    if val is not None:
        return str(val).strip().lower() == "true"
    return os.getenv("JARVIS_PHONE_ALLOW_INTERRUPTIONS", "0") == "1"


def _silence_mode_enabled() -> bool:
    # "Quiet unless I say the wake word" toggle (silence_mode.py). Default OFF. The voice loop
    # reads it LIVE per-utterance via the silence_mode module's cached snapshot; this reader is
    # the app's view of the same settings-table key so the phone can grow a switch.
    val = approval_store.get_setting("silence_mode_enabled")
    return str(val).strip().lower() == "true" if val is not None else False


def _voice_brain() -> str:
    # Active LLM brain profile — the runtime switch the voice loop reads at session
    # start. Settings table wins; else JARVIS_VOICE_BRAIN env; else 'env' (legacy JARVIS_LLM_*).
    val = approval_store.get_setting("voice_brain")
    return val.strip() if val else os.getenv("JARVIS_VOICE_BRAIN", "env")


@app.get("/v1/settings", dependencies=[Depends(require_token)])
async def get_settings():
    return {
        "remote_approval_enabled": await asyncio.to_thread(_remote_enabled),
        "thinking_enabled": await asyncio.to_thread(_thinking_enabled),
        "barge_in_enabled": await asyncio.to_thread(_barge_in_enabled),
        "silence_mode_enabled": await asyncio.to_thread(_silence_mode_enabled),
        "voice_brain": await asyncio.to_thread(_voice_brain),
    }


@app.post("/v1/settings", dependencies=[Depends(require_token)])
async def set_settings(update: SettingsUpdate):
    if update.remote_approval_enabled is not None:
        await asyncio.to_thread(
            approval_store.set_setting, "remote_approval_enabled",
            "true" if update.remote_approval_enabled else "false",
        )
    if update.thinking_enabled is not None:
        await asyncio.to_thread(
            approval_store.set_setting, "thinking_enabled",
            "true" if update.thinking_enabled else "false",
        )
    if update.barge_in_enabled is not None:
        await asyncio.to_thread(
            approval_store.set_setting, "barge_in_enabled",
            "true" if update.barge_in_enabled else "false",
        )
    if update.silence_mode_enabled is not None:
        await asyncio.to_thread(
            approval_store.set_setting, "silence_mode_enabled",
            "true" if update.silence_mode_enabled else "false",
        )
    if update.voice_brain is not None:
        brain = update.voice_brain.strip()
        # Reject an unknown brain name up front: storing a typo silently runs the
        # legacy 'env' brain while GET /v1/settings reports the typo as active — a
        # control plane that lies. 'env' = the legacy JARVIS_LLM_* implicit profile.
        try:
            import voice_llm
            allowed = set(voice_llm.all_profiles()) | {"env"}
        except Exception:
            allowed = None  # voice_llm unavailable here — skip validation, don't 500
        if allowed is not None and brain not in allowed:
            raise HTTPException(
                status_code=422,
                detail=f"unknown voice_brain '{brain}'; allowed: {sorted(allowed)}",
            )
        await asyncio.to_thread(approval_store.set_setting, "voice_brain", brain)
    return await get_settings()


# ---- Push wake registration (server-initiated morning ritual) -----------------
class PushRegister(BaseModel):
    token: str
    tenant: str = "owner"
    platform: str = "android"
    wake_hour: int = 5
    wake_minute: int = 0
    tz: str = "America/New_York"
    enabled: bool = True
    wake_days: list[int] | None = None  # Mon=0..Sun=6; None preserves the server-set schedule


@app.post("/v1/push/register", dependencies=[Depends(require_token)])
async def push_register(req: PushRegister):
    """The phone posts its FCM token + desired wake time here; the wake scheduler reads it
    to fire the morning ritual server-side (so it works even if the app was killed)."""
    try:
        rec = await asyncio.to_thread(
            push_registry.register, req.token, tenant=req.tenant, platform=req.platform,
            wake_hour=req.wake_hour, wake_minute=req.wake_minute, tz=req.tz, enabled=req.enabled,
            wake_days=req.wake_days,
        )
        return {"ok": True, "wake": f"{rec['wake_hour']:02d}:{rec['wake_minute']:02d} {rec['tz']}"}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/v1/wake/audio", dependencies=[Depends(require_token)])
async def wake_audio_endpoint(
    if_none_match: str | None = Header(default=None, alias="If-None-Match"),
):
    """The 5 AM wake rendered in EVE's real voice (Kokoro) as a WAV. The phone downloads
    + caches this and PLAYS IT LOCALLY at wake time — no voice connection needed. Supports
    conditional GET: pass the ETag in If-None-Match and get 304 when nothing changed, so
    the phone only re-downloads when the whys change."""
    import rituals
    import wake_audio

    text = await asyncio.to_thread(rituals.wake_text, None, persona.USER_NICK)
    if not text:
        raise HTTPException(status_code=404, detail="no whys configured for this tenant")
    tag = await asyncio.to_thread(wake_audio.etag, text)
    if if_none_match and if_none_match.strip('"') == tag:
        return Response(status_code=304, headers={"ETag": tag})
    wav, tag = await asyncio.to_thread(wake_audio.get_wake_wav, text)
    return Response(
        content=wav,
        media_type="audio/wav",
        headers={"ETag": tag, "Cache-Control": "no-cache"},
    )


# ---- Onboarding: in-app voice enrollment + identity (no CLI, no config files) ---
class EnrollRequest(BaseModel):
    name: str
    tier: str = "owner"
    clips_b64: list[str]  # base64 WAV clips the user read aloud (live mic domain)


@app.post("/v1/enroll", dependencies=[Depends(require_token)])
async def enroll(req: EnrollRequest):
    """Enroll a speaker from clips the app recorded through the real mic. Uses the
    live-capture averaging path (best audio-domain match), so recognition is solid —
    no studio-sample drift. Writes the voiceprint (owner by default)."""
    import base64

    import enroll_speaker
    import speaker_id

    name = (req.name or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="name required")
    if req.tier not in ("owner", "known", "kid"):
        raise HTTPException(status_code=400, detail="tier must be owner|known|kid")
    try:
        clips = [base64.b64decode(c) for c in (req.clips_b64 or []) if c]
    except Exception:
        raise HTTPException(status_code=400, detail="clips_b64 must be base64 WAV")
    if not clips:
        raise HTTPException(status_code=400, detail="no audio clips")

    def _do():
        emb = speaker_id.embed_profile_files(clips)
        enroll_speaker.upsert_profile(enroll_speaker._default_path(), name, req.tier, emb.tolist())

    try:
        await asyncio.to_thread(_do)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"enrollment failed: {e}")
    return {"ok": True, "name": name, "tier": req.tier, "clips": len(clips)}


class IdentityRequest(BaseModel):
    user: str | None = None       # the owner's name (how EVE refers to them)
    nick: str | None = None       # short form she addresses them by (defaults to first name)
    whys: list[str] | None = None  # the reasons recited in the 5 AM wake


@app.post("/v1/identity", dependencies=[Depends(require_token)])
async def set_identity(req: IdentityRequest):
    """Write the owner's name/nick/whys to their per-tenant config (the life dashboard) —
    the onboarding wizard's 'what should I call you' + 'what gets you up' steps. Merges
    (only overwrites provided fields). The committed dashboard stays a neutral template;
    this writes the gitignored personal file pointed at by EVE_LIFE_DASHBOARD."""
    from pathlib import Path

    # Default is the GITIGNORED personal file — never the tracked template
    # (life_dashboard.json), or onboarding would stamp personal data into git.
    p = Path(os.getenv("EVE_LIFE_DASHBOARD",
                       str(Path(__file__).parent / "life_dashboard.local.json")))

    def _do():
        try:
            d = json.loads(p.read_text(encoding="utf-8")) if p.is_file() else {}
        except Exception:
            d = {}
        if req.user is not None:
            d["user"] = req.user.strip()
        if req.nick is not None:
            d["nick"] = req.nick.strip()
        if req.whys is not None:
            d["whys"] = [w.strip() for w in req.whys if w and w.strip()]
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(d, indent=2, ensure_ascii=False), encoding="utf-8")
        return d

    d = await asyncio.to_thread(_do)
    return {"ok": True, "user": d.get("user"), "nick": d.get("nick"), "whys": len(d.get("whys") or [])}


# ---- Today (phone 'Today' tab: the persistent morning ritual + action items) --
@app.get("/v1/today", dependencies=[Depends(require_token)])
async def get_today():
    """Whys + goals + today's strategy + checkable action items for the Today tab.
    Read-only; reads life_dashboard.json and the dated strategy note on a thread
    so the event loop never blocks on file I/O."""
    import rituals

    return await asyncio.to_thread(rituals.today_payload)


# ---- Canonical OpenJarvis (:8000) proxy -------------------------------------
# Phase 3 sync: the phone keeps ONE base URL + ONE token (EVE_APP_TOKEN); the
# sidecar holds OPENJARVIS_API_KEY and forwards READS to the canonical brain over
# HTTP (never opening its SQLite — honors "OJ :8000 is the sole writer"). Every
# proxy degrades gracefully: if OJ is down, the phone gets desktop_online=false
# instead of an error, so a tab can show "desktop offline" and keep working.
_OJ_BASE = os.getenv("OPENJARVIS_BASE", "http://127.0.0.1:8000")


async def _oj_get(path: str, params: dict | None = None, timeout: float = 4.0):
    """GET from canonical OpenJarvis. Returns (online: bool, json|None). Any
    failure (down, timeout, auth, non-200) -> (False, None) — never raises."""
    key = os.getenv("OPENJARVIS_API_KEY", "")
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    try:
        import httpx

        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.get(f"{_OJ_BASE}{path}", params=params, headers=headers)
        if resp.status_code == 200:
            return True, resp.json()
        logger.debug(f"OJ proxy {path} -> {resp.status_code}")
    except Exception as e:
        logger.debug(f"OJ proxy {path} unavailable: {e!r}")
    return False, None


@app.get("/v1/activity/feed", dependencies=[Depends(require_token)])
async def activity_feed(limit: int = 25):
    """Rich activity: the canonical conversation timeline (what EVE actually did —
    delegations + tool calls), proxied from OJ. desktop_online=false when OJ is
    down so the phone can fall back to the live /v1/stream + the local digest."""
    online, data = await _oj_get("/v1/history/conversations", params={"limit": limit})
    convos = (data or {}).get("conversations", data) if online else []
    return {"desktop_online": online, "source": "openjarvis" if online else "offline",
            "conversations": convos or []}


@app.get("/v1/activity/feed/{conv_id}", dependencies=[Depends(require_token)])
async def activity_feed_detail(conv_id: str):
    """One conversation's FULL message + delegation/tool timeline, proxied from OJ."""
    online, data = await _oj_get(f"/v1/history/conversations/{conv_id}")
    return {"desktop_online": online, "conversation": data if online else None}


@app.get("/v1/status", dependencies=[Depends(require_token)])
async def status():
    """Real engine/token/cost/session status from OJ telemetry, merged with the
    sidecar's live approval counts. Degrades to approvals-only if OJ is down."""
    online, telem = await _oj_get("/v1/telemetry/stats")
    _, budget = await _oj_get("/v1/budget") if online else (False, None)
    pending = await asyncio.to_thread(approval_store.list_pending)
    return {
        "desktop_online": online,
        "pending_approvals": len(pending),
        "telemetry": telem if online else None,
        "budget": budget,
    }


# ---- Talk (one text turn with EVE's brain) ------------------------------------
# The watch/phone talk leg: text in, EVE's reply out, via the canonical OJ agent
# (full tools + memory; /v1/chat/completions non-streaming, NO client tools array —
# that would bypass the agent). Unlike the read proxies above, failure here is LOUD
# (502/504 with the broken leg named), never a degraded fake reply.
class AskRequest(BaseModel):
    text: str


def _oj_client(timeout: float):
    """Seam for tests: the httpx client used for the brain leg."""
    import httpx

    return httpx.AsyncClient(timeout=timeout)


async def _oj_ask(text: str, timeout: float = 50.0) -> str:
    key = os.getenv("OPENJARVIS_API_KEY", "")
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    payload = {
        # Empty model -> OJ keeps EVE's configured brain (only truthy overrides it).
        "model": "",
        "messages": [{"role": "user", "content": text}],
        "stream": False,
    }
    import httpx

    try:
        async with _oj_client(timeout) as client:
            resp = await client.post(
                f"{_OJ_BASE}/v1/chat/completions", json=payload, headers=headers
            )
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail=f"EVE's brain timed out ({timeout:.0f}s)")
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"EVE's brain unreachable: {e!r}")
    if resp.status_code != 200:
        raise HTTPException(
            status_code=502, detail=f"EVE's brain returned {resp.status_code}"
        )
    try:
        reply = resp.json()["choices"][0]["message"]["content"]
    except Exception:
        raise HTTPException(
            status_code=502, detail="EVE's brain returned an unexpected response shape"
        )
    if not (reply or "").strip():
        raise HTTPException(status_code=502, detail="EVE's brain returned an empty reply")
    return reply


@app.post("/v1/ask", dependencies=[Depends(require_token)])
async def ask_eve(body: AskRequest):
    """One synchronous text exchange with EVE — the watch speaks, EVE answers."""
    text = body.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must be non-empty")
    return {"reply": await _oj_ask(text)}


# ---- Voice turn (watch v2: HER ears, HER voice) --------------------------------
# WAV in -> EVE's own STT -> her brain -> her own TTS voice back. Leg honesty:
# 400 undecodable audio, 422 silence ("no speech recognized"), 502/504 brain legs
# (via _oj_ask), and a TTS failure still returns the reply TEXT with a visible
# voice_error — the answer reaches the wrist even when her voice can't.
class VoiceTurnRequest(BaseModel):
    audio_b64: str
    request_id: str = ""
    language: str = "en"


@app.post("/v1/voice/turn", dependencies=[Depends(require_token)])
async def voice_turn(body: VoiceTurnRequest):
    import base64
    import binascii

    try:
        wav = base64.b64decode(body.audio_b64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="audio_b64 is not valid base64")
    if not wav:
        raise HTTPException(status_code=400, detail="audio is empty")

    try:
        transcript = await asyncio.to_thread(speech_oneshot.transcribe, wav, body.language)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"undecodable audio: {e}")
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"EVE's ears failed: {e!r}")
    if not transcript:
        raise HTTPException(status_code=422, detail="no speech recognized")

    reply = await _oj_ask(transcript)

    audio_b64 = None
    voice_error = None
    try:
        reply_wav = await asyncio.to_thread(speech_oneshot.synthesize, reply)
        audio_b64 = base64.b64encode(reply_wav).decode("ascii")
    except speech_oneshot.VoiceUnavailable as e:
        voice_error = str(e)
        logger.warning(f"voice turn {body.request_id or '?'}: reply is text-only — {e}")

    return {
        "transcript": transcript,
        "reply": reply,
        "audio_b64": audio_b64,
        "sample_rate": speech_oneshot.TURN_SAMPLE_RATE,
        "voice_error": voice_error,
    }


# ---- Health snapshot ingest (Vision Priority #1: HEALTH FIRST) ------------------
# The phone reads Samsung-Health-fed data from Android's Health Connect and pushes a
# compact snapshot here; EVE's health_status tool reads it (with its age) from
# health_store. NOTE: GET /v1/health (service status) is a DIFFERENT, older route.
@app.post("/v1/health/snapshot", dependencies=[Depends(require_token)])
async def health_snapshot(request: Request):
    raw = await request.body()
    if len(raw) > 64 * 1024:
        raise HTTPException(status_code=413, detail="snapshot too large (64KB cap)")
    try:
        snapshot = json.loads(raw)
        if not isinstance(snapshot, dict) or not snapshot:
            raise ValueError("snapshot must be a non-empty JSON object")
    except (ValueError, json.JSONDecodeError) as e:
        raise HTTPException(status_code=400, detail=f"undecodable snapshot: {e}")
    await asyncio.to_thread(health_store.save, snapshot)
    return {"ok": True}


# ---- Memory -----------------------------------------------------------------
# speaker is OPTIONAL: omitted -> the OWNER page (jarvis-memory.md), i.e. the actual memory EVE
# boots with (memory_tool.memory_pack). _page_for(None, ...) already returns MEMORY_PAGE, so a
# missing speaker reads/writes the owner's real vault. An EXPLICIT speaker still routes to that
# person's isolated bucket and never touches the owner page (spec §1.8 isolation invariant).
def _write_fact(speaker: str | None, fact: str) -> None:
    page = memory_tool._page_for(speaker, "known")
    page.parent.mkdir(parents=True, exist_ok=True)
    if not page.is_file():
        page.write_text(memory_tool._HEADER, encoding="utf-8")
    with open(page, "a", encoding="utf-8") as f:
        f.write(f"- [{_dt.date.today():%Y-%m-%d}] {fact}\n")


@app.get("/v1/memory", dependencies=[Depends(require_token)])
async def get_memory(speaker: str | None = Query(default=None)):
    page = memory_tool._page_for(speaker, "known")  # speaker None -> owner page (the real memory)
    facts = await asyncio.to_thread(memory_tool._entries, page)
    items = memory_tool.parse_facts(facts)  # structured {text,date,category}, newest last on disk
    # newest first for the phone; keep raw `facts` for back-compat
    return {"speaker": speaker, "facts": facts, "items": list(reversed(items))}


@app.post("/v1/memory", dependencies=[Depends(require_token)])
async def add_memory(add: MemoryAdd):
    fact = add.fact.strip()
    if not fact:
        raise HTTPException(status_code=400, detail="fact is empty")
    await asyncio.to_thread(_write_fact, add.speaker, fact)
    return {"ok": True, "speaker": add.speaker, "remembered": fact}


# ---- Activity / transcripts -------------------------------------------------
@app.get("/v1/activity", dependencies=[Depends(require_token)])
async def activity(day: str = Query(default="today")):
    resolved = transcript_review._resolve_day(day)
    if resolved is None:
        raise HTTPException(status_code=400, detail=f"unrecognized day {day!r}")
    digest = await asyncio.to_thread(transcript_review.review_day, resolved)
    return {"date": f"{resolved:%Y-%m-%d}", **digest}


# ---- Live stream (foreground only) ------------------------------------------
def _ws_token(ws: WebSocket) -> str:
    """Read the bearer token from the Sec-WebSocket-Protocol header (sent as
    'bearer, <token>'), NOT from the URL query string — a token in the URL leaks into
    access logs and proxies. Mirrors the REST Bearer scheme."""
    raw = ws.headers.get("sec-websocket-protocol", "")
    parts = [p.strip() for p in raw.split(",")]
    if len(parts) >= 2 and parts[0].lower() == "bearer":
        return parts[1]
    return ""


@app.websocket("/v1/stream")
async def stream(ws: WebSocket, surface: str = Query(default="phone")):
    if not _check(_ws_token(ws)):
        await ws.close(code=4401)        # reject before accepting; nothing joins the hub
        return
    # Surface is a routing label, not a secret — it names WHICH camera this client is
    # (?surface=phone|glasses; the existing app sends none => "phone"). Reject an
    # unknown value so a typo never silently drops a client into "phone" routing.
    if surface not in VALID_SURFACES:
        await ws.close(code=4400)
        return
    # Echo the negotiated subprotocol so the handshake completes for strict clients.
    await ws.accept(subprotocol="bearer")
    await hub.join(ws, surface)
    try:
        while True:
            await ws.receive_text()      # client keepalive; events are pushed via hub
    except WebSocketDisconnect:
        pass
    finally:
        hub.leave(ws)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=os.getenv("EVE_APPROVAL_HOST", "127.0.0.1"),
        port=int(os.getenv("EVE_APPROVAL_PORT", "8799")),
    )
