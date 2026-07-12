# Tests for calendar_watch — proactive calendar surfacing without a cron subsystem.
# CI-safe: ICS text injected (no network), fixed `now`, fake announce/broadcast/notify.
import asyncio
from datetime import datetime, timedelta
from unittest.mock import AsyncMock


import calendar_watch


def _ics(*events):
    blocks = "".join(
        f"BEGIN:VEVENT\r\nSUMMARY:{title}\r\nDTSTART:{dt:%Y%m%dT%H%M%S}\r\nEND:VEVENT\r\n"
        for title, dt in events)
    return f"BEGIN:VCALENDAR\r\n{blocks}END:VCALENDAR\r\n"


NOW = datetime(2026, 7, 2, 9, 50, 0)


def _run(coro):
    return asyncio.run(coro)


def test_due_reminders_window_and_dedupe():
    evs = _run(calendar_watch.fetch_events(
        now=NOW, ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)),
                      ("Lunch", NOW + timedelta(hours=3)))))
    announced = set()
    due = calendar_watch.due_reminders(evs, NOW, 15, announced)
    assert [e["what"] for e in due] == ["Dentist"]          # only inside the lead window
    announced.add((due[0]["what"], due[0]["starts_at"]))
    assert calendar_watch.due_reminders(evs, NOW, 15, announced) == []   # dedupe


def test_all_day_events_never_remind():
    ics = ("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Trash day\r\n"
           f"DTSTART:{NOW:%Y%m%d}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
    evs = _run(calendar_watch.fetch_events(ics_text=ics, now=NOW))
    assert calendar_watch.due_reminders(evs, NOW, 15, set()) == []


def test_tick_speaks_reminder_when_live(monkeypatch):
    monkeypatch.setattr(calendar_watch.delivery_policy, "in_quiet_hours", lambda: False)
    ann, sent = AsyncMock(), []
    state = calendar_watch.WatchState()
    out = _run(calendar_watch.tick(
        state, announce=ann, broadcast=sent.append, is_alive=lambda: True, now=NOW,
        ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)))))
    assert ("reminder", "spoken") in out
    inst = ann.await_args[0][0]
    assert "Dentist" in inst and "minutes" in inst
    assert sent and sent[0]["type"] == "calendar_event"
    # second tick: already announced -> silent
    out2 = _run(calendar_watch.tick(
        state, announce=ann, broadcast=sent.append, is_alive=lambda: True, now=NOW,
        ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)))))
    assert out2 == []


def test_tick_pushes_when_away(monkeypatch):
    monkeypatch.setattr(calendar_watch.delivery_policy, "in_quiet_hours", lambda: False)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(calendar_watch.approval_push, "notify", ok)
    out = _run(calendar_watch.tick(
        calendar_watch.WatchState(), announce=AsyncMock(), broadcast=lambda e: None,
        is_alive=lambda: False, now=NOW,
        ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)))))
    assert ("reminder", "notified") in out
    assert "Dentist" in ok.await_args[0][0]                  # headline carries the event


def test_tick_quiet_hours_pushes_instead_of_speaking(monkeypatch):
    monkeypatch.setattr(calendar_watch.delivery_policy, "in_quiet_hours", lambda: True)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(calendar_watch.approval_push, "notify", ok)
    ann = AsyncMock()
    out = _run(calendar_watch.tick(
        calendar_watch.WatchState(), announce=ann, broadcast=lambda e: None,
        is_alive=lambda: True, now=NOW,
        ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)))))
    assert ("reminder", "notified") in out and ann.await_count == 0


def test_morning_brief_fires_once_within_window(monkeypatch):
    monkeypatch.setattr(calendar_watch.delivery_policy, "in_quiet_hours", lambda: False)
    monkeypatch.setenv("EVE_CAL_MORNING", "08:00")
    at_8 = NOW.replace(hour=8, minute=5)
    ann = AsyncMock()
    state = calendar_watch.WatchState()
    ics = _ics(("Dentist", at_8.replace(hour=10)), ("Standup", at_8.replace(hour=14)))
    out = _run(calendar_watch.tick(state, announce=ann, broadcast=lambda e: None,
                                   is_alive=lambda: True, now=at_8, ics_text=ics))
    assert ("morning", "spoken") in out
    inst = ann.await_args[0][0]
    assert "Dentist" in inst and "Standup" in inst and "2" in inst
    out2 = _run(calendar_watch.tick(state, announce=ann, broadcast=lambda e: None,
                                    is_alive=lambda: True, now=at_8, ics_text=ics))
    assert all(k != "morning" for k, _ in out2)              # once a day


def test_morning_brief_skipped_after_window(monkeypatch):
    # A boot at 3pm must not blurt a stale morning brief.
    monkeypatch.setattr(calendar_watch.delivery_policy, "in_quiet_hours", lambda: False)
    monkeypatch.setenv("EVE_CAL_MORNING", "08:00")
    out = _run(calendar_watch.tick(
        calendar_watch.WatchState(), announce=AsyncMock(), broadcast=lambda e: None,
        is_alive=lambda: True, now=NOW.replace(hour=15),
        ics_text=_ics(("Dentist", NOW.replace(hour=16)))))
    assert all(k != "morning" for k, _ in out)


def test_evening_brief_previews_tomorrow(monkeypatch):
    monkeypatch.setattr(calendar_watch.delivery_policy, "in_quiet_hours", lambda: False)
    monkeypatch.setenv("EVE_CAL_EVENING", "20:00")
    at_8pm = NOW.replace(hour=20, minute=1)
    tomorrow = at_8pm + timedelta(days=1)
    ann = AsyncMock()
    out = _run(calendar_watch.tick(
        calendar_watch.WatchState(), announce=ann, broadcast=lambda e: None,
        is_alive=lambda: True, now=at_8pm,
        ics_text=_ics(("Crew meeting", tomorrow.replace(hour=9)))))
    assert ("evening", "spoken") in out
    assert "Crew meeting" in ann.await_args[0][0]


def test_fetch_error_is_swallowed_and_scrubbed(monkeypatch, caplog):
    monkeypatch.setenv("JARVIS_CALENDAR_ICS_URL", "https://secret.example/cal.ics")
    monkeypatch.setenv("EVE_CAL_WATCH", "1")

    async def boom(days=2, *, ics_text=None):
        raise RuntimeError("fetch died for https://secret.example/cal.ics")
    monkeypatch.setattr(calendar_watch, "fetch_events", boom)
    out = _run(calendar_watch.tick(
        calendar_watch.WatchState(), announce=AsyncMock(), broadcast=lambda e: None,
        is_alive=lambda: True, now=NOW))
    assert out == []


def test_enabled_flag(monkeypatch):
    monkeypatch.delenv("JARVIS_CALENDAR_ICS_URL", raising=False)
    assert calendar_watch.enabled() is False
    monkeypatch.setenv("JARVIS_CALENDAR_ICS_URL", "https://x/cal.ics")
    assert calendar_watch.enabled() is True
    monkeypatch.setenv("EVE_CAL_WATCH", "0")
    assert calendar_watch.enabled() is False
