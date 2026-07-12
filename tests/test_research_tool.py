# tests/test_research_tool.py
#
# research_tool is Eve's voice control over ResearchOS (FastAPI on the inference box):
# start_research (an action), research_status, list_research_decisions (reads).
# the inference box is frequently DOWN, so every function must degrade to one honest line.
# These tests mock httpx and assert, per tool:
#   - the happy path (the two-step create+start for start_research; the
#     list->status chain for research_status);
#   - ResearchOS being DOWN -> the "the inference box may be down" instruction;
#   - a malformed/partial response -> graceful handling, never a crash;
#   - a backend error (non-2xx) -> ok:False with a truncated error;
#   - start_research missing goal -> ok:False without a network call.
from dataclasses import dataclass
from typing import Callable, Optional

import httpx
import pytest

import research_tool


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
    """Serves a queue of canned responses (tuple to return, Exception to raise).
    Captures every request in order so a two-step handler can be verified."""
    calls: list = []
    queue: list = []

    def __init__(self, *a, **kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def request(self, method, url, params=None, json=None):
        _FakeClient.calls.append({"method": method, "url": url, "params": params, "json": json})
        item = _FakeClient.queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return _FakeResp(*item)


def _install(monkeypatch, *responses):
    _FakeClient.calls = []
    _FakeClient.queue = list(responses)
    monkeypatch.setattr(research_tool.httpx, "AsyncClient", _FakeClient)


def _boom(monkeypatch):
    def _raise(*a, **kw):
        raise AssertionError("httpx.AsyncClient created — a network call was attempted!")
    monkeypatch.setattr(research_tool.httpx, "AsyncClient", _raise)


@pytest.fixture(autouse=True)
def _research_env(monkeypatch):
    monkeypatch.setenv("RESEARCHOS_URL", "http://100.0.0.1:8001")
    yield


# ================================= start_research =============================

@pytest.mark.asyncio
async def test_start_research_happy_two_step(monkeypatch):
    _install(
        monkeypatch,
        (201, {"id": "sess-1", "goal": "a 3D printer", "mode": "product-research"}),
        (200, {"job_id": "job-9", "status": "queued"}),
    )
    p = FakeParams({"goal": "a 3D printer", "budget": 500})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is True
    assert p.delivered["session_id"] == "sess-1"
    assert p.delivered["job_id"] == "job-9"
    assert p.delivered["status"] == "queued"
    # Step 1 POST /api/sessions with the goal+budget body, step 2 kicks the pipeline.
    assert _FakeClient.calls[0]["url"] == "http://100.0.0.1:8001/api/sessions"
    assert _FakeClient.calls[0]["json"] == {"goal": "a 3D printer", "budget": 500}
    assert _FakeClient.calls[1]["url"] == "http://100.0.0.1:8001/api/sessions/sess-1/research"


@pytest.mark.asyncio
async def test_start_research_no_budget_omitted(monkeypatch):
    _install(monkeypatch,
             (201, {"id": "s2"}),
             (200, {"job_id": "j2", "status": "queued"}))
    await research_tool.handle_start_research(FakeParams({"goal": "solar panels"}))
    assert _FakeClient.calls[0]["json"] == {"goal": "solar panels"}


@pytest.mark.asyncio
async def test_start_research_missing_goal_no_call(monkeypatch):
    _boom(monkeypatch)
    p = FakeParams({"goal": "   "})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is False
    assert "needs a goal" in p.delivered["error"]


@pytest.mark.asyncio
async def test_start_research_unconfigured_fails_loud(monkeypatch):
    # RESEARCHOS_URL unset -> fail LOUDLY with an actionable message, never probe a
    # hardcoded rig. Goal is valid, so this proves the config guard (not goal-validation).
    monkeypatch.delenv("RESEARCHOS_URL", raising=False)
    _boom(monkeypatch)
    p = FakeParams({"goal": "a drone"})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is False
    assert "RESEARCHOS_URL" in p.delivered["error"]


@pytest.mark.asyncio
async def test_research_status_unconfigured_fails_loud(monkeypatch):
    monkeypatch.delenv("RESEARCHOS_URL", raising=False)
    _boom(monkeypatch)
    p = FakeParams({})
    await research_tool.handle_research_status(p)
    assert p.delivered["ok"] is False
    assert "RESEARCHOS_URL" in p.delivered["error"]


@pytest.mark.asyncio
async def test_list_decisions_unconfigured_fails_loud(monkeypatch):
    monkeypatch.delenv("RESEARCHOS_URL", raising=False)
    _boom(monkeypatch)
    p = FakeParams({})
    await research_tool.handle_list_research_decisions(p)
    assert p.delivered["ok"] is False
    assert "RESEARCHOS_URL" in p.delivered["error"]


@pytest.mark.asyncio
async def test_start_research_down_on_create(monkeypatch):
    _install(monkeypatch, httpx.ConnectError("refused"))
    p = FakeParams({"goal": "a drone"})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is False
    assert p.delivered["error"] == "ResearchOS is unreachable — the inference box may be down"


@pytest.mark.asyncio
async def test_start_research_down_after_create(monkeypatch):
    # Session created, but the inference box drops before the pipeline kicks — be honest
    # about the split rather than claiming it started.
    _install(monkeypatch,
             (201, {"id": "sess-x"}),
             httpx.ConnectError("refused"))
    p = FakeParams({"goal": "a drone"})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is False
    assert p.delivered["session_id"] == "sess-x"
    assert "didn't start" in p.delivered["error"]


@pytest.mark.asyncio
async def test_start_research_created_no_id(monkeypatch):
    _install(monkeypatch, (201, {"goal": "x"}))  # no id -> can't proceed
    p = FakeParams({"goal": "x"})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is False
    assert "no id" in p.delivered["error"]


@pytest.mark.asyncio
async def test_start_research_create_error(monkeypatch):
    _install(monkeypatch, (500, {"detail": "qwen unavailable"}))
    p = FakeParams({"goal": "x"})
    await research_tool.handle_start_research(p)
    assert p.delivered["ok"] is False
    assert p.delivered["error"] == "qwen unavailable"


# ================================ research_status ============================

@pytest.mark.asyncio
async def test_research_status_happy(monkeypatch):
    _install(
        monkeypatch,
        (200, [{"id": "sess-1", "goal": "a 3D printer", "status": "researching"}]),
        (200, {"status": "searching", "needs_completed": 1, "needs_total": 3}),
    )
    p = FakeParams({})
    await research_tool.handle_research_status(p)
    assert p.delivered["ok"] is True
    assert p.delivered["found"] is True
    assert p.delivered["job_status"] == "searching"
    assert p.delivered["needs_completed"] == 1
    assert _FakeClient.calls[0]["url"] == "http://100.0.0.1:8001/api/sessions"
    assert _FakeClient.calls[1]["url"] == "http://100.0.0.1:8001/api/sessions/sess-1/status"


@pytest.mark.asyncio
async def test_research_status_no_sessions(monkeypatch):
    _install(monkeypatch, (200, []))
    p = FakeParams({})
    await research_tool.handle_research_status(p)
    assert p.delivered["ok"] is True
    assert p.delivered["found"] is False


@pytest.mark.asyncio
async def test_research_status_no_job_yet(monkeypatch):
    _install(monkeypatch,
             (200, [{"id": "s1", "goal": "g", "status": "analyzing"}]),
             (404, {"detail": "No research job for this session"}))
    p = FakeParams({})
    await research_tool.handle_research_status(p)
    assert p.delivered["ok"] is True
    assert p.delivered["found"] is True
    assert p.delivered["job_status"] is None


@pytest.mark.asyncio
async def test_research_status_down(monkeypatch):
    _install(monkeypatch, httpx.ConnectError("refused"))
    p = FakeParams({})
    await research_tool.handle_research_status(p)
    assert p.delivered["error"] == "ResearchOS is unreachable — the inference box may be down"


@pytest.mark.asyncio
async def test_research_status_malformed(monkeypatch):
    # /api/sessions returns a dict instead of a list -> treated as no sessions.
    _install(monkeypatch, (200, {"unexpected": "shape"}))
    p = FakeParams({})
    await research_tool.handle_research_status(p)
    assert p.delivered["ok"] is True
    assert p.delivered["found"] is False


# ============================ list_research_decisions ========================

@pytest.mark.asyncio
async def test_list_decisions_happy(monkeypatch):
    _install(monkeypatch, (200, {"decisions": [
        {"slug": "decision-3d-printer", "path": "/w/decision-3d-printer.md"},
        {"slug": "decision-solar", "path": "/w/decision-solar.md"},
        {"slug": "decision-drone", "path": "/w/decision-drone.md"},
        {"slug": "decision-old", "path": "/w/decision-old.md"},
    ]}))
    p = FakeParams({})   # default limit 3
    await research_tool.handle_list_research_decisions(p)
    assert p.delivered["ok"] is True
    assert p.delivered["count"] == 3
    assert p.delivered["decisions"][0]["slug"] == "decision-3d-printer"


@pytest.mark.asyncio
async def test_list_decisions_limit(monkeypatch):
    _install(monkeypatch, (200, {"decisions": [{"slug": f"decision-{i}"} for i in range(10)]}))
    p = FakeParams({"limit": 2})
    await research_tool.handle_list_research_decisions(p)
    assert p.delivered["count"] == 2


@pytest.mark.asyncio
async def test_list_decisions_malformed(monkeypatch):
    _install(monkeypatch, (200, {}))   # no 'decisions' key
    p = FakeParams({})
    await research_tool.handle_list_research_decisions(p)
    assert p.delivered["ok"] is True
    assert p.delivered["decisions"] == []


@pytest.mark.asyncio
async def test_list_decisions_down(monkeypatch):
    _install(monkeypatch, httpx.ConnectError("refused"))
    p = FakeParams({})
    await research_tool.handle_list_research_decisions(p)
    assert p.delivered["error"] == "ResearchOS is unreachable — the inference box may be down"
