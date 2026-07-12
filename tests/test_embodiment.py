# EVE ↔ embodiment platform wiring: MCP client against a fake stdio server, in-handler
# confirm gate for motion (estop NEVER gated), events surfacing via the initiative
# engine, flag/owner gating.
import asyncio
import json
import sys
import textwrap
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import embodiment_tool
import initiative
import tool_policy

FAKE_SERVER = textwrap.dedent("""\
    import json, sys
    for line in sys.stdin:
        req = json.loads(line)
        m, i = req.get("method"), req.get("id")
        if m == "initialize":
            out = {"serverInfo": {"name": "fake-embody"}}
        elif m == "tools/call":
            name = req["params"]["name"]
            args = req["params"].get("arguments") or {}
            if name == "sim_start":
                payload = {"session": "s1", "robot": args.get("robot"), "time": 0.0}
            elif name == "motion_grasp":
                payload = {"session": args["session"], "estopped": False, "time": 1.5,
                           "ctrl": [1.0], "qpos": [1.0], "actuators": ["a"]}
            elif name == "motion_estop":
                payload = {"session": args["session"], "estopped": True}
            elif name == "look":
                payload = {"provider": "sim", "path": "/tmp/fake-look.png"}
            else:
                payload = {"echo": name}
            out = {"content": [{"type": "text", "text": json.dumps(payload)}],
                   "isError": False}
        else:
            out = {}
        sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": i, "result": out}) + "\\n")
        sys.stdout.flush()
""")


def _params(**arguments):
    return SimpleNamespace(arguments=arguments, result_callback=AsyncMock())


@pytest.fixture
def wired(tmp_path, monkeypatch):
    server = tmp_path / "fake_embody.py"
    server.write_text(FAKE_SERVER)
    monkeypatch.setenv("EVE_EMBODIMENT", "1")
    monkeypatch.setenv("EVE_EMBODIMENT_CMD", f"{sys.executable} {server}")
    monkeypatch.setenv("EVE_EMBODIMENT_EVENTS", str(tmp_path / "events.jsonl"))
    monkeypatch.setattr(embodiment_tool, "_EVENTS", tmp_path / "events.jsonl")
    monkeypatch.setattr(embodiment_tool, "_client", None)
    monkeypatch.setattr(embodiment_tool, "_last_session", None)
    yield tmp_path
    if embodiment_tool._client is not None:
        embodiment_tool._client.close()


def _out(p):
    return p.result_callback.await_args.args[0]


def test_disabled_flag_refuses(monkeypatch):
    monkeypatch.setenv("EVE_EMBODIMENT", "0")
    p = _params(action="look")
    asyncio.run(embodiment_tool.handle_embodiment(p))
    assert _out(p)["ok"] is False and "disabled" in _out(p)["error"]


def test_motion_draft_then_confirmed_executes(wired):
    # boot the sim first
    p0 = _params(action="sim_start", robot="ruka-hand")
    asyncio.run(embodiment_tool.handle_embodiment(p0))
    assert _out(p0)["session"] == "s1"
    # motion without confirmed -> draft only, nothing executed
    p1 = _params(action="grasp", close=1.0)
    asyncio.run(embodiment_tool.handle_embodiment(p1))
    assert _out(p1).get("draft") is True and "confirmed=true" in _out(p1)["instruction"]
    # confirmed -> executes and writes a surfacing event
    p2 = _params(action="grasp", close=1.0, confirmed=True)
    asyncio.run(embodiment_tool.handle_embodiment(p2))
    assert _out(p2)["ok"] is True and _out(p2)["time"] == 1.5
    events = [json.loads(x) for x in
              (wired / "events.jsonl").read_text().splitlines()]
    assert [e["action"] for e in events] == ["sim_start", "grasp"]


def test_estop_is_never_gated(wired):
    p0 = _params(action="sim_start")
    asyncio.run(embodiment_tool.handle_embodiment(p0))
    p = _params(action="estop")   # no confirmed — must act instantly
    asyncio.run(embodiment_tool.handle_embodiment(p))
    assert _out(p)["ok"] is True and _out(p)["estopped"] is True


def test_motion_without_sim_is_honest(wired):
    p = _params(action="grasp", confirmed=True)
    asyncio.run(embodiment_tool.handle_embodiment(p))
    assert _out(p)["ok"] is False and "no sim is running" in _out(p)["error"]


def test_owner_gated_and_skill_present():
    assert "embodiment" in tool_policy.OWNER_ONLY
    assert tool_policy.tier_allows("embodiment", "medium", "known") is False
    from skill_loader import load_skills
    assert load_skills().get("embodiment") is not None


def test_initiative_surfaces_embodiment_events(tmp_path):
    events = tmp_path / "events.jsonl"
    events.write_text(
        json.dumps({"ts": 1.0, "action": "look",
                    "data": {"path": "/tmp/l.png", "provider": "sim"}}) + "\n" +
        json.dumps({"ts": 2.0, "action": "estop",
                    "data": {"session": "s1", "estopped": True}}) + "\n")
    st = initiative.EngineState()
    items = asyncio.run(initiative.embodiment_source(
        st, datetime(2026, 7, 2, 12, 0), 0.0, events_path=events))
    assert [i.kind for i in items] == ["embodiment_look", "embodiment_estop"]
    assert items[0].urgency == "low" and items[0].data["path"] == "/tmp/l.png"
    assert items[1].urgency == "high"
    # offset advances: second scan is silent
    assert asyncio.run(initiative.embodiment_source(
        st, datetime(2026, 7, 2, 12, 1), 1.0, events_path=events)) == []
