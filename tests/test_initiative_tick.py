# tick(): per-source isolation (one bad source never kills the pass), disabled gate,
# and the held-digest flush into the next rhythm brief.
import asyncio
from datetime import datetime
from unittest.mock import AsyncMock

import pytest

import approval_push
import initiative

NOW = datetime(2026, 7, 2, 9, 0, 0)


def _run(coro):
    return asyncio.run(coro)


def _item(source="calendar", urgency="high", key="k1", instruction="say X"):
    return initiative.Item(
        source=source, kind="t", urgency=urgency, headline=f"H:{key}",
        instruction=instruction, body="b", dedupe_key=key, source_ref="r")


@pytest.fixture(autouse=True)
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "p.json"))
    monkeypatch.delenv("EVE_QUIET_HOURS", raising=False)
    monkeypatch.delenv("EVE_INITIATIVE", raising=False)
    monkeypatch.setattr(approval_push, "notify", AsyncMock(return_value={"ntfy": True}))


def test_bad_source_isolated_good_source_still_delivers():
    async def bad(state, now, now_mono):
        raise RuntimeError("feed down")

    async def good(state, now, now_mono):
        return [_item()]

    st = initiative.EngineState()
    out = _run(initiative.tick(st, announce=AsyncMock(), broadcast=lambda m: None,
                               is_alive=lambda: True, now=NOW, now_mono=1000.0,
                               sources=[bad, good]))
    assert out == [("t", "spoken")]


def test_disabled_gate(monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE", "0")
    st = initiative.EngineState()
    out = _run(initiative.tick(st, announce=AsyncMock(), broadcast=lambda m: None,
                               is_alive=lambda: True, now=NOW))
    assert out == []


def test_held_items_flush_into_next_rhythm_brief():
    st = initiative.EngineState()
    st.held.append(_item(urgency="low", key="lead1"))
    st.held.append(_item(urgency="low", key="lead2"))
    spoken = []

    async def announce(text):
        spoken.append(text)

    async def rhythm(state, now, now_mono):
        return [_item(source="rhythm", key="brief", instruction="Morning brief.")]

    out = _run(initiative.tick(st, announce=announce, broadcast=lambda m: None,
                               is_alive=lambda: True, now=NOW, now_mono=1000.0,
                               sources=[rhythm]))
    assert out == [("t", "spoken")]
    assert "H:lead1" in spoken[0] and "H:lead2" in spoken[0]
    assert st.held == []


def test_muted_rhythm_does_not_consume_held():
    initiative.adjust("rhythm", "mute")
    st = initiative.EngineState()
    st.held.append(_item(urgency="low", key="lead1"))

    async def rhythm(state, now, now_mono):
        return [_item(source="rhythm", key="brief")]

    out = _run(initiative.tick(st, announce=AsyncMock(), broadcast=lambda m: None,
                               is_alive=lambda: True, now=NOW, now_mono=1000.0,
                               sources=[rhythm]))
    assert out == [("t", "dropped")]
    assert len(st.held) == 1
