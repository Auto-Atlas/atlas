# tests/test_wealth_tool.py
#
# wealth_tool is Eve's READ window into the Wealth OS dashboard (Next.js on
# localhost:3100): get_wealth_summary, get_planned_purchases,
# get_budget_envelope, get_goal_scorecard. No bearer, no loopback guard — the
# GET routes are unauthenticated and carry no secret. These tests mock httpx and
# assert, per tool:
#   - the happy path (URL, query params, and the dollar-converted result);
#   - the dashboard being DOWN -> the honest "isn't running" instruction;
#   - a malformed/partial response -> graceful degradation, never a crash;
#   - a backend 500 -> ok:False with a truncated error;
#   - unknown-business validation without a network call.
from dataclasses import dataclass
from typing import Callable, Optional

import httpx
import pytest

import wealth_tool


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


# ---- fake httpx.AsyncClient: captures the request, returns canned responses ----

class _FakeResp:
    def __init__(self, status, data, raise_json=False):
        self.status_code = status
        self._data = data
        self._raise = raise_json

    def json(self):
        if self._raise:
            raise ValueError("not json")
        return self._data


class _FakeClient:
    """Serves a queue of canned responses; each queue item is either a
    (status, data[, raise_json]) tuple or an Exception instance to raise (used to
    simulate the dashboard being down). Captures the last request."""
    captured: dict = {}
    queue: list = []

    def __init__(self, *a, **kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def get(self, url, params=None):
        _FakeClient.captured = {"url": url, "params": params}
        item = _FakeClient.queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return _FakeResp(*item)


def _install(monkeypatch, *responses):
    _FakeClient.captured = {}
    _FakeClient.queue = list(responses)
    monkeypatch.setattr(wealth_tool.httpx, "AsyncClient", _FakeClient)


def _boom(monkeypatch):
    """Any client construction raises — proves no network call was attempted."""
    def _raise(*a, **kw):
        raise AssertionError("httpx.AsyncClient created — a network call was attempted!")
    monkeypatch.setattr(wealth_tool.httpx, "AsyncClient", _raise)


@pytest.fixture(autouse=True)
def _wealth_env(monkeypatch):
    monkeypatch.setenv("WEALTH_OS_URL", "http://localhost:3100")
    # Slugs are config now (WEALTH_OS_BUSINESS_SLUGS), never baked in — set the same
    # three these tests exercise so existing behavior is unchanged. The unconfigured
    # path has its own tests below that clear this.
    monkeypatch.setenv("WEALTH_OS_BUSINESS_SLUGS",
                       "acme-farms,acme-web,acme-robotics")
    yield


# ================================ get_wealth_summary ============================

@pytest.mark.asyncio
async def test_wealth_summary_happy(monkeypatch):
    _install(monkeypatch, (200, {
        "week": 27, "year": 2026,
        "ytd_net_cents": 1234000,
        "gap_to_1m_cents": 98766000,
        "companies": [
            {"id": "acme-farms", "name": "Acme Farms", "net_cash_cents": 73400},
        ],
    }))
    p = FakeParams({})
    await wealth_tool.handle_get_wealth_summary(p)
    assert p.delivered["ok"] is True
    assert p.delivered["ytd_net_dollars"] == 12340.0
    assert p.delivered["gap_to_1m_dollars"] == 987660.0
    assert p.delivered["companies"][0]["net_cash_dollars"] == 734.0
    assert _FakeClient.captured["url"] == "http://localhost:3100/api/pulse"


@pytest.mark.asyncio
async def test_wealth_summary_passes_week_year(monkeypatch):
    _install(monkeypatch, (200, {"companies": []}))
    await wealth_tool.handle_get_wealth_summary(FakeParams({"week": 5, "year": 2025}))
    assert _FakeClient.captured["params"] == {"week": 5, "year": 2025}


@pytest.mark.asyncio
async def test_wealth_summary_no_gap_when_absent(monkeypatch):
    # AutoInvoice not wired -> no gap_to_1m_cents; the tool must not fabricate one.
    _install(monkeypatch, (200, {"ytd_net_cents": 0, "companies": []}))
    p = FakeParams({})
    await wealth_tool.handle_get_wealth_summary(p)
    assert p.delivered["gap_to_1m_dollars"] is None


@pytest.mark.asyncio
async def test_wealth_summary_down(monkeypatch):
    _install(monkeypatch, httpx.ConnectError("connection refused"))
    p = FakeParams({})
    await wealth_tool.handle_get_wealth_summary(p)
    assert p.delivered["ok"] is False
    assert p.delivered["error"] == "Wealth OS dashboard isn't running"
    assert "isn't running" in p.delivered["instruction"]


@pytest.mark.asyncio
async def test_wealth_summary_malformed(monkeypatch):
    # 200 but the body isn't JSON -> data={} -> graceful zeros, no crash.
    _install(monkeypatch, (200, None, True))
    p = FakeParams({})
    await wealth_tool.handle_get_wealth_summary(p)
    assert p.delivered["ok"] is True
    assert p.delivered["ytd_net_dollars"] == 0
    assert p.delivered["companies"] == []


@pytest.mark.asyncio
async def test_wealth_summary_http_500(monkeypatch):
    _install(monkeypatch, (500, {"error": {"message": "MCP error"}}))
    p = FakeParams({})
    await wealth_tool.handle_get_wealth_summary(p)
    assert p.delivered["ok"] is False
    assert p.delivered["error"] == "MCP error"


# ============================== get_planned_purchases ==========================

@pytest.mark.asyncio
async def test_planned_purchases_happy(monkeypatch):
    _install(monkeypatch, (200, {
        "count": 2,
        "purchases": [
            {"company": "acme-farms", "category": "equipment",
             "total_cost_usd": 450.0, "status": "planned", "rationale": "tractor part"},
            {"company": "acme-web", "category": "software",
             "total_cost_usd": 20.0, "status": "planned", "rationale": None},
        ],
    }))
    p = FakeParams({})
    await wealth_tool.handle_get_planned_purchases(p)
    assert p.delivered["ok"] is True
    assert p.delivered["count"] == 2
    assert p.delivered["total_planned_usd"] == 470.0
    # status=planned filter always sent
    assert _FakeClient.captured["params"]["status"] == "planned"


@pytest.mark.asyncio
async def test_planned_purchases_business_filter(monkeypatch):
    _install(monkeypatch, (200, {"purchases": []}))
    await wealth_tool.handle_get_planned_purchases(FakeParams({"business": "acme-robotics"}))
    assert _FakeClient.captured["params"]["company"] == "acme-robotics"


@pytest.mark.asyncio
async def test_planned_purchases_unknown_business_no_call(monkeypatch):
    _boom(monkeypatch)
    p = FakeParams({"business": "acme-corp"})
    await wealth_tool.handle_get_planned_purchases(p)
    assert p.delivered["ok"] is False
    assert "unknown business" in p.delivered["error"]


@pytest.mark.asyncio
async def test_planned_purchases_down(monkeypatch):
    _install(monkeypatch, httpx.ReadTimeout("timeout"))
    p = FakeParams({})
    await wealth_tool.handle_get_planned_purchases(p)
    assert p.delivered["error"] == "Wealth OS dashboard isn't running"


@pytest.mark.asyncio
async def test_planned_purchases_no_slugs_configured_fails_loud(monkeypatch):
    # WEALTH_OS_BUSINESS_SLUGS unset -> validation must FAIL LOUDLY (actionable
    # message naming the env var), never silently query with an unvetted slug.
    monkeypatch.delenv("WEALTH_OS_BUSINESS_SLUGS", raising=False)
    _boom(monkeypatch)
    p = FakeParams({"business": "acme-farms"})
    await wealth_tool.handle_get_planned_purchases(p)
    assert p.delivered["ok"] is False
    assert "no business slugs configured" in p.delivered["error"]
    assert "WEALTH_OS_BUSINESS_SLUGS" in p.delivered["error"]


# =============================== get_budget_envelope ==========================

@pytest.mark.asyncio
async def test_budget_envelope_happy(monkeypatch):
    _install(monkeypatch, (200, {
        "count": 2,
        "envelopes": [
            {"company": "acme-farms", "category": "equipment",
             "allocated_usd": 1000, "committed_usd": 450, "available_usd": 550},
            {"company": "acme-farms", "category": "software",
             "allocated_usd": 100, "committed_usd": 20, "available_usd": 80},
        ],
    }))
    p = FakeParams({"business": "acme-farms"})
    await wealth_tool.handle_get_budget_envelope(p)
    assert p.delivered["ok"] is True
    assert p.delivered["total_available_usd"] == 630
    assert _FakeClient.captured["params"] == {"business": "acme-farms"}


@pytest.mark.asyncio
async def test_budget_envelope_missing_business_no_call(monkeypatch):
    _boom(monkeypatch)
    p = FakeParams({})
    await wealth_tool.handle_get_budget_envelope(p)
    assert p.delivered["ok"] is False
    assert "needs which business" in p.delivered["error"]


@pytest.mark.asyncio
async def test_budget_envelope_unknown_business_no_call(monkeypatch):
    _boom(monkeypatch)
    p = FakeParams({"business": "nope"})
    await wealth_tool.handle_get_budget_envelope(p)
    assert "unknown business" in p.delivered["error"]


@pytest.mark.asyncio
async def test_budget_envelope_down(monkeypatch):
    _install(monkeypatch, httpx.ConnectError("refused"))
    p = FakeParams({"business": "acme-web"})
    await wealth_tool.handle_get_budget_envelope(p)
    assert p.delivered["error"] == "Wealth OS dashboard isn't running"


@pytest.mark.asyncio
async def test_budget_envelope_no_slugs_configured_fails_loud(monkeypatch):
    # No WEALTH_OS_BUSINESS_SLUGS -> fail LOUDLY before any network call, rather than
    # silently accepting the business or querying an empty envelope.
    monkeypatch.delenv("WEALTH_OS_BUSINESS_SLUGS", raising=False)
    _boom(monkeypatch)
    p = FakeParams({"business": "acme-farms"})
    await wealth_tool.handle_get_budget_envelope(p)
    assert p.delivered["ok"] is False
    assert "no business slugs configured" in p.delivered["error"]
    assert "WEALTH_OS_BUSINESS_SLUGS" in p.delivered["error"]


# =============================== get_goal_scorecard ==========================

@pytest.mark.asyncio
async def test_goal_scorecard_happy(monkeypatch):
    _install(monkeypatch, (200, {
        "scorecard": {
            "grain": "weekly", "period": "2026-W27",
            "goals": [
                {"stack_rank": 1, "title": "$1M cash", "auto": {"score": 82, "detail": "on pace"},
                 "recorded": None},
                {"stack_rank": 2, "title": "Delegation", "auto": None,
                 "recorded": {"score": 50, "notes": "half"}},
            ],
        },
        "rules": {"rules": []},
    }))
    p = FakeParams({})
    await wealth_tool.handle_get_goal_scorecard(p)
    assert p.delivered["ok"] is True
    assert p.delivered["period"] == "2026-W27"
    assert p.delivered["goals"][0]["score"] == 82
    assert p.delivered["goals"][1]["score"] == 50  # falls back to recorded


@pytest.mark.asyncio
async def test_goal_scorecard_grain_passed(monkeypatch):
    _install(monkeypatch, (200, {"scorecard": {"goals": []}}))
    await wealth_tool.handle_get_goal_scorecard(FakeParams({"grain": "monthly"}))
    assert _FakeClient.captured["params"] == {"grain": "monthly"}


@pytest.mark.asyncio
async def test_goal_scorecard_malformed(monkeypatch):
    # Missing 'scorecard' key -> graceful empty goals, no crash.
    _install(monkeypatch, (200, {"unexpected": True}))
    p = FakeParams({})
    await wealth_tool.handle_get_goal_scorecard(p)
    assert p.delivered["ok"] is True
    assert p.delivered["goals"] == []


@pytest.mark.asyncio
async def test_goal_scorecard_down(monkeypatch):
    _install(monkeypatch, httpx.ConnectError("refused"))
    p = FakeParams({})
    await wealth_tool.handle_get_goal_scorecard(p)
    assert p.delivered["error"] == "Wealth OS dashboard isn't running"
