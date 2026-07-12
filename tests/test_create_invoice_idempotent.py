# tests/test_create_invoice_idempotent.py
#
# Money-path twin of the SMS double-send bug (2026-06-22). create_invoice is a
# needs_confirmation tool: tool_policy stages a frozen draft and a confirmed=true
# re-call releases it. The stage is consumed before the await, so ONE context's
# single confirm fires at most once — but the model can RE-STAGE the identical
# invoice (a post-denial / threshold-lower retry, or a "do it again" turn) and
# confirm a second time, which POSTed a SECOND invoice to AutoInvoice. On the
# money path that is a duplicate bill.
#
# The same bug class the SMS fix closed: the confirmation GATE is single-fire per
# stage, but nothing on the EXECUTE step remembered that this exact draft already
# created an invoice. These tests pin a sent-once guard keyed to the successfully
# created draft, set ONLY after AutoInvoice returns 201:
#   - a re-stage + re-confirm of an ALREADY-CREATED invoice must NOT POST again;
#   - a re-stage after a needs_confirmation (customer-not-found) result MUST still
#     create, because nothing was actually created the first time;
#   - a genuinely different invoice still creates normally.
from dataclasses import dataclass
from typing import Callable, Optional

import pytest

import invoice_tool


@dataclass
class FakeParams:
    arguments: dict
    delivered: object = None
    result_callback: Optional[Callable] = None

    def __post_init__(self):
        if self.result_callback is None:
            async def _capture(result, **kwargs):
                self.delivered = result
            self.result_callback = _capture


@pytest.fixture(autouse=True)
def _reset_invoice_state(monkeypatch):
    if hasattr(invoice_tool, "_created_once"):
        invoice_tool._created_once.clear()
    monkeypatch.setenv("AUTOINVOICE_SERVICE_TOKEN", "test-token")
    yield


def _invoice_args():
    return {
        "customer": {"name": "Browns"},
        "line_items": [{"description": "Mowing", "quantity": 3, "rate": 50}],
        "company_id": "field-services",
    }


def _ok_201(invoice_number="INV-1001"):
    return 201, {
        "invoice_number": invoice_number,
        "total_cents": 15000,
        "customer": {"name": "Browns"},
        "company_id": "field-services",
        "line_items": [
            {"description": "Mowing", "quantity": 3, "rate_cents": 5000, "amount_cents": 15000}
        ],
    }


@pytest.mark.asyncio
async def test_repeated_create_of_same_draft_posts_only_once(monkeypatch):
    """The money double-create repro: the SAME invoice draft is released twice
    (re-stage + re-confirm). AutoInvoice must be POSTed EXACTLY ONCE; the second
    release reports the existing invoice rather than billing the customer twice."""
    posts: list[dict] = []

    async def fake_post(body, token):
        posts.append(dict(body))
        return _ok_201()

    monkeypatch.setattr(invoice_tool, "_post_structured_invoice", fake_post)

    p1 = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p1)
    assert p1.delivered["ok"] is True
    assert p1.delivered["invoice_number"] == "INV-1001"

    # Re-stage of the identical invoice + a second confirm.
    p2 = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p2)

    assert len(posts) == 1, f"invoice double-created: {posts!r}"
    assert p2.delivered["ok"] is True
    assert p2.delivered.get("already_created") is True
    assert p2.delivered["invoice_number"] == "INV-1001"


@pytest.mark.asyncio
async def test_restage_after_customer_confirmation_still_creates(monkeypatch):
    """A re-stage after a needs='customer_confirmation' result MUST create: the
    first call created NOTHING, so the guard must not suppress the real follow-up."""
    posts: list[dict] = []

    async def fake_post(body, token):
        posts.append(dict(body))
        if not body.get("confirm_create_customer"):
            return 200, {"needs": "customer_confirmation", "query": "Browns",
                         "candidates": [{"name": "Brown Co"}]}
        return _ok_201()

    monkeypatch.setattr(invoice_tool, "_post_structured_invoice", fake_post)

    p1 = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p1)
    assert p1.delivered.get("needs_confirmation") is True   # nothing created yet

    p2 = FakeParams({**_invoice_args(), "confirm_create_customer": True})
    await invoice_tool.handle_create_invoice(p2)

    assert len(posts) == 2                                   # the real create ran
    assert p2.delivered["ok"] is True
    assert p2.delivered["invoice_number"] == "INV-1001"


@pytest.mark.asyncio
async def test_different_invoice_still_creates(monkeypatch):
    """A genuinely different invoice (different figures) still creates normally."""
    posts: list[dict] = []
    counter = {"n": 0}

    async def fake_post(body, token):
        posts.append(dict(body))
        counter["n"] += 1
        return _ok_201(invoice_number=f"INV-200{counter['n']}")

    monkeypatch.setattr(invoice_tool, "_post_structured_invoice", fake_post)

    p1 = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p1)
    assert p1.delivered["ok"] is True

    other = _invoice_args()
    other["line_items"][0]["quantity"] = 9          # different bill
    p2 = FakeParams(other)
    await invoice_tool.handle_create_invoice(p2)

    assert len(posts) == 2
    assert p2.delivered["ok"] is True
    assert p2.delivered["invoice_number"] == "INV-2002"


@pytest.mark.asyncio
async def test_failed_create_is_not_guarded(monkeypatch):
    """If AutoInvoice did NOT return 201 (error), the draft is NOT marked created —
    a genuine retry must be able to create. (Guard set only after real success.)"""
    posts: list[dict] = []
    state = {"n": 0}

    async def fake_post(body, token):
        posts.append(dict(body))
        state["n"] += 1
        if state["n"] == 1:
            return 500, {"error": "autoinvoice down"}
        return _ok_201()

    monkeypatch.setattr(invoice_tool, "_post_structured_invoice", fake_post)

    p1 = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p1)
    assert p1.delivered["ok"] is False                       # failed, not guarded

    p2 = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p2)
    assert len(posts) == 2                                   # retry created
    assert p2.delivered["ok"] is True
