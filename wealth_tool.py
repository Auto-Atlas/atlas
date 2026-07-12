#
# wealth_tool — Eve's read window into the Wealth OS dashboard (the Next.js app
# in ~/Vision/apps/dashboard, default http://localhost:3100). Four voice-first
# READS, no writes:
#   - get_wealth_summary   : the cash pulse — YTD net + per-business net + the
#                            gap to the one-million-dollar goal
#   - get_planned_purchases: researched-but-unspent purchases waiting on a call
#   - get_budget_envelope  : one business's envelopes (allocated/committed/available)
#   - get_goal_scorecard   : the Vision priority-stack scorecard
#
# WHY no bearer / no loopback guard (unlike books_tool): the dashboard's GET
# endpoints are unauthenticated by design — the CSRF/mutation guard only fences
# the POST routes, and these reads carry NO secret. So the one thing books_tool
# protects (a service token that must never leave the box) does not exist here;
# adding a loopback guard would be cargo-culted ceremony. The single seam below
# still centralizes "where is Wealth OS", the 5s timeout, and the down-detection
# so every function degrades the same honest way when the dashboard isn't up.
#
# EVE is a PRODUCT: the base URL is WEALTH_OS_URL (default localhost:3100), never
# hardcoded. Amounts arrive in MIXED units — /api/pulse is in integer CENTS
# (AutoInvoice's native unit), while /api/planned-purchases and /api/budgets are
# already in whole-dollar USD. Each handler converts at ITS boundary and every
# result carries an `instruction` telling Eve to SPEAK dollars, rounded naturally.
#

import os

import httpx
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

# The business slugs Wealth OS accepts — configured per-tenant via the
# WEALTH_OS_BUSINESS_SLUGS env var (comma-separated), NEVER baked in: EVE is a
# PRODUCT, so the owner's companies are not a code default. An unknown slug is
# rejected BEFORE any network call so Eve asks again instead of silently querying
# an empty envelope. Read fresh on every call so an onboarding/config change takes
# effect without a restart (and so each test can set the env per-case).
def _business_slugs() -> tuple[str, ...]:
    raw = os.getenv("WEALTH_OS_BUSINESS_SLUGS", "")
    return tuple(s.strip() for s in raw.split(",") if s.strip())


def _validate_business(business: str) -> dict | None:
    """None when `business` is an accepted slug, else an ok:False result. Fails
    LOUDLY (never silently) when NO slugs are configured: an install with
    WEALTH_OS_BUSINESS_SLUGS unset must tell the operator to set it, rather than
    silently accept every business or reject them all."""
    slugs = _business_slugs()
    if not slugs:
        return {
            "ok": False,
            "error": "no business slugs configured — set WEALTH_OS_BUSINESS_SLUGS="
                     "slug-a,slug-b (comma-separated) so the wealth reads know which "
                     "businesses to accept.",
        }
    if business not in slugs:
        return {
            "ok": False,
            "error": f"unknown business {business!r} — expected one of {', '.join(slugs)}",
        }
    return None

_TIMEOUT_S = 5.0            # spoken UX: never make the user wait on a dead service


def _wealth_url() -> str:
    """Base URL of the Wealth OS dashboard. Env-driven (EVE is a product); trailing
    slash trimmed so `{base}{path}` is always well-formed."""
    return os.getenv("WEALTH_OS_URL", "http://localhost:3100").rstrip("/")


def _cents_to_dollars(cents) -> float:
    """Integer cents -> dollars. Only /api/pulse is in cents; the budget/purchase
    endpoints are already USD and must NOT pass through here."""
    return (cents or 0) / 100


class _WealthDown(Exception):
    """The dashboard couldn't be reached (connect refused / timeout / transport
    error). Handlers turn this into an honest, spoken-friendly ok:False — the
    number one failure mode is 'the Next.js app isn't running'."""


async def _wealth_get(path: str, *, params: dict | None = None) -> tuple[int, dict]:
    """The SINGLE outbound seam for the wealth reads. GET `{base}{path}` with a 5s
    timeout. Returns (status, json). Raises _WealthDown for any transport failure
    (connect refused, timeout, DNS) so callers speak one clear 'isn't running'
    line; lets a real HTTP response (even 500) return normally for _error_result
    to unpack. Isolated so tests wrap exactly one network call."""
    url = f"{_wealth_url()}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S) as client:
            resp = await client.get(url, params=params)
            try:
                data = resp.json()
            except Exception:
                data = {}
            return resp.status_code, data
    except httpx.HTTPError as e:
        raise _WealthDown(str(e)) from e


def _down_result() -> dict:
    """The honest 'dashboard isn't running' result — names WHICH leg failed."""
    return {
        "ok": False,
        "error": "Wealth OS dashboard isn't running",
        "instruction": (
            "Tell the user the Wealth OS dashboard isn't running right now, so you "
            "can't read the numbers. One short sentence; do NOT invent figures."
        ),
    }


def _error_result(data: dict, status: int) -> dict:
    """Turn a non-2xx dashboard response into a truncated ok:False. The routes
    return either {error:{message}} or {error:"..."} — handle both."""
    err = (data or {}).get("error")
    if isinstance(err, dict):
        msg = err.get("message") or f"HTTP {status}"
    else:
        msg = err or f"HTTP {status}"
    return {"ok": False, "error": str(msg)[:200]}


# ============================ get_wealth_summary ===========================

GET_WEALTH_SUMMARY_SCHEMA = FunctionSchema(
    name="get_wealth_summary",
    description=(
        "Read the Wealth OS cash pulse: net cash per business, year-to-date net across "
        "the businesses, and how far off the one-million-dollar goal we are. Use when the "
        "user asks how the money's doing overall, for a wealth summary or cash pulse, the "
        "year-to-date total, or how close they are to the million-dollar goal. This reads "
        "the Wealth OS dashboard, NOT the raw books. Report only what the tool returns — "
        "never invent numbers."
    ),
    properties={
        "week": {
            "type": "integer",
            "description": "ISO week number (1-53). Omit for the current week.",
        },
        "year": {
            "type": "integer",
            "description": "Four-digit year, e.g. 2026. Omit for the current year.",
        },
    },
    required=[],
)


async def handle_get_wealth_summary(params: FunctionCallParams):
    query = {}
    for field in ("week", "year"):
        val = params.arguments.get(field)
        if val is not None:
            query[field] = val
    try:
        status, data = await _wealth_get("/api/pulse", params=query or None)
    except _WealthDown as e:
        logger.warning(f"get_wealth_summary: wealth down: {e}")
        await params.result_callback(_down_result())
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    companies = [
        {
            "id": c.get("id"),
            "name": c.get("name"),
            "net_cash_dollars": _cents_to_dollars(c.get("net_cash_cents")),
        }
        for c in data.get("companies", [])
    ]
    # gap_to_1m_cents may be absent if AutoInvoice isn't wired — expose it only
    # when the API actually returned it, so Eve knows whether to mention the goal.
    gap_cents = data.get("gap_to_1m_cents")
    result = {
        "ok": True,
        "week": data.get("week"),
        "year": data.get("year"),
        "companies": companies,
        "ytd_net_dollars": _cents_to_dollars(data.get("ytd_net_cents")),
        "gap_to_1m_dollars": _cents_to_dollars(gap_cents) if gap_cents is not None else None,
        "instruction": (
            "Lead with the year-to-date net across the businesses in DOLLARS, rounded "
            "naturally ('about twelve thousand dollars', not '$12,340.00'). Then give net "
            "cash per business. If gap_to_1m_dollars is present, mention how far off the "
            "million-dollar goal they are; if it's null, don't bring the goal up. Speak "
            "dollars, never cents. Never invent a number the tool didn't return."
        ),
    }
    logger.info(f"get_wealth_summary -> ytd={result['ytd_net_dollars']} companies={len(companies)}")
    await params.result_callback(result)


# ========================= get_planned_purchases ==========================

GET_PLANNED_PURCHASES_SCHEMA = FunctionSchema(
    name="get_planned_purchases",
    description=(
        "List planned purchases — things ResearchOS researched and staged to buy but that "
        "haven't been spent yet (committed money). Use when the user asks what's queued to "
        "buy, what purchases are pending or planned, or what research decided to order. "
        "Optionally filter to one business. Report only what the tool returns."
    ),
    properties={
        "business": {
            "type": "string",
            "description": (
                'Which business to filter to: "acme-farms", "acme-web", or '
                '"acme-robotics". Omit for all businesses.'
            ),
        },
    },
    required=[],
)


async def handle_get_planned_purchases(params: FunctionCallParams):
    business = params.arguments.get("business")
    query = {"status": "planned"}       # "planned" == committed-but-unspent
    if business:
        err = _validate_business(business)
        if err is not None:
            await params.result_callback(err)
            return
        query["company"] = business
    try:
        status, data = await _wealth_get("/api/planned-purchases", params=query)
    except _WealthDown as e:
        logger.warning(f"get_planned_purchases: wealth down: {e}")
        await params.result_callback(_down_result())
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    purchases = [
        {
            "company": p.get("company"),
            "category": p.get("category"),
            # Already USD dollars on this endpoint — do NOT cents-convert.
            "total_cost_usd": p.get("total_cost_usd"),
            "status": p.get("status"),
            "rationale": p.get("rationale"),
        }
        for p in data.get("purchases", [])
    ]
    total = sum(p["total_cost_usd"] or 0 for p in purchases)
    result = {
        "ok": True,
        "count": data.get("count", len(purchases)),
        "total_planned_usd": round(total, 2),
        "purchases": purchases,
        "instruction": (
            "Say how many purchases are planned and the total planned spend in DOLLARS, "
            "rounded naturally. Name the top few by cost (business and what it's for) unless "
            "the user asks for all. If nothing is planned, say so plainly. Speak dollars; "
            "report only what the tool returns."
        ),
    }
    logger.info(f"get_planned_purchases -> count={result['count']} total={result['total_planned_usd']}")
    await params.result_callback(result)


# ========================== get_budget_envelope ===========================

GET_BUDGET_ENVELOPE_SCHEMA = FunctionSchema(
    name="get_budget_envelope",
    description=(
        "Read a business's budget envelopes: allocated, committed (already promised to "
        "planned purchases), and available per category. Use when the user asks about a "
        "business's budget, how much is left to spend, what's allocated or committed, or "
        "whether there's room for a purchase. Requires which business. Report only what "
        "the tool returns."
    ),
    properties={
        "business": {
            "type": "string",
            "description": (
                'Which business: "acme-farms", "acme-web", or '
                '"acme-robotics". Required.'
            ),
        },
    },
    required=["business"],
)


async def handle_get_budget_envelope(params: FunctionCallParams):
    business = str(params.arguments.get("business") or "").strip()
    if not business:
        await params.result_callback({
            "ok": False,
            "error": "get_budget_envelope needs which business — ask the user.",
        })
        return
    err = _validate_business(business)
    if err is not None:
        await params.result_callback(err)
        return
    try:
        status, data = await _wealth_get("/api/budgets", params={"business": business})
    except _WealthDown as e:
        logger.warning(f"get_budget_envelope: wealth down: {e}")
        await params.result_callback(_down_result())
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    envelopes = [
        {
            "category": e.get("category"),
            # All three are already USD dollars — no cents conversion.
            "allocated_usd": e.get("allocated_usd"),
            "committed_usd": e.get("committed_usd"),
            "available_usd": e.get("available_usd"),
        }
        for e in data.get("envelopes", [])
    ]
    total_available = sum(e["available_usd"] or 0 for e in envelopes)
    result = {
        "ok": True,
        "business": business,
        "count": data.get("count", len(envelopes)),
        "total_available_usd": round(total_available, 2),
        "envelopes": envelopes,
        "instruction": (
            "Lead with the total available to spend for this business in DOLLARS, rounded "
            "naturally. Then break down by category (allocated / committed / available) only "
            "if the user wants detail. If there are no envelopes, say this business has no "
            "budgets set up. Speak dollars; report only what the tool returns."
        ),
    }
    logger.info(f"get_budget_envelope -> {business} available={result['total_available_usd']}")
    await params.result_callback(result)


# ========================== get_goal_scorecard ============================

GET_GOAL_SCORECARD_SCHEMA = FunctionSchema(
    name="get_goal_scorecard",
    description=(
        "Read the Vision priority-stack scorecard: the ranked goals (the million-dollar "
        "cash goal, delegation, the robot arm, and manual goals) with their scores this "
        "period. Use when the user asks how they're tracking against their goals, for the "
        "scorecard or priority stack, or whether they're on pace. Report only what the tool "
        "returns."
    ),
    properties={
        "grain": {
            "type": "string",
            "description": "daily, weekly, or monthly. Omit for weekly (the default).",
        },
    },
    required=[],
)


async def handle_get_goal_scorecard(params: FunctionCallParams):
    grain = params.arguments.get("grain")
    query = {}
    if grain in ("daily", "weekly", "monthly"):
        query["grain"] = grain
    try:
        status, data = await _wealth_get("/api/scorecard", params=query or None)
    except _WealthDown as e:
        logger.warning(f"get_goal_scorecard: wealth down: {e}")
        await params.result_callback(_down_result())
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    scorecard = (data or {}).get("scorecard") or {}
    goals = []
    for g in scorecard.get("goals", []):
        auto = g.get("auto") or {}
        recorded = g.get("recorded") or {}
        goals.append({
            "stack_rank": g.get("stack_rank"),
            "title": g.get("title"),
            # Prefer the auto-computed score/detail; fall back to a recorded entry.
            "score": auto.get("score") if auto else recorded.get("score"),
            "detail": auto.get("detail") if auto else (recorded.get("notes")),
        })
    result = {
        "ok": True,
        "grain": scorecard.get("grain"),
        "period": scorecard.get("period"),
        "goals": goals,
        "instruction": (
            "Go through the goals in stack-rank order (rank 1 first). For each, give its "
            "title and how it's tracking (the score and detail if present). Lead with the "
            "million-dollar cash goal if it's in the list. Keep it brief unless asked for "
            "detail. Report only what the tool returns — don't invent a score."
        ),
    }
    logger.info(f"get_goal_scorecard -> grain={result['grain']} goals={len(goals)}")
    await params.result_callback(result)
