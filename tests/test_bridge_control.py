# Tests for the bridge inbound control channel — the thinking toggle from the sidecar UI (Epic T).
import asyncio
import importlib
import json
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
    import thinking_state
    importlib.reload(thinking_state)
    return thinking_state


def _bridge_with_recorder():
    import bridge
    b = bridge.MetricsBridge(host="127.0.0.1", port=0, mode="test")
    sent = []

    async def fake_broadcast(msg):
        sent.append(msg)

    b.broadcast = fake_broadcast
    return b, sent


def test_set_thinking_control_flips_state_and_broadcasts(store):
    b, sent = _bridge_with_recorder()
    asyncio.run(b._on_control(json.dumps({"type": "set_thinking", "on": True})))
    assert store.enabled() is True
    assert {"type": "thinking_mode", "enabled": True} in sent
    asyncio.run(b._on_control(json.dumps({"type": "set_thinking", "on": False})))
    assert store.enabled() is False
    assert {"type": "thinking_mode", "enabled": False} in sent


def test_malformed_or_unknown_control_is_ignored(store):
    b, sent = _bridge_with_recorder()
    asyncio.run(b._on_control("not json at all"))
    asyncio.run(b._on_control(json.dumps({"type": "something_else"})))
    asyncio.run(b._on_control(json.dumps(["not", "a", "dict"])))
    assert sent == []                  # nothing broadcast
    assert store.enabled() is False    # state untouched
