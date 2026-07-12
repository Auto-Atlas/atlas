# tests/test_invoice_loopback_token.py
#
# Security: the AutoInvoice bearer (AUTOINVOICE_SERVICE_TOKEN) is only valid for the
# local AutoInvoice on this box. Codex audit (TODO.md:134): the token was attached to
# whatever AUTOINVOICE_URL was configured, so a misconfigured/non-loopback URL would
# leak it off-box. These tests pin the guard:
#   - a LOOPBACK url (127.0.0.1 / localhost) → bearer IS attached, invoice POSTs normally;
#   - a NON-loopback url (tailnet 100.x, public host) → REFUSED, no POST, token never sent;
#   - _is_loopback_url literal cases.
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


# --- _is_loopback_url unit cases -------------------------------------------------

@pytest.mark.parametrize("url,expected", [
    ("http://127.0.0.1:4000", True),
    ("http://127.0.0.1", True),
    ("http://127.5.6.7:4000", True),          # all of 127.0.0.0/8 is loopback
    ("http://localhost:4000", True),
    ("http://LocalHost:4000", True),          # case-insensitive
    ("http://[::1]:4000", True),
    ("http://203.0.113.80:4000", False),     # routable — NON-loopback
    ("http://192.168.1.10:4000", False),      # LAN — NON-loopback
    ("http://evil.example.com", False),       # public host
    ("http://example.com:4000", False),
    ("", False),                              # empty
    ("not a url", False),
])
def test_is_loopback_url(url, expected):
    assert invoice_tool._is_loopback_url(url) is expected


# --- loopback URL: bearer attached, POST happens --------------------------------

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
    """Captures the headers passed to session.post so the test can assert the
    Authorization bearer was (or was not) attached to the real outbound request."""
    captured: dict = {}

    def __init__(self, *a, **kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def post(self, url, json=None, headers=None):
        _FakeSession.captured = {"url": url, "json": json, "headers": dict(headers or {})}
        return _FakeResp(201, {
            "invoice_number": "INV-1001",
            "total_cents": 15000,
            "customer": {"name": "Browns"},
            "company_id": "field-services",
            "line_items": [
                {"description": "Mowing", "quantity": 3, "rate_cents": 5000, "amount_cents": 15000}
            ],
        })


@pytest.mark.asyncio
@pytest.mark.parametrize("url", ["http://127.0.0.1:4000", "http://localhost:4000"])
async def test_loopback_attaches_bearer_and_posts(monkeypatch, url):
    """Loopback AUTOINVOICE_URL → the bearer IS attached on the real request and the
    invoice POSTs normally (unchanged behavior). Mocks aiohttp so nothing hits a
    real service, and asserts on the headers the handler actually sent."""
    monkeypatch.setenv("AUTOINVOICE_URL", url)
    _FakeSession.captured = {}
    monkeypatch.setattr(invoice_tool.aiohttp, "ClientSession", _FakeSession)

    p = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p)

    assert p.delivered["ok"] is True
    assert p.delivered["invoice_number"] == "INV-1001"
    # The token left the box exactly as before — to the loopback AutoInvoice.
    assert _FakeSession.captured["headers"].get("Authorization") == "Bearer test-token"
    assert _FakeSession.captured["url"].startswith(url)


# --- non-loopback URL: REFUSED, token never sent --------------------------------

@pytest.mark.asyncio
@pytest.mark.parametrize("url", [
    "http://203.0.113.80:4000",   # routable — NON-loopback
    "http://evil.example.com",     # public host
])
async def test_non_loopback_refuses_and_never_sends_token(monkeypatch, url):
    """Non-loopback AUTOINVOICE_URL → REFUSE with an honest error and NEVER attach
    or send the service token. The HTTP client must not be touched at all; if it is,
    the test fails (the token would have left the box)."""
    monkeypatch.setenv("AUTOINVOICE_URL", url)

    def _boom(*a, **kw):
        raise AssertionError("aiohttp.ClientSession created — token would have left the box!")

    monkeypatch.setattr(invoice_tool.aiohttp, "ClientSession", _boom)

    p = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p)

    assert p.delivered["ok"] is False
    assert "loopback" in p.delivered["error"].lower()
    # honest about why, and the token string never appears in the result
    assert "test-token" not in str(p.delivered)


@pytest.mark.asyncio
async def test_non_loopback_does_not_post(monkeypatch):
    """Belt-and-suspenders: even if _post_structured_invoice were reachable, a
    non-loopback URL must never call it (no POST carrying the token)."""
    monkeypatch.setenv("AUTOINVOICE_URL", "http://203.0.113.80:4000")
    posts: list = []

    async def fake_post(body, token):
        posts.append((body, token))
        return 201, {}

    monkeypatch.setattr(invoice_tool, "_post_structured_invoice", fake_post)

    p = FakeParams(_invoice_args())
    await invoice_tool.handle_create_invoice(p)

    assert posts == []                       # token never POSTed
    assert p.delivered["ok"] is False
