# tests/test_agent_bridge_acp.py
#
# The `acp` tier delegates through acpx (headless ACP client) to our
# acp_claude_code.py adapter. These tests fake the acpx binary — the adapter
# itself is covered by tests/test_acp_claude_code.py.
import json

import pytest

import agent_bridge

FAKE_ACPX = """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
log = Path(os.environ["FAKE_ACPX_ARGV"])
calls = json.loads(log.read_text()) if log.exists() else []
calls.append({"argv": sys.argv[1:], "claude_args": os.environ.get("EVE_ACP_CLAUDE_ARGS"),
              "model": os.environ.get("EVE_ACP_MODEL")})
log.write_text(json.dumps(calls))
fail = os.environ.get("FAKE_ACPX_FAIL", "")
if fail == "1" or (fail == "prompt" and "prompt" in sys.argv[1:]):
    print("boom", file=sys.stderr)
    sys.exit(1)
if "prompt" in sys.argv[1:]:
    print("the acp answer")
else:
    print("session-id")
"""


@pytest.fixture
def fake_acpx(tmp_path, monkeypatch):
    exe = tmp_path / "acpx"
    exe.write_text(FAKE_ACPX)
    exe.chmod(0o755)
    argv_file = tmp_path / "argv.json"
    ws = tmp_path / "ws"
    ws.mkdir()
    monkeypatch.setattr(agent_bridge, "ACPX_BIN", str(exe))
    monkeypatch.setattr(agent_bridge, "WORKSPACE", ws)
    monkeypatch.setenv("FAKE_ACPX_ARGV", str(argv_file))
    monkeypatch.delenv("FAKE_ACPX_FAIL", raising=False)
    return argv_file


async def test_acp_tier_returns_result_and_wires_the_adapter(fake_acpx, monkeypatch):
    monkeypatch.delenv("JARVIS_ACP_TALKBACK_CLAUDE_ARGS", raising=False)
    result = await agent_bridge._try_acp("write a haiku about tests")
    assert result == "the acp answer"
    calls = json.loads(fake_acpx.read_text())
    # Two calls: `sessions ensure` first (acpx does NOT auto-create named
    # sessions — a fresh box would otherwise fail), then the prompt.
    assert len(calls) == 2
    ensure, prompt = calls[0]["argv"], calls[1]["argv"]
    assert ensure[ensure.index("sessions") :] == [
        "sessions", "ensure", "--name", agent_bridge.ACP_SESSION
    ]
    # Structural contract on both: quiet output, non-interactive permissions,
    # our adapter as the --agent command.
    for argv in (ensure, prompt):
        assert argv[: argv.index("--agent")] == [
            "--format", "quiet", "--approve-all", "--cwd", str(agent_bridge.WORKSPACE)
        ]
        assert "acp_claude_code.py" in argv[argv.index("--agent") + 1]
    assert prompt[prompt.index("-s") + 1] == agent_bridge.ACP_SESSION
    # Without talk-back configured: the task goes through verbatim.
    assert prompt[-1] == "write a haiku about tests"


async def test_acp_tier_fails_honestly_when_session_ensure_fails(fake_acpx, monkeypatch):
    monkeypatch.setenv("FAKE_ACPX_FAIL", "1")
    with pytest.raises(RuntimeError, match="acp session ensure failed"):
        await agent_bridge._try_acp("anything")


async def test_acp_tier_fails_honestly_on_prompt_failure(fake_acpx, monkeypatch):
    monkeypatch.setenv("FAKE_ACPX_FAIL", "prompt")
    with pytest.raises(RuntimeError, match="acp exited 1"):
        await agent_bridge._try_acp("anything")


async def test_acp_tier_fails_when_acpx_missing(monkeypatch, tmp_path):
    monkeypatch.setattr(agent_bridge, "ACPX_BIN", str(tmp_path / "not-there"))
    with pytest.raises(RuntimeError, match="acpx not installed"):
        await agent_bridge._try_acp("anything")


def test_acp_is_a_registered_brain():
    assert agent_bridge._BRAINS["acp"] is agent_bridge._try_acp


# ---- mid-task talk-back (notify_eve / ask_eve from inside the Claude session) ----

TALKBACK_ARGS = "--mcp-config /cfg/acp-talkback.mcp.json --allowedTools mcp__eve__notify_eve,mcp__eve__ask_eve"


@pytest.fixture
def talkback(fake_acpx, monkeypatch, tmp_path):
    # Isolated task DB (agent_tasks reads EVE_APPROVAL_DB lazily per call).
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "tasks.db"))
    monkeypatch.setenv("JARVIS_ACP_TALKBACK_CLAUDE_ARGS", TALKBACK_ARGS)
    monkeypatch.delenv("JARVIS_ACP_GOAL_PREFIX", raising=False)
    return fake_acpx


def _cid_from(sent_task: str) -> str:
    import re

    m = re.search(r"correlation_id=([0-9a-f]{32})", sent_task)
    assert m, f"no correlation_id in task header: {sent_task[:200]}"
    return m.group(1)


async def test_acp_talkback_mints_token_and_loads_mcp(talkback):
    import agent_tasks

    result = await agent_bridge._try_acp("investigate the flaky test")
    assert result == "the acp answer"
    calls = json.loads(talkback.read_text())
    sent = calls[1]["argv"][-1]
    # The Hermes talk-back contract, verbatim: fenced header + tools + ids,
    # original task at the end.
    assert sent.endswith("investigate the flaky test")
    assert "notify_eve(correlation_id, callback_token" in sent
    assert "ask_eve(correlation_id, callback_token" in sent
    cid = _cid_from(sent)
    row = agent_tasks.get(cid)
    assert row is not None and row["callback_token"]
    assert f"callback_token={row['callback_token']}" in sent
    # The claude session got the talkback MCP server ON TOP of the base args.
    for call in calls:
        assert call["claude_args"].endswith(TALKBACK_ARGS)
    # Row closed: resolved (result recorded) and delivered (spoken this turn —
    # session-start replay must not re-announce it).
    row = agent_tasks.get(cid)  # re-read post-close
    assert row is not None
    assert row["status"] == agent_tasks.RESOLVED
    assert row["result"] == {"ok": True, "text": "the acp answer"}
    assert row["delivered_at"]


async def test_acp_talkback_row_fails_closed_on_error(talkback, monkeypatch):
    import agent_tasks

    monkeypatch.setenv("FAKE_ACPX_FAIL", "prompt")
    with pytest.raises(RuntimeError):
        await agent_bridge._try_acp("anything")
    calls = json.loads(talkback.read_text())
    cid = _cid_from(calls[-1]["argv"][-1])
    row = agent_tasks.get(cid)
    assert row is not None
    # Failed AND delivered: the tier chain reports the failure itself, so the
    # row must never replay as a blocker.
    assert row["status"] == agent_tasks.FAILED
    assert row["delivered_at"]


async def test_acp_talkback_absence_keeps_task_verbatim(fake_acpx, monkeypatch):
    monkeypatch.delenv("JARVIS_ACP_TALKBACK_CLAUDE_ARGS", raising=False)
    monkeypatch.delenv("JARVIS_ACP_GOAL_PREFIX", raising=False)
    await agent_bridge._try_acp("plain task")
    calls = json.loads(fake_acpx.read_text())
    assert calls[1]["argv"][-1] == "plain task"
    assert "callback_token" not in calls[1]["argv"][-1]


# ---- per-delegation model (EVE asks; the owner 2026-07-03) ----


async def test_acp_model_rides_env_and_a_per_model_session(fake_acpx, monkeypatch):
    # The acpx queue owner keeps the adapter alive with spawn-time env, so a
    # model choice must ride a SEPARATE named session to actually apply.
    monkeypatch.delenv("JARVIS_ACP_TALKBACK_CLAUDE_ARGS", raising=False)
    monkeypatch.delenv("JARVIS_ACP_GOAL_PREFIX", raising=False)
    monkeypatch.delenv("EVE_ACP_MODEL", raising=False)
    await agent_bridge._try_acp("write code", model="fable")
    calls = json.loads(fake_acpx.read_text())
    ensure, prompt = calls[0]["argv"], calls[1]["argv"]
    assert ensure[ensure.index("--name") + 1] == f"{agent_bridge.ACP_SESSION}-fable"
    assert prompt[prompt.index("-s") + 1] == f"{agent_bridge.ACP_SESSION}-fable"
    assert all(c["model"] == "fable" for c in calls)


async def test_acp_no_model_uses_default_session(fake_acpx, monkeypatch):
    monkeypatch.delenv("JARVIS_ACP_TALKBACK_CLAUDE_ARGS", raising=False)
    monkeypatch.delenv("JARVIS_ACP_GOAL_PREFIX", raising=False)
    await agent_bridge._try_acp("write code")
    calls = json.loads(fake_acpx.read_text())
    assert calls[1]["argv"][calls[1]["argv"].index("-s") + 1] == agent_bridge.ACP_SESSION


# ---- goal-locked delegation (/goal <task> — the owner 2026-07-03) ----


async def test_acp_goal_prefix_leads_the_message(fake_acpx, monkeypatch):
    monkeypatch.delenv("JARVIS_ACP_TALKBACK_CLAUDE_ARGS", raising=False)
    monkeypatch.setenv("JARVIS_ACP_GOAL_PREFIX", "/goal")
    await agent_bridge._try_acp("ship the feature")
    calls = json.loads(fake_acpx.read_text())
    # A slash command must be the FIRST token or Claude won't parse it.
    assert calls[1]["argv"][-1] == "/goal ship the feature"


async def test_acp_goal_prefix_with_talkback_puts_header_after(talkback, monkeypatch):
    monkeypatch.setenv("JARVIS_ACP_GOAL_PREFIX", "/goal")
    await agent_bridge._try_acp("ship the feature")
    calls = json.loads(talkback.read_text())
    sent = calls[1]["argv"][-1]
    assert sent.startswith("/goal ship the feature")
    # Talk-back contract still present — trailing, so the slash stays first.
    assert "notify_eve(correlation_id, callback_token" in sent
    assert _cid_from(sent)
