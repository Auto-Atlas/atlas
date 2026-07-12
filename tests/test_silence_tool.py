# Tests for the set_silence_mode voice tool — the spoken on/off path.
# Owner-only (OWNER_ONLY set), trivial/low risk, no confirmation — like adjust_surfacing.
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
    import silence_mode
    importlib.reload(silence_mode)
    # Pin the assistant's name — the default wake phrase derives from it (never a literal).
    import persona
    monkeypatch.setattr(persona, "ASSISTANT_NAME", "Eve")
    return silence_mode


def test_schema_registered():
    import jarvis_core
    assert "set_silence_mode" in {s.name for s in jarvis_core.ALL_TOOL_SCHEMAS}


def test_skill_is_low_risk_no_confirm():
    import jarvis_core
    sk = jarvis_core._SKILLS.get("set_silence_mode")
    assert sk is not None and sk.risk == "low" and sk.requires_confirmation is False


def test_tool_is_owner_only():
    import tool_policy
    assert "set_silence_mode" in tool_policy.OWNER_ONLY
    # owner may call it; nobody else can.
    assert tool_policy.tier_allows("set_silence_mode", "low", "owner") is True
    assert tool_policy.tier_allows("set_silence_mode", "low", "known") is False
    assert tool_policy.tier_allows("set_silence_mode", "low", "kid") is False
    assert tool_policy.tier_allows("set_silence_mode", "low", "unknown") is False


def _call(handler, args):
    captured = {}

    class P:
        arguments = args

        async def result_callback(self, result, **kw):
            captured.update(result)

    asyncio.run(handler(P()))
    return captured


def test_handler_toggles_state_and_instructs(store):
    import jarvis_core
    out = _call(jarvis_core.handle_set_silence_mode, {"enabled": True})
    assert out["ok"] and out["silence_mode"] is True
    assert store.enabled() is True
    assert "instruction" in out and out["instruction"].strip()

    out = _call(jarvis_core.handle_set_silence_mode, {"enabled": False})
    assert out["silence_mode"] is False
    assert store.enabled() is False
    assert "instruction" in out and out["instruction"].strip()
