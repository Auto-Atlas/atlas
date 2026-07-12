# Tests for check_delegations — the queryable audit trail ("did that email go out?", brief §task-board).
import asyncio
import importlib
import os
import tempfile

import pytest


@pytest.fixture
def store(monkeypatch):
    d = tempfile.mkdtemp()
    db = os.path.join(d, "approvals.db")
    import approval_store
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    approval_store.set_db_path(db)
    import agent_tasks
    importlib.reload(agent_tasks)
    return agent_tasks


def _call(handler, args):
    captured = {}

    class P:
        arguments = args

        async def result_callback(self, result, **kw):
            captured.update(result)

    asyncio.run(handler(P()))
    return captured


def test_check_delegations_schema_registered():
    import jarvis_core
    assert "check_delegations" in {s.name for s in jarvis_core.ALL_TOOL_SCHEMAS}


def test_check_delegations_skill_is_low_risk():
    import jarvis_core
    sk = jarvis_core._SKILLS.get("check_delegations")
    assert sk is not None and sk.risk == "low" and sk.requires_confirmation is False


def test_empty_when_no_tasks(store):
    import jarvis_core
    out = _call(jarvis_core.handle_check_delegations, {})
    assert out["ok"] and out["tasks"] == []


def test_lists_recent_tasks_with_status(store):
    import jarvis_core
    cid, _ = store.create("hermes", "text Marco running late", summary="text Marco",
                          delivery="poll", requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "sent"})
    out = _call(jarvis_core.handle_check_delegations, {})
    assert out["ok"] and len(out["tasks"]) == 1
    t = out["tasks"][0]
    assert t["agent"] == "hermes" and t["status"] == "resolved"
    assert "Marco" in t["task"] and t["result"] == "sent"


def test_agent_synonym_filters_to_hermes(store):
    import jarvis_core
    store.create("hermes", "a", summary="a", delivery="poll",
                 requester="W", requester_tier="owner", ttl_s=3600)
    # "messaging" is the spoken name for the Hermes agent — must map to it.
    out = _call(jarvis_core.handle_check_delegations, {"agent": "messaging"})
    assert out["ok"] and len(out["tasks"]) == 1 and out["tasks"][0]["agent"] == "hermes"


def test_failed_task_surfaces_blocker_reason(store):
    # fail() stores the reason under result.error; the readback must surface it (not drop it),
    # so "what did Hermes do?" answers "it hit a blocker: <reason>".
    import jarvis_core
    cid, _ = store.create("hermes", "post standup", summary="post standup", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.fail(cid, "blocked: no telegram creds")
    out = _call(jarvis_core.handle_check_delegations, {"agent": "hermes"})
    t = out["tasks"][0]
    assert t["status"] == "failed"
    assert "blocked" in t["result"]
