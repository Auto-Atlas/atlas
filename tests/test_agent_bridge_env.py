# tests/test_agent_bridge_env.py
#
# Live incident 2026-07-03: EVE reported "the agent brains are all failing" —
# the systemd service's slim PATH couldn't see acpx (nvm bin) or claude/codex
# (~/.superset/bin), and the hermes tier called wsl.exe unconditionally on
# Linux. These tests pin the fixes.
import dataclasses
import os

import pytest

import agent_bridge


def test_enrich_path_adds_extra_dirs_once(tmp_path, monkeypatch):
    tool_dir = tmp_path / "toolbin"
    tool_dir.mkdir()
    monkeypatch.setenv("JARVIS_EXTRA_PATH", str(tool_dir))
    monkeypatch.setenv("PATH", "/usr/bin:/bin")
    agent_bridge._enrich_path()
    parts = os.environ["PATH"].split(os.pathsep)
    assert str(tool_dir) in parts
    # idempotent: running again must not duplicate the entry
    agent_bridge._enrich_path()
    assert os.environ["PATH"].split(os.pathsep).count(str(tool_dir)) == 1


def test_enrich_path_makes_a_slim_path_resolve_tools(tmp_path, monkeypatch):
    tool_dir = tmp_path / "toolbin"
    tool_dir.mkdir()
    fake = tool_dir / "acpx"
    fake.write_text("#!/bin/sh\necho ok\n")
    fake.chmod(0o755)
    monkeypatch.setenv("JARVIS_EXTRA_PATH", str(tool_dir))
    monkeypatch.setenv("PATH", "/usr/bin:/bin")  # service-like slim PATH
    agent_bridge._enrich_path()
    assert agent_bridge._which("acpx") == str(fake)


@pytest.mark.skipif(os.name == "nt", reason="native-hermes path is POSIX-only")
async def test_hermes_uses_native_binary_on_posix(tmp_path, monkeypatch):
    log = tmp_path / "argv.txt"
    fake = tmp_path / "hermes"
    fake.write_text(f"#!/bin/sh\necho \"$@\" > {log}\necho 'hermes says hi'\n")
    fake.chmod(0o755)
    monkeypatch.setattr(agent_bridge, "_which",
                        lambda name: str(fake) if name == "hermes" else None)
    monkeypatch.setattr(agent_bridge, "WORKSPACE", tmp_path)
    result = await agent_bridge._try_hermes("look this up\nplease")
    assert result == "hermes says hi"
    # -z one-shot with the task flattened to a single line
    assert log.read_text().strip() == "-z look this up please"


# Live incident 2026-07-08 (phone): the intent-routed chain led research with the
# inline one-shot `hermes -z`, which blocked the voice turn for the full 180s
# timeout and dumped the task on the weak local tier — while the registry
# delegate (delegate_hermes over A2A) ran Hermes fine. The chain must drop the
# inline tier whenever the registry path is enabled, and named-Hermes asks must
# redirect to delegate_hermes instead of stalling.

def test_intent_chain_drops_inline_hermes_when_delegate_path_enabled(monkeypatch):
    import delegate_registry
    monkeypatch.delenv("JARVIS_BRAIN_ORDER", raising=False)
    monkeypatch.setitem(delegate_registry.REGISTRY, "hermes",
                        dataclasses.replace(delegate_registry.REGISTRY["hermes"], enabled=True))
    # single delegation: research rides Claude Code over ACP, nothing queued behind
    assert agent_bridge._brain_order_for("look up the weather patterns") == ["acp"]


def test_intent_chain_keeps_inline_hermes_without_delegate_path(monkeypatch):
    import delegate_registry
    monkeypatch.delenv("JARVIS_BRAIN_ORDER", raising=False)
    monkeypatch.setitem(delegate_registry.REGISTRY, "hermes",
                        dataclasses.replace(delegate_registry.REGISTRY["hermes"], enabled=False))
    assert agent_bridge._brain_order_for("look up the weather patterns") == ["hermes"]


def test_env_pin_is_an_operator_override_and_never_filtered(monkeypatch):
    import delegate_registry
    monkeypatch.setenv("JARVIS_BRAIN_ORDER", "hermes,local")
    monkeypatch.setitem(delegate_registry.REGISTRY, "hermes",
                        dataclasses.replace(delegate_registry.REGISTRY["hermes"], enabled=True))
    assert agent_bridge._brain_order_for("anything at all") == ["hermes", "local"]


class _FakeParams:
    """Minimal stand-in for FunctionCallParams: arguments + a capturing
    async result_callback."""

    def __init__(self, arguments):
        self.arguments = arguments
        self.delivered = None

    async def result_callback(self, result):
        self.delivered = result


async def test_named_hermes_redirects_to_delegate_hermes_when_inline_disabled(monkeypatch):
    import delegate_registry
    monkeypatch.delenv("JARVIS_BRAIN_ORDER", raising=False)
    monkeypatch.setitem(delegate_registry.REGISTRY, "hermes",
                        dataclasses.replace(delegate_registry.REGISTRY["hermes"], enabled=True))
    handler = agent_bridge.make_jarvis_agent_handler(emit=None)
    params = _FakeParams({"task": "find the latest robotics news", "brain": "hermes"})
    await handler(params)
    assert params.delivered["ok"] is False
    assert "delegate_hermes" in params.delivered["instruction"]


async def test_named_brain_runs_alone_and_fails_loudly(monkeypatch):
    # 2026-07-08: a named brain is the SOLE delegation — when it fails, the
    # handler reports the failure verbatim instead of cascading to other tiers.
    calls = []

    async def failing_codex(task):
        calls.append("codex")
        raise RuntimeError("codex CLI not installed")

    async def local_must_not_run(task):
        calls.append("local")
        return "local answered"

    monkeypatch.setitem(agent_bridge._BRAINS, "codex", failing_codex)
    monkeypatch.setitem(agent_bridge._BRAINS, "local", local_must_not_run)
    handler = agent_bridge.make_jarvis_agent_handler(emit=None)
    params = _FakeParams({"task": "run the connectivity check", "brain": "codex"})
    await handler(params)
    assert calls == ["codex"]  # no waterfall behind the named brain
    assert params.delivered["ok"] is False
    assert "codex CLI not installed" in params.delivered["error"]
