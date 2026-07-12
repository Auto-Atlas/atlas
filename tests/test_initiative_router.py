# Channel ladder: high->speak (quiet/dead -> push), med->push, low->hold; rate limit
# demotes event-driven items to hold; rhythms exempt; mute drops before any delivery.
import asyncio
from unittest.mock import AsyncMock

import pytest

import approval_push
import initiative


def _run(coro):
    return asyncio.run(coro)


def _item(source="calendar", urgency="high", key="k1"):
    return initiative.Item(
        source=source, kind="event_reminder", urgency=urgency, headline="Coming up: X",
        instruction="say X", body="X", dedupe_key=key, source_ref="2026-07-02T10:00:00")


@pytest.fixture
def no_push(monkeypatch):
    fake = AsyncMock(return_value={"ntfy": True})
    monkeypatch.setattr(approval_push, "notify", fake)
    return fake


@pytest.fixture
def prefs(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "p.json"))
    monkeypatch.delenv("EVE_QUIET_HOURS", raising=False)
    monkeypatch.delenv("EVE_INITIATIVE_MIN_GAP_S", raising=False)
    return initiative.load_prefs()


def test_high_speaks_live_and_arms_rate_limit(no_push, prefs):
    st = initiative.EngineState()
    announce = AsyncMock()
    status = _run(initiative.route_and_deliver(
        _item(), st, prefs, announce=announce, broadcast=lambda m: None,
        is_alive=lambda: True, now_mono=1000.0))
    assert status == "spoken"
    announce.assert_awaited_once_with("say X")
    assert st.last_interrupt == 1000.0
    assert "k1" in st.seen
    no_push.assert_not_awaited()


def test_high_pushes_when_session_dead(no_push, prefs):
    st = initiative.EngineState()
    status = _run(initiative.route_and_deliver(
        _item(), st, prefs, announce=AsyncMock(), broadcast=lambda m: None,
        is_alive=lambda: False, now_mono=1000.0))
    assert status == "notified"
    no_push.assert_awaited_once()


def test_quiet_hours_never_speak(no_push, prefs, monkeypatch):
    # NB: an env window like "0-24" parses to start==end -> NOT quiet (delivery_policy
    # wrap rule), so force the policy directly.
    import delivery_policy
    monkeypatch.setattr(delivery_policy, "in_quiet_hours", lambda now=None: True)
    st = initiative.EngineState()
    announce = AsyncMock()
    status = _run(initiative.route_and_deliver(
        _item(), st, prefs, announce=announce, broadcast=lambda m: None,
        is_alive=lambda: True, now_mono=1000.0))
    assert status == "notified"
    announce.assert_not_awaited()


def test_rate_limit_demotes_second_interrupt_to_hold(no_push, prefs):
    st = initiative.EngineState()
    a = AsyncMock()
    kw = dict(announce=a, broadcast=lambda m: None, is_alive=lambda: True)
    assert _run(initiative.route_and_deliver(
        _item(key="k1"), st, prefs, now_mono=1000.0, **kw)) == "spoken"
    assert _run(initiative.route_and_deliver(
        _item(key="k2"), st, prefs, now_mono=1010.0, **kw)) == "held"
    assert [i.dedupe_key for i in st.held] == ["k2"]
    # past the gap it speaks again
    assert _run(initiative.route_and_deliver(
        _item(key="k3"), st, prefs, now_mono=1300.0, **kw)) == "spoken"


def test_rhythm_exempt_from_rate_limit(no_push, prefs):
    st = initiative.EngineState()
    st.last_interrupt = 1000.0
    status = _run(initiative.route_and_deliver(
        _item(source="rhythm", key="r1"), st, prefs, announce=AsyncMock(),
        broadcast=lambda m: None, is_alive=lambda: True, now_mono=1010.0))
    assert status == "spoken"


def test_med_pushes_and_low_holds_with_cap(no_push, prefs):
    st = initiative.EngineState()
    kw = dict(announce=AsyncMock(), broadcast=lambda m: None, is_alive=lambda: True)
    assert _run(initiative.route_and_deliver(
        _item(urgency="med", key="m1"), st, prefs, now_mono=1000.0, **kw)) == "notified"
    for i in range(25):
        _run(initiative.route_and_deliver(
            _item(urgency="low", key=f"l{i}"), st, prefs, now_mono=2000.0 + i, **kw))
    assert len(st.held) == initiative.HELD_CAP


def test_muted_source_dropped_before_broadcast(no_push, prefs, tmp_path):
    initiative.adjust("calendar", "mute")
    prefs = initiative.load_prefs()
    st = initiative.EngineState()
    seen_broadcasts = []
    status = _run(initiative.route_and_deliver(
        _item(), st, prefs, announce=AsyncMock(), broadcast=seen_broadcasts.append,
        is_alive=lambda: True, now_mono=1000.0))
    assert status == "dropped"
    assert seen_broadcasts == [] and st.seen == set()


def test_broadcast_payload_traceable(no_push, prefs):
    st = initiative.EngineState()
    payloads = []
    _run(initiative.route_and_deliver(
        _item(urgency="med"), st, prefs, announce=AsyncMock(),
        broadcast=payloads.append, is_alive=lambda: True, now_mono=1000.0))
    assert payloads[0]["source"] == "calendar"
    assert payloads[0]["source_ref"] == "2026-07-02T10:00:00"


def test_push_failure_returns_queued(prefs, monkeypatch):
    monkeypatch.setattr(approval_push, "notify", AsyncMock(side_effect=RuntimeError("down")))
    st = initiative.EngineState()
    status = _run(initiative.route_and_deliver(
        _item(urgency="med"), st, prefs, announce=AsyncMock(),
        broadcast=lambda m: None, is_alive=lambda: True, now_mono=1000.0))
    assert status == "queued"
