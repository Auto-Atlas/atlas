#
# research_tool — Eve's voice control over ResearchOS, the autonomous product-
# research service (FastAPI on the inference box; address set via RESEARCHOS_URL).
# Three voice functions:
#   - start_research         : create a session {goal, budget?} and kick off the
#                              research pipeline (an ACTION — confirm-gated)
#   - research_status        : progress of the MOST RECENT session
#   - list_research_decisions: the latest research decisions (wiki pages)
#
# WHY confirm-gate start_research (and only it): kicking a pipeline spends real
# compute on the inference box and is a side effect, so it follows the same single-tool
# confirm flow as create_lead — the skill frontmatter (requires_confirmation:
# true) makes tool_policy freeze the first call, read the goal back, and only
# fire on the confirmed=true re-call. The two reads are free and un-gated.
#
# WHY no bearer / no loopback guard: ResearchOS on the tailnet is unauthenticated
# and carries NO secret Eve holds, so unlike books_tool there is nothing to keep
# on-box — the URL is deliberately a NON-loopback tailnet address (that's where
# the inference box lives). The single seam below centralizes the base URL (env-driven,
# EVE is a product), the 5s timeout, and the down-detection.
#
# OPERATIONAL NOTE: the inference box is frequently DOWN. Every function degrades to one
# honest spoken line — "ResearchOS is unreachable, the inference box may be down" — and
# never fabricates progress. Built and unit-tested entirely against mocked httpx.
#

import os

import httpx
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

_TIMEOUT_S = 5.0            # spoken UX: a down inference box must fail fast, not hang


def _researchos_url() -> str:
    """Base URL of ResearchOS — env-driven (EVE is a product), with NO default: a
    productized install must point RESEARCHOS_URL at its OWN ResearchOS instance
    rather than silently probing someone else's private rig. Trailing slash trimmed
    so `{base}{path}` is well-formed. Callers guard on emptiness via _config_result
    and fail LOUDLY before any network call."""
    return os.getenv("RESEARCHOS_URL", "").rstrip("/")


def _config_result() -> dict | None:
    """None when RESEARCHOS_URL is configured, else an ok:False result. Fails
    LOUDLY (never a silent probe of a hardcoded rig) when the address is unset."""
    if not os.getenv("RESEARCHOS_URL", "").strip():
        return {
            "ok": False,
            "error": "RESEARCHOS_URL not set — point it at your ResearchOS instance",
            "instruction": (
                "Tell the user the research service isn't set up yet — its address "
                "(RESEARCHOS_URL) hasn't been configured — so you can't reach it. One "
                "short sentence; do NOT invent any progress or result."
            ),
        }
    return None


class _ResearchDown(Exception):
    """ResearchOS couldn't be reached — almost always the inference box being offline.
    Handlers turn this into an honest, spoken-friendly ok:False."""


async def _research_request(
    method: str, path: str, *, params: dict | None = None, body: dict | None = None
) -> tuple[int, dict]:
    """The SINGLE outbound seam to ResearchOS. `{base}{path}` with a 5s timeout.
    Returns (status, json). Raises _ResearchDown for any transport failure
    (connect refused / timeout / DNS) so callers speak one clear 'the inference box may be
    down' line; lets a real HTTP response (even 4xx/5xx) return for the caller to
    unpack. Isolated so tests wrap exactly one network call."""
    url = f"{_researchos_url()}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S) as client:
            resp = await client.request(method, url, params=params, json=body)
            try:
                data = resp.json()
            except Exception:
                data = {}
            return resp.status_code, data
    except httpx.HTTPError as e:
        raise _ResearchDown(str(e)) from e


def _down_result() -> dict:
    """The honest 'inference box is down' result — names WHICH leg failed."""
    return {
        "ok": False,
        "error": "ResearchOS is unreachable — the inference box may be down",
        "instruction": (
            "Tell the user ResearchOS isn't reachable right now — the inference box may be down — "
            "so you can't reach the research service. One short sentence; do NOT invent "
            "any progress or result."
        ),
    }


def _error_result(data: dict, status: int) -> dict:
    """Turn a non-2xx ResearchOS response into a truncated ok:False. FastAPI errors
    are {"detail": "..."}; be defensive about other shapes too."""
    detail = (data or {}).get("detail") or (data or {}).get("error") or f"HTTP {status}"
    return {"ok": False, "error": str(detail)[:200]}


# ============================== start_research =============================
# An ACTION (spawns a pipeline on the inference box). Confirmation staging is handled
# ENTIRELY by tool_policy via the skill frontmatter (requires_confirmation: true):
# the first call is frozen and the goal read back; this handler only runs on the
# confirmed=true release with the frozen args (tool_policy strips `confirmed`
# before it reaches us). So the handler just validates the goal and does the
# two-step create-then-start. Mirrors books_tool.create_lead.

START_RESEARCH_SCHEMA = FunctionSchema(
    name="start_research",
    description=(
        "Start a ResearchOS research session by voice: describe a GOAL (what to research or "
        "buy) and optionally a budget, and it kicks off the autonomous research pipeline on "
        "the inference box. Use when the user says to research something, find products for a goal, or "
        "spin up a research session. Read the goal (and budget) back and only start it after "
        "the user clearly says yes. This SPENDS compute; it is not a read."
    ),
    properties={
        "goal": {
            "type": "string",
            "description": "What to research, e.g. 'a 3D printer under 500 dollars for the farm'. Required.",
        },
        "budget": {
            "type": "number",
            "description": "Optional budget in dollars, e.g. 500. Omit if none was given.",
        },
        "confirmed": {
            "type": "boolean",
            "description": (
                "Set true ONLY when re-calling after the user said yes to the read-back. "
                "Omit or set false on the first call."
            ),
        },
    },
    required=["goal"],
)


async def handle_start_research(params: FunctionCallParams):
    goal = str(params.arguments.get("goal") or "").strip()
    # Validate BEFORE any network call — a session with no goal is worthless, and
    # tool_policy's requires_fields is not wired for this tool.
    if not goal:
        await params.result_callback({
            "ok": False,
            "error": "start_research needs a goal — ask the user what to research.",
        })
        return
    cfg = _config_result()
    if cfg is not None:
        await params.result_callback(cfg)
        return
    budget = params.arguments.get("budget")

    # Step 1: create the session.
    create_body: dict = {"goal": goal}
    if budget is not None:
        create_body["budget"] = budget
    try:
        status, session = await _research_request("POST", "/api/sessions", body=create_body)
    except _ResearchDown as e:
        logger.warning(f"start_research create: research down: {e}")
        await params.result_callback(_down_result())
        return
    if status not in (200, 201):
        await params.result_callback(_error_result(session, status))
        return

    session_id = (session or {}).get("id")
    if not session_id:
        # Created but no id came back — do NOT claim the pipeline started.
        await params.result_callback({
            "ok": False,
            "error": "ResearchOS created a session but returned no id — can't start the pipeline.",
        })
        return

    # Step 2: kick off the pipeline for that session.
    try:
        status, started = await _research_request(
            "POST", f"/api/sessions/{session_id}/research"
        )
    except _ResearchDown as e:
        logger.warning(f"start_research kick: research down: {e}")
        # The session exists but the pipeline didn't start — be honest about the split.
        await params.result_callback({
            "ok": False,
            "session_id": session_id,
            "error": "ResearchOS went unreachable after creating the session — the pipeline "
                     "didn't start. the inference box may be down.",
        })
        return
    if status not in (200, 201):
        await params.result_callback(_error_result(started, status))
        return

    result = {
        "ok": True,
        "session_id": session_id,
        "goal": goal,
        "budget": budget,
        "mode": (session or {}).get("mode"),
        "job_id": (started or {}).get("job_id"),
        "status": (started or {}).get("status") or (started or {}).get("session", {}).get("status"),
        "instruction": (
            "Confirm the research session has started. Repeat the goal back so the user knows "
            "you got it right, and say you'll have results shortly — they can ask for the "
            "status any time. One or two short sentences."
        ),
    }
    logger.info(f"start_research -> session={session_id} job={result['job_id']}")
    await params.result_callback(result)


# ============================= research_status ============================

RESEARCH_STATUS_SCHEMA = FunctionSchema(
    name="research_status",
    description=(
        "Check the progress of the most recent ResearchOS research session — how far along "
        "it is and whether it's done. Use when the user asks how the research is going, if "
        "it's finished, or for a status update on what they asked you to research. Reports "
        "only the latest session. Report only what the tool returns."
    ),
    properties={},
    required=[],
)


async def handle_research_status(params: FunctionCallParams):
    cfg = _config_result()
    if cfg is not None:
        await params.result_callback(cfg)
        return
    # Find the most recent session (list is ordered newest-first), then read its job.
    try:
        status, sessions = await _research_request("GET", "/api/sessions")
    except _ResearchDown as e:
        logger.warning(f"research_status list: research down: {e}")
        await params.result_callback(_down_result())
        return
    if status != 200:
        await params.result_callback(_error_result(sessions if isinstance(sessions, dict) else {}, status))
        return

    session_list = sessions if isinstance(sessions, list) else []
    if not session_list:
        await params.result_callback({
            "ok": True,
            "found": False,
            "instruction": "Tell the user there are no research sessions yet. One short sentence.",
        })
        return

    latest = session_list[0]
    session_id = latest.get("id")
    goal = latest.get("goal")

    try:
        status, job = await _research_request("GET", f"/api/sessions/{session_id}/status")
    except _ResearchDown as e:
        logger.warning(f"research_status job: research down: {e}")
        await params.result_callback(_down_result())
        return

    if status == 404:
        # Session exists but no pipeline job yet (e.g. still in analyze stage).
        await params.result_callback({
            "ok": True,
            "found": True,
            "session_id": session_id,
            "goal": goal,
            "session_status": latest.get("status"),
            "job_status": None,
            "instruction": (
                "Tell the user the latest research session (repeat the goal) hasn't started "
                "its pipeline yet — it's in the "
                f"'{latest.get('status')}' stage. One short sentence."
            ),
        })
        return
    if status != 200:
        await params.result_callback(_error_result(job, status))
        return

    result = {
        "ok": True,
        "found": True,
        "session_id": session_id,
        "goal": goal,
        "session_status": latest.get("status"),
        "job_status": (job or {}).get("status"),
        "needs_completed": (job or {}).get("needs_completed"),
        "needs_total": (job or {}).get("needs_total"),
        "error": (job or {}).get("error"),
        "instruction": (
            "Give the user the latest session's goal and how it's going: the job status "
            "(e.g. searching, evaluating, complete) and how many needs are done out of the "
            "total. If it errored, say so plainly. Keep it to a sentence or two. Report only "
            "what the tool returns."
        ),
    }
    logger.info(f"research_status -> session={session_id} status={result['job_status']}")
    await params.result_callback(result)


# ========================= list_research_decisions ========================

LIST_RESEARCH_DECISIONS_SCHEMA = FunctionSchema(
    name="list_research_decisions",
    description=(
        "List the most recent ResearchOS decisions — the research write-ups where products "
        "were chosen. Use when the user asks what research has decided lately, for recent "
        "decisions, or what got picked. Defaults to the latest 3. Report only what the tool "
        "returns."
    ),
    properties={
        "limit": {
            "type": "integer",
            "description": "How many recent decisions to list. Omit for the default (3).",
        },
    },
    required=[],
)


async def handle_list_research_decisions(params: FunctionCallParams):
    try:
        limit = int(params.arguments.get("limit") or 3)
    except (TypeError, ValueError):
        limit = 3
    limit = max(1, min(limit, 20))

    cfg = _config_result()
    if cfg is not None:
        await params.result_callback(cfg)
        return
    try:
        status, data = await _research_request("GET", "/api/decisions")
    except _ResearchDown as e:
        logger.warning(f"list_research_decisions: research down: {e}")
        await params.result_callback(_down_result())
        return
    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    decisions = [
        {"slug": d.get("slug")}
        for d in (data or {}).get("decisions", [])
    ][:limit]
    result = {
        "ok": True,
        "count": len(decisions),
        "decisions": decisions,
        "instruction": (
            "Tell the user how many recent decisions there are and read back the top few by "
            "their slug (turn the slug into readable words — it's a title, not a URL). If "
            "there are none, say there are no research decisions yet. Report only what the "
            "tool returns."
        ),
    }
    logger.info(f"list_research_decisions -> count={result['count']}")
    await params.result_callback(result)
