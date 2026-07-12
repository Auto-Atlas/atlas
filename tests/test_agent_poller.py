# Tests for the poller pass (delegate_registry.poll_tick) — the async round-trip that makes
# EVE the sole executor of poll-delivery tasks. The delegate runner is injected, so no pipecat
# and no real Hermes subprocess are needed.
import asyncio
import importlib
import os
import tempfile

import pytest

import delegate_registry as dr


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


def test_poll_tick_drives_pending_task_to_resolved_and_delivers(store):
    cid, _ = store.create("hermes", "send a note", summary="note", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    delivered = []

    async def fake_run(spec, task, *, timeout_s=None):
        assert spec.name == "hermes" and task == "send a note"
        return {"ok": True, "text": "note sent"}

    async def deliver(row):
        delivered.append(row)

    n = asyncio.run(dr.poll_tick(deliver, lease_s=60, hard_s=180, run=fake_run))
    assert n == 1
    row = store.get(cid)
    assert row["status"] == "resolved" and row["result"]["text"] == "note sent"
    assert delivered and delivered[0]["id"] == cid     # ping-back delivered exactly once


def test_poll_tick_fails_task_honestly_and_delivers_blocker(store):
    cid, _ = store.create("hermes", "x", summary="x", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    delivered = []

    async def fake_run(spec, task, *, timeout_s=None):
        return {"ok": False, "text": "blocked: no telegram creds"}

    async def deliver(row):
        delivered.append(row)

    asyncio.run(dr.poll_tick(deliver, lease_s=60, hard_s=180, run=fake_run))
    assert store.get(cid)["status"] == "failed"        # honest failure, not a fake success
    # A blocker is a result too: it is delivered exactly once so it never vanishes silently.
    assert len(delivered) == 1 and delivered[0]["id"] == cid
    assert "blocked" in (delivered[0].get("result") or {}).get("error", "")


def test_poll_tick_runs_each_task_exactly_once(store):
    # The no-double-send guarantee: a single pending task is run once even across two ticks.
    cid, _ = store.create("hermes", "send once", summary="once", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    runs = []

    async def fake_run(spec, task, *, timeout_s=None):
        runs.append(task)
        return {"ok": True, "text": "done"}

    async def deliver(row):
        pass

    async def scenario():
        await dr.poll_tick(deliver, lease_s=60, hard_s=180, run=fake_run)
        await dr.poll_tick(deliver, lease_s=60, hard_s=180, run=fake_run)  # second tick

    asyncio.run(scenario())
    assert runs == ["send once"]                        # executed exactly once
    assert store.get(cid)["status"] == "resolved"


def test_poll_tick_ignores_push_tasks(store):
    store.create("hermes", "pushed", summary="p", delivery="push",
                 requester="W", requester_tier="owner", ttl_s=3600)
    ran = []

    async def fake_run(spec, task, *, timeout_s=None):
        ran.append(task)
        return {"ok": True, "text": "x"}

    async def deliver(row):
        pass

    n = asyncio.run(dr.poll_tick(deliver, lease_s=60, hard_s=180, run=fake_run))
    assert n == 0 and ran == []                          # poller never claims push tasks
