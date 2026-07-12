# Tests for the tool_policy block->stage remote-approval branch (spec §1.7).
# Real components: real tool_policy.wrapped, real approval_store (temp DB), real
# speaker_state. The default-disabled path must be byte-identical to today's deny.
import importlib
from dataclasses import dataclass, field
from typing import Callable, Optional

import pytest


@dataclass
class FakeParams:
    arguments: dict
    context: object = None
    delivered: object = None
    last_kwargs: dict = field(default_factory=dict)
    result_callback: Optional[Callable] = None

    def __post_init__(self):
        if self.result_callback is None:
            async def _capture(result, **kwargs):
                self.delivered = result
                self.last_kwargs = kwargs
            self.result_callback = _capture


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "approvals.db"))
    monkeypatch.delenv("EVE_REMOTE_APPROVAL", raising=False)
    import approval_store
    importlib.reload(approval_store)
    import tool_policy
    importlib.reload(tool_policy)
    import speaker_state
    speaker_state.reset()
    tool_policy._staged.clear()
    tool_policy._injected.clear()
    return tool_policy, approval_store, speaker_state


async def _ok(params):
    await params.result_callback({"ok": True, "ran": True})


def _invoice_spec(tp):
    return tp.ToolPolicy(needs_confirmation=True,
                         requires_fields=("customer", "line_items"), risk_level="high")


async def test_disabled_default_is_byte_identical_deny(env):
    tp, store, speaker_state = env
    import persona
    speaker_state.set_current("Alex", "known", 0.9)
    wrapped = tp.policy("create_invoice", _invoice_spec(tp), _ok)
    p = FakeParams({"customer": {"name": "X"}, "line_items": [{"description": "d", "quantity": 1, "rate": 1}]})
    await wrapped(p)
    # EXACT same payload today's hard-deny returns (tool_policy.py:85-89) — full dict.
    assert p.delivered == {
        "ok": False, "denied": True, "tier": "known",
        "instruction": persona.refusal_instruction("create_invoice", "known", "Alex"),
    }
    assert store.list_pending() == []                       # nothing staged when disabled


async def test_enabled_known_high_stages_instead_of_denies(env):
    tp, store, speaker_state = env
    store.set_setting("remote_approval_enabled", "true")    # the in-app activation toggle
    speaker_state.set_current("Alex", "known", 0.9)
    wrapped = tp.policy("create_invoice", _invoice_spec(tp), _ok)
    p = FakeParams({"customer": {"name": "The Browns"},
                    "line_items": [{"description": "Deep clean", "quantity": 2, "rate": 480}]})
    await wrapped(p)
    assert p.delivered.get("denied") is not True            # NOT a refusal
    assert p.delivered.get("staged_for_approval") is True
    assert "ran" not in p.delivered                         # the action did NOT fire
    pending = store.list_pending()
    assert len(pending) == 1
    row = pending[0]
    assert row["tool"] == "create_invoice" and row["requester"] == "Alex"
    assert row["requester_tier"] == "known" and row["risk_level"] == "high"
    assert row["args"]["line_items"][0]["rate"] == 480      # frozen draft carries full detail


async def test_enabled_but_missing_required_field_falls_through_to_refusal(env):
    tp, store, speaker_state = env
    store.set_setting("remote_approval_enabled", "true")
    speaker_state.set_current("Alex", "known", 0.9)
    wrapped = tp.policy("create_invoice", _invoice_spec(tp), _ok)
    p = FakeParams({"customer": {"name": "X"}})             # no line_items
    await wrapped(p)
    assert p.delivered["denied"] is True                    # normal refusal, not staged
    assert store.list_pending() == []


async def test_enabled_env_var_also_activates(env, monkeypatch):
    tp, store, speaker_state = env
    monkeypatch.setenv("EVE_REMOTE_APPROVAL", "enabled")    # env default path (no settings row)
    speaker_state.set_current("Alex", "known", 0.9)
    wrapped = tp.policy("create_invoice", _invoice_spec(tp), _ok)
    p = FakeParams({"customer": {"name": "X"}, "line_items": [{"description": "d", "quantity": 1, "rate": 1}]})
    await wrapped(p)
    assert p.delivered.get("staged_for_approval") is True
    assert len(store.list_pending()) == 1


async def test_owner_never_routes_to_remote(env):
    tp, store, speaker_state = env
    store.set_setting("remote_approval_enabled", "true")
    speaker_state.set_current("Owner", "owner", 0.95)
    wrapped = tp.policy("create_invoice", _invoice_spec(tp), _ok)
    p = FakeParams({"customer": {"name": "X"}, "line_items": [{"description": "d", "quantity": 1, "rate": 1}]})
    await wrapped(p)
    # owner with needs_confirmation -> normal in-context draft preview, never remote staging
    assert p.delivered.get("needs_confirmation") is True
    assert store.list_pending() == []


async def test_unknown_high_never_stages(env):
    tp, store, speaker_state = env
    store.set_setting("remote_approval_enabled", "true")
    speaker_state.set_current(None, "unknown", 0.0)
    wrapped = tp.policy("create_invoice", _invoice_spec(tp), _ok)
    p = FakeParams({"customer": {"name": "X"}, "line_items": [{"description": "d", "quantity": 1, "rate": 1}]})
    await wrapped(p)
    assert p.delivered["denied"] is True
    assert store.list_pending() == []
