# Calendar source parity: same lead-window semantics as calendar_watch, dedupe via
# state.seen, no items when nothing is due. ICS injected — no network.
import asyncio
from datetime import datetime, timedelta

import initiative

NOW = datetime(2026, 7, 2, 9, 50, 0)


def _ics(*events):
    blocks = "".join(
        f"BEGIN:VEVENT\r\nSUMMARY:{title}\r\nDTSTART:{dt:%Y%m%dT%H%M%S}\r\nEND:VEVENT\r\n"
        for title, dt in events)
    return f"BEGIN:VCALENDAR\r\n{blocks}END:VCALENDAR\r\n"


def _run(coro):
    return asyncio.run(coro)


def test_due_event_becomes_high_item_with_traceable_ref(monkeypatch):
    monkeypatch.setenv("EVE_CAL_LEAD_MIN", "15")
    st = initiative.EngineState()
    ics = _ics(("Dentist", NOW + timedelta(minutes=10)), ("Lunch", NOW + timedelta(hours=3)))
    items = _run(initiative.calendar_source(st, NOW, 0.0, ics_text=ics))
    assert len(items) == 1
    it = items[0]
    assert (it.source, it.kind, it.urgency) == ("calendar", "event_reminder", "high")
    assert "Dentist" in it.headline and "Dentist" in it.instruction
    assert it.source_ref == (NOW + timedelta(minutes=10)).isoformat()


def test_seen_key_dedupes_across_ticks(monkeypatch):
    monkeypatch.setenv("EVE_CAL_LEAD_MIN", "15")
    st = initiative.EngineState()
    ics = _ics(("Dentist", NOW + timedelta(minutes=10)))
    items = _run(initiative.calendar_source(st, NOW, 0.0, ics_text=ics))
    st.seen.add(items[0].dedupe_key)   # router does this on delivery
    assert _run(initiative.calendar_source(st, NOW, 0.0, ics_text=ics)) == []


def test_no_ics_configured_returns_empty(monkeypatch):
    monkeypatch.delenv("JARVIS_CALENDAR_ICS_URL", raising=False)
    assert _run(initiative.calendar_source(initiative.EngineState(), NOW, 0.0)) == []
