# tests/test_brain_order.py
#
# _brain_order_for routes each delegation to a SINGLE brain (2026-07-08: the
# multi-tier waterfall is out — it stalled the phone turn and masked the lead
# brain's failure behind weaker tiers). Code-ish tasks go to Claude Code over
# ACP; research goes to inline hermes only on boxes WITHOUT the delegate_hermes
# registry path, otherwise also to acp. A set JARVIS_BRAIN_ORDER env var still
# PINS a chain for every task — that is the operator's explicit opt-in to a
# waterfall, and the only way GLM gets onto the hot path.
import dataclasses

import agent_bridge
import delegate_registry


def _set_hermes_delegate(monkeypatch, enabled: bool):
    monkeypatch.setitem(
        delegate_registry.REGISTRY, "hermes",
        dataclasses.replace(delegate_registry.REGISTRY["hermes"], enabled=enabled))


def test_default_routing_is_a_single_brain(monkeypatch):
    monkeypatch.delenv("JARVIS_BRAIN_ORDER", raising=False)
    _set_hermes_delegate(monkeypatch, True)
    # Coding leads with Claude Code over ACP (the owner's 2026-07-03 wire-up);
    # with the delegate_hermes path live, research ALSO goes to acp — never the
    # inline hermes one-shot, and never a cascade.
    assert agent_bridge._brain_order_for("debug this function") == ["acp"]
    assert agent_bridge._brain_order_for("what's the weather like") == ["acp"]


def test_research_uses_inline_hermes_only_without_delegate_path(monkeypatch):
    monkeypatch.delenv("JARVIS_BRAIN_ORDER", raising=False)
    _set_hermes_delegate(monkeypatch, False)
    assert agent_bridge._brain_order_for("what's the weather like") == ["hermes"]


def test_env_override_pins_chain_for_every_task(monkeypatch):
    monkeypatch.setenv("JARVIS_BRAIN_ORDER", "glm,codex,local")
    # Both a coding task and a general task now follow the pinned order verbatim,
    # with GLM first — proving the override defeats intent routing.
    assert agent_bridge._brain_order_for("debug this function") == ["glm", "codex", "local"]
    assert agent_bridge._brain_order_for("tell me a joke") == ["glm", "codex", "local"]


def test_env_override_drops_unknown_tiers(monkeypatch):
    monkeypatch.setenv("JARVIS_BRAIN_ORDER", "glm, bogus , local")
    # Whitespace is trimmed and unknown tier names are silently dropped.
    assert agent_bridge._brain_order_for("anything") == ["glm", "local"]


def test_all_invalid_override_falls_back_to_intent_routing(monkeypatch):
    monkeypatch.setenv("JARVIS_BRAIN_ORDER", "nope,bogus")
    _set_hermes_delegate(monkeypatch, True)
    # An override naming only unknown tiers must not yield an empty chain — it
    # falls through to the single-brain intent default.
    assert agent_bridge._brain_order_for("what's the weather like") == ["acp"]
