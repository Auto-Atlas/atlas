# Detached jarvis_agent (live-delegation-approvals follow-up, the owner 2026-07-10:
# "if we handoff a task we don't want EVE tied up"): with the body's announce seam wired,
# the tool call returns "on it" IMMEDIATELY, the brain runs in the background, and the
# result is spoken through the seam when it lands. Seam absent / flag off => the original
# blocking behavior, bit-for-bit.
import asyncio
import types

import pytest

import agent_bridge


@pytest.fixture(autouse=True)
def _reset_seam():
    yield
    agent_bridge.set_detached_announce(None)


def _params(task="do the thing", brain="codex"):
    calls = []

    async def cb(r):
        calls.append(r)

    return types.SimpleNamespace(arguments={"task": task, "brain": brain},
                                 result_callback=cb), calls


def _fake_brain(monkeypatch, *, delay=0.05, result="the answer", fail=False):
    started = asyncio.Event()

    async def runner(task, **kw):
        started.set()
        await asyncio.sleep(delay)
        if fail:
            raise RuntimeError("brain exploded")
        return result

    monkeypatch.setitem(agent_bridge._BRAINS, "codex", runner)
    return started


async def test_detached_returns_immediately_and_announces_result_later(monkeypatch):
    started = _fake_brain(monkeypatch, delay=0.1, result="built the page")
    spoken = []

    async def announce(instruction, cid):
        spoken.append(instruction)
        return "spoken"

    agent_bridge.set_detached_announce(announce)
    emitted = []

    async def emit(e):
        emitted.append(e)

    handler = agent_bridge.make_jarvis_agent_handler(emit=emit)
    params, calls = _params()
    await handler(params)

    # The tool answered BEFORE the brain even started — EVE is free.
    assert calls and calls[0].get("detached") is True
    assert not spoken, "result must not be announced before the brain finishes"
    await asyncio.wait_for(started.wait(), 1)   # background run does start
    assert not spoken, "still running — nothing spoken yet"

    # Let the background run land.
    for _ in range(50):
        if spoken:
            break
        await asyncio.sleep(0.02)
    assert spoken and "built the page" in spoken[0]
    assert "UNTRUSTED" in spoken[0]
    ends = [e for e in emitted if e["type"] == "delegation_end"]
    assert ends and ends[0]["ok"] is True and ends[0]["result"] == "built the page"


async def test_detached_failure_announces_blocker_honestly(monkeypatch):
    _fake_brain(monkeypatch, fail=True)
    spoken = []

    async def announce(instruction, cid):
        spoken.append(instruction)

    agent_bridge.set_detached_announce(announce)
    handler = agent_bridge.make_jarvis_agent_handler(emit=None)
    params, calls = _params()
    await handler(params)
    assert calls[0].get("detached") is True
    for _ in range(50):
        if spoken:
            break
        await asyncio.sleep(0.02)
    assert spoken and "could NOT" in spoken[0] and "brain exploded" in spoken[0]


async def test_no_seam_keeps_blocking_behavior(monkeypatch):
    _fake_brain(monkeypatch, result="sync answer")
    handler = agent_bridge.make_jarvis_agent_handler(emit=None)
    params, calls = _params()
    await handler(params)
    assert calls and calls[0].get("ok") is True
    assert calls[0].get("result") == "sync answer"       # full blocking contract
    assert "detached" not in calls[0]


async def test_flag_off_keeps_blocking_even_with_seam(monkeypatch):
    monkeypatch.setenv("EVE_JARVIS_AGENT_DETACHED", "0")
    _fake_brain(monkeypatch, result="sync answer")

    async def announce(instruction, cid):
        raise AssertionError("must not announce in blocking mode")

    agent_bridge.set_detached_announce(announce)
    handler = agent_bridge.make_jarvis_agent_handler(emit=None)
    params, calls = _params()
    await handler(params)
    assert calls[0].get("result") == "sync answer"
