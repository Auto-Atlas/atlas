#
# The books — Eve's read (and one write) window into the AutoInvoice backend
# running locally on this machine (http://localhost:4000). Four voice-first
# tools:
#   - get_cash_pulse       : weekly cash in/out per business + YTD
#   - list_unpaid_invoices : who owes money, overdue first
#   - lookup_customer      : the story on one customer (balance, last bill)
#   - create_lead          : capture a new inquiry (the only WRITE — gated)
#
# All four carry the AutoInvoice service bearer, which is ONLY valid for the
# local AutoInvoice on this box. Every request goes through one seam
# (_books_request) that refuses to send the token to a non-loopback URL and
# refuses when the token isn't configured — the token must never leave the box.
# Results carry "instruction" fields telling Eve how to SPEAK the numbers:
# dollars not cents, rounded naturally, top 3 unless asked for all.
#

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

# Reuse invoice_tool's env seams + loopback guard verbatim — one definition of
# "where is AutoInvoice" and "is this URL safe for the bearer" for both modules.
# Do NOT duplicate _is_loopback_url; a second copy could drift from the guard the
# security tests pin.
from invoice_tool import _autoinvoice_token, _autoinvoice_url, _is_loopback_url


def _cents_to_dollars(cents) -> float:
    """cents (int) -> dollars (float). Backend is the single source of amounts in
    integer cents; Eve speaks dollars, so every amount is divided here, never in
    the prompt."""
    return (cents or 0) / 100


class _BooksRefusal(Exception):
    """Config/loopback refusal raised BEFORE any network call — the token never
    left the box. Handlers turn it into an honest ok:False result."""


async def _books_request(method: str, path: str, *, query: dict | None = None,
                         body: dict | None = None) -> tuple[int, dict]:
    """The SINGLE outbound seam to AutoInvoice for the books tools. Does, in order:
      1. refuse if the service token isn't configured,
      2. refuse if AUTOINVOICE_URL is not loopback (never send the bearer off-box),
      3. attach the bearer and make the request with a 15s timeout.
    Returns (status, data). Raises _BooksRefusal for (1)/(2) — so a non-loopback URL
    never reaches aiohttp — and lets network errors propagate for the handler to
    catch. Isolated so tests wrap exactly one network call (mirrors
    invoice_tool._post_structured_invoice)."""
    token = _autoinvoice_token()
    if not token:
        raise _BooksRefusal(
            "AutoInvoice token not configured — set AUTOINVOICE_SERVICE_TOKEN in .env"
        )
    url = _autoinvoice_url()
    if not _is_loopback_url(url):
        logger.warning(
            f"books request refused: AUTOINVOICE_URL is not loopback ({url!r}) — "
            "not sending the service token off-box"
        )
        raise _BooksRefusal(
            "AutoInvoice URL is not loopback — refusing to send the service token off-box"
        )
    timeout = aiohttp.ClientTimeout(total=15)
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
    }
    async with aiohttp.ClientSession(timeout=timeout) as session:
        async with session.request(
            method,
            f"{url}{path}",
            params=query,
            json=body,
            headers=headers,
        ) as resp:
            return resp.status, await resp.json()


def _error_result(data: dict, status: int) -> dict:
    """Turn a non-2xx AutoInvoice response into an honest, truncated ok:False."""
    msg = (data or {}).get("error") or (data or {}).get("message") or f"HTTP {status}"
    return {"ok": False, "error": str(msg)[:200]}


# ============================ get_cash_pulse ================================

GET_CASH_PULSE_SCHEMA = FunctionSchema(
    name="get_cash_pulse",
    description=(
        "Read the weekly cash pulse across all the businesses: money in, expenses, and "
        "net cash per company, plus year-to-date net. Use when the user asks how much "
        "money came in, for a cash pulse, how the businesses are doing this week, or how "
        "close they are to a goal. With no arguments it uses the current week; pass a week "
        "number and/or year to look at a specific one. Report only what the tool returns — "
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


async def handle_get_cash_pulse(params: FunctionCallParams):
    query = {}
    for field in ("week", "year"):
        val = params.arguments.get(field)
        if val is not None:
            query[field] = val
    try:
        status, data = await _books_request("GET", "/eve/pulse", query=query or None)
    except _BooksRefusal as e:
        await params.result_callback({"ok": False, "error": str(e)})
        return
    except Exception as e:
        logger.warning(f"get_cash_pulse failed: {e}")
        await params.result_callback({"ok": False, "error": f"could not reach AutoInvoice: {e}"})
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    companies = [
        {
            "id": c.get("id"),
            "name": c.get("name"),
            "gross_inflow_dollars": _cents_to_dollars(c.get("gross_inflow_cents")),
            "expenses_dollars": _cents_to_dollars(c.get("expenses_cents")),
            "net_cash_dollars": _cents_to_dollars(c.get("net_cash_cents")),
        }
        for c in data.get("companies", [])
    ]
    result = {
        "ok": True,
        "week": data.get("week"),
        "year": data.get("year"),
        "companies": companies,
        "ytd_net_dollars": _cents_to_dollars(data.get("ytd_net_cents")),
        "gap_to_1m_dollars": _cents_to_dollars(data.get("gap_to_1m_cents")),
        "instruction": (
            "Give the net cash for each business in DOLLARS (round naturally, e.g. "
            "'about seven hundred thirty four dollars'), then the year-to-date net line. "
            "Mention the gap to a million dollars ONLY if the user asks about the goal. "
            "Speak dollars, never cents. Never invent a number the tool didn't return."
        ),
    }
    logger.info(f"get_cash_pulse -> week={result['week']} companies={len(companies)}")
    await params.result_callback(result)


# ========================= list_unpaid_invoices ============================

LIST_UNPAID_INVOICES_SCHEMA = FunctionSchema(
    name="list_unpaid_invoices",
    description=(
        "List unpaid invoices — who owes money — with overdue ones called out. Use when the "
        "user asks who owes them money, for unpaid or outstanding invoices, or whether "
        "anything is overdue. Optionally filter to one business by company_id (e.g. "
        "an AutoInvoice company slug), and cap the count with limit. Report only "
        "what the tool returns."
    ),
    properties={
        "company_id": {
            "type": "string",
            "description": "Which business to filter to (an AutoInvoice company slug). Omit for all.",
        },
        "limit": {
            "type": "integer",
            "description": "Max invoices to fetch. Omit for the default (10).",
        },
    },
    required=[],
)


async def handle_list_unpaid_invoices(params: FunctionCallParams):
    query = {}
    for field in ("company_id", "limit"):
        val = params.arguments.get(field)
        if val is not None:
            query[field] = val
    try:
        status, data = await _books_request(
            "GET", "/eve/invoices/unpaid", query=query or None
        )
    except _BooksRefusal as e:
        await params.result_callback({"ok": False, "error": str(e)})
        return
    except Exception as e:
        logger.warning(f"list_unpaid_invoices failed: {e}")
        await params.result_callback({"ok": False, "error": f"could not reach AutoInvoice: {e}"})
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    invoices = [
        {
            "invoice_number": inv.get("invoice_number"),
            "customer_name": inv.get("customer_name"),
            "status": inv.get("status"),
            "total_dollars": _cents_to_dollars(inv.get("total_cents")),
            "issue_date": inv.get("issue_date"),
            "due_date": inv.get("due_date"),
            "days_overdue": inv.get("days_overdue"),
            "company_id": inv.get("company_id"),
        }
        for inv in data.get("invoices", [])
    ]
    result = {
        "ok": True,
        "count": data.get("count"),
        "total_dollars": _cents_to_dollars(data.get("total_cents")),
        "overdue_count": data.get("overdue_count"),
        "overdue_dollars": _cents_to_dollars(data.get("overdue_cents")),
        "invoices": invoices,
        "instruction": (
            "Lead with OVERDUE: how many are overdue, the total overdue in dollars, and the "
            "oldest one (customer + days overdue). Then give the remaining unpaid total in "
            "dollars. Name only the top 3 invoices by amount unless the user asks for all. "
            "Speak dollars, never cents; round naturally. If nothing is unpaid, say so plainly."
        ),
    }
    logger.info(
        f"list_unpaid_invoices -> count={result['count']} overdue={result['overdue_count']}"
    )
    await params.result_callback(result)


# ============================ lookup_customer ==============================

LOOKUP_CUSTOMER_SCHEMA = FunctionSchema(
    name="lookup_customer",
    description=(
        "Look up the story on one customer: their open balance, last invoice, and lifetime "
        "paid. Use when the user asks what's going on with a customer, whether someone owes "
        "anything, or when they were last billed. Pass EXACTLY ONE of name, email, or phone. "
        "If the result says the customer wasn't found, read back any candidates and ask which "
        "one they mean. Report only what the tool returns."
    ),
    properties={
        "name": {
            "type": "string",
            "description": "Customer name to search for, e.g. 'Browns'. Use this OR email OR phone.",
        },
        "email": {
            "type": "string",
            "description": "Customer email to search by. Use this OR name OR phone.",
        },
        "phone": {
            "type": "string",
            "description": "Customer phone to search by. Use this OR name OR email.",
        },
    },
    required=[],
)


async def handle_lookup_customer(params: FunctionCallParams):
    # Exactly one of name/email/phone. The model sometimes passes empty strings for
    # the ones it isn't using, so filter falsy values before counting.
    provided = {
        f: str(params.arguments.get(f)).strip()
        for f in ("name", "email", "phone")
        if params.arguments.get(f) and str(params.arguments.get(f)).strip()
    }
    if len(provided) != 1:
        await params.result_callback({
            "ok": False,
            "error": "lookup_customer needs exactly one of name, email, or phone — ask the user.",
        })
        return

    try:
        status, data = await _books_request(
            "GET", "/eve/customers/lookup", query=provided
        )
    except _BooksRefusal as e:
        await params.result_callback({"ok": False, "error": str(e)})
        return
    except Exception as e:
        logger.warning(f"lookup_customer failed: {e}")
        await params.result_callback({"ok": False, "error": f"could not reach AutoInvoice: {e}"})
        return

    if status != 200:
        await params.result_callback(_error_result(data, status))
        return

    if not data.get("found"):
        candidates = [c.get("name") for c in data.get("candidates", [])]
        logger.info(f"lookup_customer not found (ambiguous={data.get('ambiguous')})")
        await params.result_callback({
            "ok": True,
            "found": False,
            "ambiguous": bool(data.get("ambiguous")),
            "candidates": candidates,
            "instruction": (
                "No single customer matched. If there are candidates, read them back and ask "
                "which one the user means, then look that one up. If there are none, say you "
                "couldn't find that customer. Do NOT invent a balance or history."
            ),
        })
        return

    cust = data.get("customer", {})
    open_inv = data.get("open_invoices", {})
    last = data.get("last_invoice") or {}
    result = {
        "ok": True,
        "found": True,
        "customer": {
            "id": cust.get("id"),
            "name": cust.get("name"),
            "email": cust.get("email"),
            "phone": cust.get("phone"),
        },
        "open_balance_dollars": _cents_to_dollars(open_inv.get("total_cents")),
        "open_invoice_count": open_inv.get("count"),
        "last_invoice": {
            "invoice_number": last.get("invoice_number"),
            "status": last.get("status"),
            "total_dollars": _cents_to_dollars(last.get("total_cents")),
            "issue_date": last.get("issue_date"),
        } if last else None,
        "lifetime_paid_dollars": _cents_to_dollars(data.get("lifetime_paid_cents")),
        "instruction": (
            "Give the customer's OPEN BALANCE in dollars first, then their last invoice — "
            "number, status, and date. Mention lifetime paid only if the user asks. "
            "Speak dollars, never cents; round naturally. Never invent numbers."
        ),
    }
    logger.info(f"lookup_customer -> found {cust.get('name')!r}")
    await params.result_callback(result)


# ============================== create_lead ================================
# The ONLY write here. Confirmation staging is handled ENTIRELY by tool_policy via
# the skill frontmatter (requires_confirmation: true): the first call is frozen and
# read back, and this handler only runs on the confirmed=true release with the
# frozen args (tool_policy strips `confirmed` before it reaches us). So this handler
# just validates name+phone and POSTs.
#
# No _created_once idempotency guard (unlike invoice_tool): a lead has no client draft
# id to key on and a duplicate lead is low-harm (a dedupe on the backend, or a human,
# resolves it) — NOT a duplicate bill. If leads ever need dedupe, mirror invoice_tool's
# keying discipline rather than bolting on a looser guard.

CREATE_LEAD_SCHEMA = FunctionSchema(
    name="create_lead",
    description=(
        "Capture a new customer inquiry or lead. Use when the user says to take a lead, "
        "log a new customer inquiry, or that someone wants a quote. Gather the person's "
        "NAME and PHONE (both required); optionally their email, a short message about what "
        "they want, the project type, and which business it's for. Read the lead back to the "
        "user — name and phone at least — and only save it after they clearly say yes. This "
        "saves the lead; it does NOT send anything to the customer."
    ),
    properties={
        "name": {
            "type": "string",
            "description": "The lead's name. Required.",
        },
        "phone": {
            "type": "string",
            "description": "The lead's phone number. Required.",
        },
        "email": {
            "type": "string",
            "description": "The lead's email, if given. Optional.",
        },
        "message": {
            "type": "string",
            "description": "A short note about what they want. Optional.",
        },
        "project_type": {
            "type": "string",
            "description": "The kind of work, e.g. 'landscaping' or 'website'. Optional.",
        },
        "company_id": {
            "type": "string",
            "description": "Which business the lead is for (an AutoInvoice company slug). Optional.",
        },
        "confirmed": {
            "type": "boolean",
            "description": (
                "Set true ONLY when re-calling after the user said yes to the read-back. "
                "Omit or set false on the first call."
            ),
        },
    },
    required=["name", "phone"],
)


async def handle_create_lead(params: FunctionCallParams):
    name = str(params.arguments.get("name") or "").strip()
    phone = str(params.arguments.get("phone") or "").strip()
    # Validate BEFORE any network call — a lead with no name/phone is not worth saving,
    # and tool_policy's requires_fields is not wired for this tool, so guard here.
    if not name or not phone:
        await params.result_callback({
            "ok": False,
            "error": "create_lead needs both a name and a phone number — ask the user.",
        })
        return

    body = {
        "name": name,
        "phone": phone,
        "email": params.arguments.get("email"),
        "message": params.arguments.get("message"),
        "project_type": params.arguments.get("project_type"),
        "company_id": params.arguments.get("company_id"),
        "source": "eve",   # always tag Eve-captured leads
    }

    try:
        status, data = await _books_request("POST", "/eve/leads", body=body)
    except _BooksRefusal as e:
        await params.result_callback({"ok": False, "error": str(e)})
        return
    except Exception as e:
        logger.warning(f"create_lead failed: {e}")
        await params.result_callback({"ok": False, "error": f"could not reach AutoInvoice: {e}"})
        return

    if status != 201:
        await params.result_callback(_error_result(data, status))
        return

    result = {
        "ok": True,
        "lead_id": data.get("lead_id"),
        "status": data.get("status"),
        "name": name,
        "phone": phone,
        "company_id": body["company_id"],
        "instruction": (
            "Confirm the lead is saved. Repeat the name and phone number back so the user "
            "knows you got them right, and say which business it's filed under (or that it's "
            "unfiled if none was given). One or two short sentences."
        ),
    }
    logger.info(f"create_lead -> {result.get('lead_id')} ({name})")
    await params.result_callback(result)
