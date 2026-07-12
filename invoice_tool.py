#
# Invoicing — create DRAFT invoices via the AutoInvoice backend running
# locally on this machine (http://localhost:4000). The tool gathers
# customer + line items + optional address/date/company from the user's
# speech, reads it back for confirmation, then POSTs to the structured
# endpoint. Invoices are DRAFT only — Eve cannot send them.
#
# Two-step customer confirmation: if the customer name isn't found, the
# API returns needs="customer_confirmation" with candidates. Eve asks
# the user, then re-calls with confirm_create_customer=true.
#

import ipaddress
import json
import os
from urllib.parse import urlparse

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

# Read lazily at call time (NOT captured at import) so the headless release path and its
# tests can point AUTOINVOICE_URL/token at a real local server or a dead port without a
# module reload. The values are config, not constants.
def _autoinvoice_url() -> str:
    return os.getenv("AUTOINVOICE_URL", "http://localhost:4000").rstrip("/")


def _autoinvoice_token() -> str:
    return os.getenv("AUTOINVOICE_SERVICE_TOKEN", "")


def _is_loopback_url(url: str) -> bool:
    """True only if `url`'s host is a LITERAL loopback target: 127.0.0.0/8 (e.g.
    127.0.0.1), ::1, or the hostname 'localhost'. The AutoInvoice bearer token is
    only valid for the local AutoInvoice on this box, so it must never be attached
    to a URL pointing anywhere else. We do NOT resolve DNS — a name could resolve
    to a non-loopback address (or change), so only these literal forms count; a
    tailnet 100.x, a LAN IP, or any public host is treated as NON-loopback."""
    try:
        host = urlparse(url).hostname
    except ValueError:
        return False
    if not host:
        return False
    host = host.lower()
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


# Created-once idempotency (mirrors sms_tool._sent_once). create_invoice is a money path:
# tool_policy stages a frozen draft and a confirmed=true re-call releases it, single-fire
# PER STAGE — but the model can RE-STAGE the identical invoice (a post-denial / threshold
# retry, a "do it again" turn) and confirm a second time, which POSTed a SECOND invoice to
# AutoInvoice (a duplicate bill). The confirmation GATE was single-fire; the EXECUTE step
# was not. Keying off the frozen draft makes a successful create fire AT MOST ONCE per draft.
# A re-stage after a needs='customer_confirmation' (or any non-201) result never adds the
# key, so the genuine follow-up still creates. Process/session scoped, like the SMS guard.
_created_once: dict[str, dict] = {}


def _draft_key(body: dict) -> str:
    """Stable dedupe key for a staged invoice draft. There is no client-supplied draft id,
    so the canonical (sorted) JSON of the request body IS the identity of what gets billed.
    The `confirmed` flag is already stripped from the frozen draft by tool_policy before the
    handler sees it, so equal bills hash equal."""
    return json.dumps(body, sort_keys=True, default=str)


async def _post_structured_invoice(body: dict, token: str) -> tuple[int, dict]:
    """The single outbound seam to AutoInvoice's structured endpoint. Returns
    (status, data). Isolated so the created-once guard and tests wrap exactly one
    network call — mirrors sms_tool.send_sms."""
    timeout = aiohttp.ClientTimeout(total=15)
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
    }
    async with aiohttp.ClientSession(timeout=timeout) as session:
        async with session.post(
            f"{_autoinvoice_url()}/invoices/structured",
            json=body,
            headers=headers,
        ) as resp:
            return resp.status, await resp.json()

CREATE_INVOICE_SCHEMA = FunctionSchema(
    name="create_invoice",
    description=(
        "Create a DRAFT invoice. First gather customer, line items (description, "
        "quantity, rate in DOLLARS), and optional service address, date, and business. "
        "The FIRST call returns a draft preview — it does NOT create the invoice yet. "
        "Read the full draft back to the user (customer, each line as qty x rate, total, "
        "address, date, business). Only after the user clearly says yes, call create_invoice "
        "AGAIN with confirmed set to true to actually create it. Invoices are DRAFT — never "
        "say one was sent. If a result returns needs='customer_confirmation', the customer "
        "wasn't found: ask the user, then call again with confirm_create_customer=true using "
        "the same figures, read the new draft back, and get a fresh yes before confirming. "
        "Never invent a price."
    ),
    properties={
        "customer": {
            "type": "object",
            "description": 'Who to bill. Either {"name": "Browns"} or {"customer_id": "..."}.',
        },
        "line_items": {
            "type": "array",
            "description": (
                'What to bill for. Each item: {"description": "Mowing", "quantity": 3, "rate": 50}. '
                "Rate is in DOLLARS (not cents)."
            ),
        },
        "service_address": {
            "type": "string",
            "description": "Job location where the work happened. Optional.",
        },
        "service_date": {
            "type": "string",
            "description": "Date of service, YYYY-MM-DD. Default today.",
        },
        "company_id": {
            "type": "string",
            "description": "Which business (an AutoInvoice company slug). Omit to use your AutoInvoice default company.",
        },
        "confirm_create_customer": {
            "type": "boolean",
            "description": "Set true ONLY after the user approves creating a new customer.",
        },
        "confirmed": {
            "type": "boolean",
            "description": (
                "Set true ONLY when re-calling after the user said yes to the draft "
                "read-back. Omit or set false on the first call."
            ),
        },
    },
    required=["customer", "line_items"],
)


async def handle_create_invoice(params: FunctionCallParams):
    token = _autoinvoice_token()
    if not token:
        await params.result_callback(
            {"ok": False, "error": "AutoInvoice token not configured — set AUTOINVOICE_SERVICE_TOKEN in .env"}
        )
        return

    # Refuse to send the service token off-box. The bearer is only valid for the
    # local AutoInvoice; a misconfigured/non-loopback AUTOINVOICE_URL would leak it.
    # Sending the invoice unauthenticated to a non-loopback host would be wrong too,
    # so refuse outright rather than attach the token or post without it.
    url = _autoinvoice_url()
    if not _is_loopback_url(url):
        logger.warning(
            f"create_invoice refused: AUTOINVOICE_URL is not loopback ({url!r}) — "
            "not sending the service token off-box"
        )
        await params.result_callback(
            {"ok": False,
             "error": "AutoInvoice URL is not loopback — refusing to send the service token off-box"}
        )
        return

    body = {}
    for field in ("customer", "line_items", "service_address", "service_date", "company_id", "confirm_create_customer"):
        val = params.arguments.get(field)
        if val is not None:
            body[field] = val

    if not body.get("customer") or not body.get("line_items"):
        await params.result_callback(
            {"ok": False, "error": "customer and at least one line item are required"}
        )
        return

    # Created-once guard: if this EXACT draft already produced an invoice (e.g. the model
    # re-staged the same bill after a denial and confirmed again), do NOT create a second
    # one — report the existing invoice. Set only AFTER AutoInvoice returns 201 below.
    key = _draft_key(body)
    prior = _created_once.get(key)
    if prior is not None:
        logger.info(f"create_invoice: draft already created ({prior.get('invoice_number')}) — no re-create")
        await params.result_callback(
            {**prior, "already_created": True,
             "instruction": (
                 "This exact invoice was already created — tell the user it's done (give the "
                 "number and total). Do NOT create it again; remind them it's a DRAFT to send "
                 "from the dashboard. Never say it was sent."
             )}
        )
        return

    try:
        status, data = await _post_structured_invoice(body, token)
    except Exception as e:
        logger.warning(f"create_invoice failed: {e}")
        await params.result_callback(
            {"ok": False, "error": f"could not reach AutoInvoice: {e}"}
        )
        return

    if status == 201:
        total_dollars = data.get("total_cents", 0) / 100
        logger.info(
            f"invoice created: {data.get('invoice_number')} "
            f"${total_dollars:.2f} for {data.get('customer', {}).get('name')}"
        )
        result = {
            "ok": True,
            "invoice_number": data.get("invoice_number"),
            "status": "DRAFT",
            "customer": data.get("customer", {}).get("name"),
            "company": data.get("company_id"),
            "total_dollars": total_dollars,
            "line_items": [
                {
                    "description": li.get("description"),
                    "quantity": li.get("quantity"),
                    "rate_dollars": li.get("rate_cents", 0) / 100,
                    "amount_dollars": li.get("amount_cents", 0) / 100,
                }
                for li in data.get("line_items", [])
            ],
            "instruction": (
                "Tell the user: draft invoice created with the number and total in dollars. "
                "Remind them it's a DRAFT to review and send from the dashboard. "
                "Never say it was sent."
            ),
        }
        # Mark created-once ONLY after AutoInvoice accepted it, so a failed/needs-confirm
        # attempt above never suppresses a genuine retry. A later re-stage of this exact
        # draft returns this stored result instead of billing the customer again.
        _created_once[key] = result
        await params.result_callback(result)
        return

    if status == 200 and data.get("needs") == "customer_confirmation":
        candidates = [c.get("name") for c in data.get("candidates", [])]
        logger.info(f"invoice needs customer confirmation for {data.get('query')!r}")
        await params.result_callback(
            {
                "ok": False,
                "needs_confirmation": True,
                "query": data.get("query"),
                "candidates": candidates,
                "instruction": (
                    "The customer wasn't found. Tell the user the name you searched for. "
                    "If there are candidates, list them and ask which one. If none, ask "
                    "if you should create a new customer with that name. Only re-call "
                    "with confirm_create_customer=true after they say yes."
                ),
            }
        )
        return

    error_msg = data.get("error") or data.get("message") or f"HTTP {status}"
    logger.warning(f"create_invoice error: {status} {error_msg}")
    await params.result_callback({"ok": False, "error": str(error_msg)[:200]})
