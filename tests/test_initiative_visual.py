# Visual-first surfacing (the owner, 2026-07-02): every broadcast Item carries a structured
# `data` dict the app can render as a card — never just clipped prose.
import asyncio
from datetime import datetime, timedelta
from unittest.mock import AsyncMock

import pytest

import approval_push
import briefing
import initiative

NOW = datetime(2026, 7, 2, 9, 50, 0)
MORNING = datetime(2026, 7, 2, 8, 5, 0)


def _ics(*events):
    blocks = "".join(
        f"BEGIN:VEVENT\r\nSUMMARY:{title}\r\nDTSTART:{dt:%Y%m%dT%H%M%S}\r\nEND:VEVENT\r\n"
        for title, dt in events)
    return f"BEGIN:VCALENDAR\r\n{blocks}END:VCALENDAR\r\n"


def _run(coro):
    return asyncio.run(coro)


@pytest.fixture(autouse=True)
def env(tmp_path, monkeypatch):
    for k in ("JARVIS_CALENDAR_ICS_URL", "EVE_CAL_MORNING", "EVE_QUIET_HOURS",
              "EVE_INITIATIVE_EMAIL_POLL_S"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "p.json"))
    monkeypatch.setenv("EVE_CAL_LEAD_MIN", "15")


def test_calendar_item_carries_event_card_data():
    st = initiative.EngineState()
    items = _run(initiative.calendar_source(
        st, NOW, 0.0, ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)))))
    d = items[0].data
    assert d["what"] == "Dentist"
    assert d["starts_at"] == (NOW + timedelta(minutes=10)).isoformat()
    assert "when" in d


def test_email_item_carries_email_card_data(monkeypatch):
    import priorities
    monkeypatch.setattr(priorities, "load", lambda: {
        "personal_emails": ["wife@example.com"], "important_domains": [],
        "operational_tools": [], "high_intent_keywords": [],
        "ignore_keywords": [], "billing_security_keywords": []})
    st = initiative.EngineState()
    items = _run(initiative.email_source(st, NOW, 1000.0, messages=[
        {"from": "Wife", "from_email": "wife@example.com", "subject": "dinner",
         "date": "d1", "headers": {}}]))
    d = items[0].data
    assert d == {"from": "Wife", "from_email": "wife@example.com",
                 "subject": "dinner", "label": "VIP", "date": "d1"}


def test_morning_brief_carries_fact_list(monkeypatch):
    monkeypatch.setattr(briefing, "gather_briefing", AsyncMock(return_value={
        "weather": {"error": "off"}, "email": {"count": 2, "unread": []},
        "calendar": {"error": "off"}, "inbox": {"error": "off"}}))
    st = initiative.EngineState()
    items = _run(initiative.rhythm_source(st, MORNING, 0.0, ics_text=_ics()))
    d = items[0].data
    assert isinstance(d["facts"], list) and any("2 unread" in f for f in d["facts"])


def test_broadcast_payload_includes_data(monkeypatch):
    monkeypatch.setattr(approval_push, "notify", AsyncMock(return_value={"ntfy": True}))
    payloads = []
    st = initiative.EngineState()
    item = initiative.Item(
        source="calendar", kind="event_reminder", urgency="med", headline="h",
        instruction="i", body="b", dedupe_key="k", source_ref="r",
        data={"what": "Dentist"})
    _run(initiative.route_and_deliver(
        item, st, initiative.load_prefs(), announce=AsyncMock(),
        broadcast=payloads.append, is_alive=lambda: True, now_mono=0.0))
    assert payloads[0]["data"] == {"what": "Dentist"}
