# tests/test_books_tool.py
#
# books_tool is Eve's window into the AutoInvoice backend: get_cash_pulse,
# list_unpaid_invoices, lookup_customer (reads) and create_lead (the one write).
# All four carry the AutoInvoice service bearer, valid ONLY for the local
# AutoInvoice. These tests mirror test_invoice_loopback_token.py:
#   - a NON-loopback AUTOINVOICE_URL  -> REFUSED, no network call, token never sent;
#   - a MISSING token                 -> REFUSED, no network call;
#   - each tool's happy path over a fake aiohttp (asserts bearer, path, query/body);
#   - a backend 500 / {"error"}       -> ok:False with a truncated error;
#   - create_lead missing phone       -> ok:False without a network call.
from dataclasses import dataclass
from typing import Callable, Optional

import pytest

import books_tool


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
def _books_env(monkeypatch):
    # Default every test to a configured token + loopback URL; individual tests
    # override to exercise the refusal paths.
    monkeypatch.setenv("AUTOINVOICE_SERVICE_TOKEN", "test-token")
    monkeypatch.setenv("AUTOINVOICE_URL", "http://127.0.0.1:4000")
    yield


# ---- fake aiohttp: captures the request the handler actually made ---------------

class _FakeResp:
    def __init__(self, status, data):
        self.status = status
        self._data = data

    async def json(self):
        return self._data

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakeSession:
    """Captures the (method, url, params, json, headers) passed to session.request
    so tests can assert the bearer, path, and query/body shape. Returns a canned
    (status, data)."""
    captured: dict = {}
    response: tuple = (200, {})

    def __init__(self, *a, **kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def request(self, method, url, params=None, json=None, headers=None):
        _FakeSession.captured = {
            "method": method,
            "url": url,
            "params": params,
            "json": json,
            "headers": dict(headers or {}),
        }
        return _FakeResp(*_FakeSession.response)


def _install_fake(monkeypatch, status, data):
    _FakeSession.captured = {}
    _FakeSession.response = (status, data)
    monkeypatch.setattr(books_tool.aiohttp, "ClientSession", _FakeSession)


def _boom_session(monkeypatch):
    """Any construction of a ClientSession fails the test — the token would have
    left the box."""
    def _boom(*a, **kw):
        raise AssertionError("aiohttp.ClientSession created — token would have left the box!")
    monkeypatch.setattr(books_tool.aiohttp, "ClientSession", _boom)


# The four (handler, minimal-args) pairs, for the guard tests that hit every tool.
_ALL_TOOLS = [
    (books_tool.handle_get_cash_pulse, {}),
    (books_tool.handle_list_unpaid_invoices, {}),
    (books_tool.handle_lookup_customer, {"name": "Browns"}),
    (books_tool.handle_create_lead, {"name": "Jo", "phone": "555-1212"}),
]


# ---- loopback guard: non-loopback URL -> refused, token never sent --------------

@pytest.mark.asyncio
@pytest.mark.parametrize("url", [
    "http://203.0.113.80:4000",   # routable — NON-loopback
    "http://evil.example.com",     # public host
])
@pytest.mark.parametrize("handler,args", _ALL_TOOLS)
async def test_non_loopback_refuses_and_never_sends_token(monkeypatch, url, handler, args):
    monkeypatch.setenv("AUTOINVOICE_URL", url)
    _boom_session(monkeypatch)

    p = FakeParams(dict(args))
    await handler(p)

    assert p.delivered["ok"] is False
    assert "loopback" in p.delivered["error"].lower()
    assert "test-token" not in str(p.delivered)   # token string never leaks into the result


# ---- missing token -> refused, no network call ----------------------------------

@pytest.mark.asyncio
@pytest.mark.parametrize("handler,args", _ALL_TOOLS)
async def test_missing_token_refuses(monkeypatch, handler, args):
    monkeypatch.delenv("AUTOINVOICE_SERVICE_TOKEN", raising=False)
    _boom_session(monkeypatch)   # a refusal must happen before any session is built

    p = FakeParams(dict(args))
    await handler(p)

    assert p.delivered["ok"] is False
    assert "token" in p.delivered["error"].lower()


# ---- happy paths: assert bearer header, method, path, query/body ----------------

@pytest.mark.asyncio
async def test_cash_pulse_happy_path(monkeypatch):
    _install_fake(monkeypatch, 200, {
        "week": 27, "year": 2026,
        "companies": [{
            "id": "field-services", "name": "Field Services Co",
            "gross_inflow_cents": 123400, "expenses_cents": 50000, "net_cash_cents": 73400,
        }],
        "ytd_net_cents": 5000000, "gap_to_1m_cents": 95000000,
    })

    p = FakeParams({"week": 27, "year": 2026})
    await books_tool.handle_get_cash_pulse(p)

    cap = _FakeSession.captured
    assert cap["method"] == "GET"
    assert cap["url"] == "http://127.0.0.1:4000/eve/pulse"
    assert cap["params"] == {"week": 27, "year": 2026}
    assert cap["headers"].get("Authorization") == "Bearer test-token"

    assert p.delivered["ok"] is True
    assert p.delivered["week"] == 27
    # cents -> dollars conversion
    assert p.delivered["companies"][0]["net_cash_dollars"] == 734.0
    assert p.delivered["ytd_net_dollars"] == 50000.0
    assert p.delivered["gap_to_1m_dollars"] == 950000.0


@pytest.mark.asyncio
async def test_cash_pulse_no_args_omits_query(monkeypatch):
    _install_fake(monkeypatch, 200, {"week": 27, "year": 2026, "companies": [],
                                     "ytd_net_cents": 0, "gap_to_1m_cents": 0})
    p = FakeParams({})
    await books_tool.handle_get_cash_pulse(p)
    # no week/year -> no query params at all (current week on the backend)
    assert _FakeSession.captured["params"] is None
    assert p.delivered["ok"] is True


@pytest.mark.asyncio
async def test_unpaid_invoices_happy_path(monkeypatch):
    _install_fake(monkeypatch, 200, {
        "count": 3, "total_cents": 45000,
        "overdue_count": 1, "overdue_cents": 15000,
        "invoices": [{
            "invoice_number": "INV-0012", "customer_name": "Browns", "status": "OVERDUE",
            "total_cents": 15000, "issue_date": "2026-06-01", "due_date": "2026-06-15",
            "days_overdue": 20, "company_id": "field-services",
        }],
    })

    p = FakeParams({"company_id": "field-services", "limit": 5})
    await books_tool.handle_list_unpaid_invoices(p)

    cap = _FakeSession.captured
    assert cap["method"] == "GET"
    assert cap["url"] == "http://127.0.0.1:4000/eve/invoices/unpaid"
    assert cap["params"] == {"company_id": "field-services", "limit": 5}
    assert cap["headers"].get("Authorization") == "Bearer test-token"

    assert p.delivered["ok"] is True
    assert p.delivered["overdue_dollars"] == 150.0
    assert p.delivered["total_dollars"] == 450.0
    assert p.delivered["invoices"][0]["total_dollars"] == 150.0


@pytest.mark.asyncio
async def test_lookup_customer_found(monkeypatch):
    _install_fake(monkeypatch, 200, {
        "found": True,
        "customer": {"id": "c1", "name": "Browns", "email": None, "phone": None},
        "open_invoices": {"count": 1, "total_cents": 15000},
        "last_invoice": {"invoice_number": "INV-0012", "status": "OVERDUE",
                         "total_cents": 15000, "issue_date": "2026-06-01"},
        "lifetime_paid_cents": 230000,
    })

    p = FakeParams({"name": "Browns"})
    await books_tool.handle_lookup_customer(p)

    cap = _FakeSession.captured
    assert cap["url"] == "http://127.0.0.1:4000/eve/customers/lookup"
    assert cap["params"] == {"name": "Browns"}   # exactly one lookup key sent
    assert cap["headers"].get("Authorization") == "Bearer test-token"

    assert p.delivered["ok"] is True
    assert p.delivered["found"] is True
    assert p.delivered["open_balance_dollars"] == 150.0
    assert p.delivered["last_invoice"]["invoice_number"] == "INV-0012"
    assert p.delivered["lifetime_paid_dollars"] == 2300.0


@pytest.mark.asyncio
async def test_lookup_customer_not_found_reads_candidates(monkeypatch):
    _install_fake(monkeypatch, 200, {
        "found": False, "ambiguous": True,
        "candidates": [{"id": "c1", "name": "Brown Bros"}, {"id": "c2", "name": "Browne"}],
    })

    p = FakeParams({"name": "Brown"})
    await books_tool.handle_lookup_customer(p)

    assert p.delivered["ok"] is True
    assert p.delivered["found"] is False
    assert p.delivered["ambiguous"] is True
    assert p.delivered["candidates"] == ["Brown Bros", "Browne"]


@pytest.mark.asyncio
async def test_lookup_customer_requires_exactly_one_key(monkeypatch):
    _boom_session(monkeypatch)   # must reject before any network call
    # two keys -> refuse
    p = FakeParams({"name": "Browns", "email": "a@b.com"})
    await books_tool.handle_lookup_customer(p)
    assert p.delivered["ok"] is False
    assert "exactly one" in p.delivered["error"].lower()
    # zero keys -> refuse
    p2 = FakeParams({})
    await books_tool.handle_lookup_customer(p2)
    assert p2.delivered["ok"] is False


@pytest.mark.asyncio
async def test_create_lead_happy_path(monkeypatch):
    _install_fake(monkeypatch, 201, {"lead_id": "lead_1", "status": "NEW"})

    p = FakeParams({
        "name": "Jane Doe", "phone": "555-9999",
        "email": None, "project_type": "landscaping", "company_id": "field-services",
    })
    await books_tool.handle_create_lead(p)

    cap = _FakeSession.captured
    assert cap["method"] == "POST"
    assert cap["url"] == "http://127.0.0.1:4000/eve/leads"
    assert cap["headers"].get("Authorization") == "Bearer test-token"
    # body carries name/phone and ALWAYS source="eve"
    assert cap["json"]["name"] == "Jane Doe"
    assert cap["json"]["phone"] == "555-9999"
    assert cap["json"]["source"] == "eve"
    assert cap["json"]["company_id"] == "field-services"

    assert p.delivered["ok"] is True
    assert p.delivered["lead_id"] == "lead_1"
    assert p.delivered["status"] == "NEW"


@pytest.mark.asyncio
async def test_create_lead_missing_phone_no_network(monkeypatch):
    _boom_session(monkeypatch)   # validation must happen before any session is built

    p = FakeParams({"name": "Jane Doe"})   # no phone
    await books_tool.handle_create_lead(p)

    assert p.delivered["ok"] is False
    assert "phone" in p.delivered["error"].lower()


# ---- backend error: non-2xx -> ok:False with a truncated error ------------------

@pytest.mark.asyncio
@pytest.mark.parametrize("handler,args", _ALL_TOOLS)
async def test_backend_error_returns_truncated(monkeypatch, handler, args):
    long_msg = "boom " * 100   # 500 chars -> must be truncated to <= 200
    # reads return 500; the write's error branch is any non-201, so 500 works there too
    _install_fake(monkeypatch, 500, {"error": long_msg})

    p = FakeParams(dict(args))
    await handler(p)

    assert p.delivered["ok"] is False
    assert p.delivered["error"].startswith("boom")
    assert len(p.delivered["error"]) <= 200
