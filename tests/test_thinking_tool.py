# Tests for the set_thinking voice tool (Epic T) — spoken on/off path.
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
    import thinking_state
    importlib.reload(thinking_state)
    return thinking_state


def test_set_thinking_schema_registered():
    import jarvis_core
    assert "set_thinking" in {s.name for s in jarvis_core.ALL_TOOL_SCHEMAS}


def test_set_thinking_skill_is_low_risk():
    import jarvis_core
    sk = jarvis_core._SKILLS.get("set_thinking")
    assert sk is not None and sk.risk == "low" and sk.requires_confirmation is False


def _call(handler, args):
    captured = {}

    class P:
        arguments = args

        async def result_callback(self, result, **kw):
            captured.update(result)

    asyncio.run(handler(P()))
    return captured


def test_set_thinking_handler_toggles_state(store):
    import jarvis_core
    out = _call(jarvis_core.handle_set_thinking, {"on": True})
    assert out["ok"] and out["thinking"] is True
    assert store.enabled() is True
    out = _call(jarvis_core.handle_set_thinking, {"on": False})
    assert out["thinking"] is False
    assert store.enabled() is False
