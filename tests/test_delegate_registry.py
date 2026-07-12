# Tests for delegate_registry — declarative specs + shared CLI transport (EVE Agent Hub).
import asyncio

import delegate_registry as dr


def test_hermes_spec_present_and_high_risk():
    s = dr.REGISTRY["hermes"]
    assert s.enabled and s.risk == "high" and s.callback == "poll"
    assert s.allow_redelegate is False
    assert s.side_effecting is True            # -> poller is the sole executor (no double-send)


def test_schemas_only_for_enabled_and_carry_task_and_confirmed():
    names = {sch.name for sch in dr.delegate_schemas()}
    assert "delegate_hermes" in names
    assert "delegate_jarvis" not in names      # disabled -> no schema
    assert "delegate_open_claw" not in names   # BLOCKED -> no schema
    sch = next(s for s in dr.delegate_schemas() if s.name == "delegate_hermes")
    assert "task" in sch.properties and "confirmed" in sch.properties
    assert sch.required == ["task"]


def test_tool_name_for():
    assert dr.tool_name_for(dr.REGISTRY["hermes"]) == "delegate_hermes"


def test_run_delegate_sync_returns_clean_text(monkeypatch):
    async def fake_cli(argv, env=None, timeout_s=None):
        assert argv[1] == "chat" and argv[2] == "-q" and "ping" in argv[3]
        assert "--resume" not in argv                       # fresh conversation
        # hermes -Q prints the reply on STDOUT and 'session_id:' on STDERR (verified live)
        return (0, "PONG\n", "log noise\nsession_id: 20260702_1\n")

    monkeypatch.setattr(dr, "_run_cli", fake_cli)
    out = asyncio.run(dr.run_delegate(dr.REGISTRY["hermes"], "ping"))
    assert out == {"ok": True, "text": "PONG", "session_id": "20260702_1"}


def test_run_delegate_resume_line_builds_resume_argv_and_strips_it(monkeypatch):
    seen = {}

    async def fake_cli(argv, env=None, timeout_s=None):
        seen["argv"] = argv
        return (0, "DONE\n", "session_id: 20260702_1\n")

    monkeypatch.setattr(dr, "_run_cli", fake_cli)
    task = f"{dr.RESUME_LINE_PREFIX}20260702_1]\nAlso include the metrics."
    out = asyncio.run(dr.run_delegate(dr.REGISTRY["hermes"], task))
    argv = seen["argv"]
    assert "--resume" in argv and argv[argv.index("--resume") + 1] == "20260702_1"
    sent = argv[argv.index("-q") + 1]
    assert "RESUME-SESSION" not in sent and "Also include the metrics." in sent
    assert out["session_id"] == "20260702_1"


def test_run_delegate_strips_chat_noise_lines(monkeypatch):
    async def fake_cli(argv, env=None, timeout_s=None):
        return (0, "↻ Resumed session x (2 user messages)\n"
                   "↻ Working directory: /tmp\n\n"
                   "session_id: s1\nThe real answer.\n", "")

    monkeypatch.setattr(dr, "_run_cli", fake_cli)
    out = asyncio.run(dr.run_delegate(dr.REGISTRY["hermes"], "x"))
    assert out["text"] == "The real answer." and out["session_id"] == "s1"


def test_run_delegate_nonzero_exit_is_honest_failure(monkeypatch):
    async def fake_cli(argv, env=None, timeout_s=None):
        return (1, "", "boom")

    monkeypatch.setattr(dr, "_run_cli", fake_cli)
    out = asyncio.run(dr.run_delegate(dr.REGISTRY["hermes"], "x"))
    assert out["ok"] is False and "boom" in out["text"]


def test_run_delegate_timeout_is_honest_failure(monkeypatch):
    async def fake_cli(argv, env=None, timeout_s=None):
        raise asyncio.TimeoutError()

    monkeypatch.setattr(dr, "_run_cli", fake_cli)
    out = asyncio.run(dr.run_delegate(dr.REGISTRY["hermes"], "x", timeout_s=5))
    assert out["ok"] is False and "timed out" in out["text"]


def test_unwired_transport_fails_cleanly(monkeypatch):
    out = asyncio.run(dr.run_delegate(dr.REGISTRY["open_claw"], "x"))
    assert out["ok"] is False and "not wired" in out["text"]
