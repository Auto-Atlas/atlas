# Tests for try_announce — totality of delegated-result delivery (EVE Agent Hub).
# The seam is the injected announce callable, so NO pipecat pipeline is needed.
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


def _resolved_row(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "done"})
    return cid


def test_spoken_marks_delivered(store):
    import try_announce
    cid = _resolved_row(store)
    spoke = []

    async def announce(text):
        spoke.append(text)

    status = asyncio.run(try_announce.deliver(announce, "say it", cid=cid))
    assert status == try_announce.SPOKEN and spoke == ["say it"]
    assert store.get(cid)["delivered_at"] is not None


def test_dead_session_queues_and_keeps_undelivered(store):
    import try_announce
    cid = _resolved_row(store)

    async def announce(text):
        raise AssertionError("must not be called when the session is dead")

    status = asyncio.run(try_announce.deliver(
        announce, "say it", cid=cid, is_alive=lambda: False))
    assert status == try_announce.QUEUED_NO_SESSION
    assert store.get(cid)["delivered_at"] is None          # replay still picks it up


def test_announce_raising_mid_teardown_queues(store):
    import try_announce
    cid = _resolved_row(store)

    async def announce(text):
        raise RuntimeError("phone session is no longer live")

    status = asyncio.run(try_announce.deliver(announce, "say it", cid=cid))
    assert status == try_announce.QUEUED_NO_SESSION
    assert store.get(cid)["delivered_at"] is None


def test_replay_instruction_is_past_tense_and_fenced(store):
    import try_announce
    cid = _resolved_row(store)
    row = store.get(cid)
    instr = try_announce.replay_instruction(row)
    assert "While you were away" in instr
    assert "UNTRUSTED DATA" in instr and "done" in instr


def test_live_instruction_is_present_tense(store):
    import try_announce
    cid = _resolved_row(store)
    instr = try_announce.live_instruction(store.get(cid))
    assert "just finished" in instr and "UNTRUSTED DATA" in instr
