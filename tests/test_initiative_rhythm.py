# Daily rhythms: once-a-day inside a 1-hour window; morning uses REAL briefing facts;
# evening previews tomorrow; week-ahead fires Sundays only. All data injected.
import asyncio
from datetime import datetime, timedelta
from unittest.mock import AsyncMock

import pytest

import briefing
import initiative

MORNING = datetime(2026, 7, 2, 8, 5, 0)      # Thursday, inside 08:00+1h
EVENING = datetime(2026, 7, 2, 20, 30, 0)
SUNDAY_5PM = datetime(2026, 7, 5, 17, 10, 0)  # 2026-07-05 is a Sunday


def _ics(*events):
    blocks = "".join(
        f"BEGIN:VEVENT\r\nSUMMARY:{title}\r\nDTSTART:{dt:%Y%m%dT%H%M%S}\r\nEND:VEVENT\r\n"
        for title, dt in events)
    return f"BEGIN:VCALENDAR\r\n{blocks}END:VCALENDAR\r\n"


def _run(coro):
    return asyncio.run(coro)


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for k in ("JARVIS_CALENDAR_ICS_URL", "EVE_CAL_MORNING", "EVE_CAL_EVENING",
              "EVE_WEEK_AHEAD"):
        monkeypatch.delenv(k, raising=False)


@pytest.fixture(autouse=True)
def fake_briefing(monkeypatch):
    monkeypatch.setattr(briefing, "gather_briefing", AsyncMock(return_value={
        "weather": {"error": "off"}, "email": {"count": 2, "unread": []},
        "calendar": {"error": "off"}, "inbox": {"error": "off"}}))


def test_morning_brief_fires_once_with_real_facts():
    st = initiative.EngineState()
    items = _run(initiative.rhythm_source(st, MORNING, 0.0, ics_text=_ics()))
    assert [i.kind for i in items] == ["morning_brief"]
    assert "2 unread" in items[0].instruction        # real fact, not invented
    assert "do NOT call tools" in items[0].instruction
    # second tick same day: nothing
    assert _run(initiative.rhythm_source(st, MORNING, 0.0, ics_text=_ics())) == []


def test_stale_window_boot_at_3pm_stays_silent():
    st = initiative.EngineState()
    at_3pm = MORNING.replace(hour=15)
    assert _run(initiative.rhythm_source(st, at_3pm, 0.0, ics_text=_ics())) == []


def test_evening_lookahead_previews_tomorrow():
    st = initiative.EngineState()
    tomorrow = EVENING + timedelta(days=1)
    items = _run(initiative.rhythm_source(
        st, EVENING, 0.0, ics_text=_ics(("Crew route", tomorrow.replace(hour=9)))))
    assert [i.kind for i in items] == ["evening_lookahead"]
    assert "Crew route" in items[0].instruction


def test_week_ahead_only_on_sunday():
    st = initiative.EngineState()
    ics = _ics(("Invoice day", SUNDAY_5PM + timedelta(days=2)))
    items = _run(initiative.rhythm_source(st, SUNDAY_5PM, 0.0, ics_text=ics))
    assert [i.kind for i in items] == ["week_ahead"]
    assert "Invoice day" in items[0].instruction
    # same clock time on a Thursday: no week-ahead
    st2 = initiative.EngineState()
    thursday_5pm = datetime(2026, 7, 2, 17, 10, 0)
    assert _run(initiative.rhythm_source(st2, thursday_5pm, 0.0, ics_text=ics)) == []


def test_no_ics_still_gives_morning_brief_but_skips_lookaheads():
    st = initiative.EngineState()
    items = _run(initiative.rhythm_source(st, MORNING, 0.0))   # no ics_text, no env URL
    assert [i.kind for i in items] == ["morning_brief"]
    st2 = initiative.EngineState()
    assert _run(initiative.rhythm_source(st2, EVENING, 0.0)) == []
