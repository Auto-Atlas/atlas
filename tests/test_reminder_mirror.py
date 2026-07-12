# Tests for reminder mirroring: a set reminder ALSO lands on the Google calendar
# and rings the phone (set_alarm FCM push); cancel takes both mirrors down.
#
# Contract pinned here:
#   (1) handle_set fans out to _mirror_out AFTER persisting (fire-and-forget),
#   (2) handle_cancel fans out to _unmirror with the victim,
#   (3) EVE_REMINDER_MIRROR=0 disables the fan-out entirely,
#   (4) a broken calendar/push leg can NEVER break the set path (best-effort),
#   (5) the set_alarm payload is the string-map contract the Android app parses,
#   (6) the created calendar event id is stamped onto the stored record so
#       cancel can delete the event.
import asyncio
import importlib
import os
import sys
import tempfile
import types

import pytest


@pytest.fixture
def rt(monkeypatch):
    d = tempfile.mkdtemp()
    store = os.path.join(d, "reminders.json")
    monkeypatch.setenv("JARVIS_REMINDERS_FILE", store)
    monkeypatch.delenv("EVE_REMINDER_MIRROR", raising=False)
    monkeypatch.delenv("EVE_REMINDER_CAL_MIN_MINUTES", raising=False)
    import reminders_tool
    importlib.reload(reminders_tool)
    return reminders_tool


class _Params:
    def __init__(self, arguments):
        self.arguments = arguments
        self.results = []

    async def result_callback(self, result):
        self.results.append(result)


async def _drain(rt):
    """Let fire-and-forget mirror tasks run to completion."""
    while rt._MIRROR_TASKS:
        await asyncio.gather(*list(rt._MIRROR_TASKS), return_exceptions=True)


def _fake_gnative(created: list, deleted: list, event_id: str = "ev123"):
    m = types.ModuleType("google_calendar_native")
    m.is_configured = lambda: True
    m.is_connected = lambda: True

    async def create_event(title, start, duration_min=60, all_day=False, calendar_id="primary"):
        created.append({"title": title, "start": start, "duration_min": duration_min})
        return {"id": event_id, "htmlLink": "", "summary": title}

    async def delete_event(eid, calendar_id="primary"):
        deleted.append(eid)
        return True

    m.create_event = create_event
    m.delete_event = delete_event
    return m


def _fake_push(pushes: list):
    m = types.ModuleType("push_sender")

    def broadcast_data(data, ttl_s=0):
        pushes.append(dict(data))
        return [{"token": "tok…", "ok": True, "detail": "fake"}]

    m.broadcast_data = broadcast_data
    return m


# ---------------------------------------------------------------------------
# Fan-out wiring
# ---------------------------------------------------------------------------
def test_set_spawns_mirror_with_the_persisted_reminder(rt, monkeypatch):
    seen = []

    async def fake_mirror(r):
        seen.append(r)

    monkeypatch.setattr(rt, "_mirror_out", fake_mirror)

    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        p = _Params({"what": "call Alex back", "minutes_from_now": 45})
        await svc.handle_set(p)
        await _drain(rt)
        assert p.results[0]["ok"] is True
        svc.cancel_all()

    asyncio.run(scenario())
    assert len(seen) == 1
    assert seen[0]["what"] == "call Alex back"
    assert seen[0]["id"]  # persisted record, not a copy missing the id


def test_cancel_spawns_unmirror_with_the_victim(rt, monkeypatch):
    seen = []

    async def fake_unmirror(r):
        seen.append(r)

    monkeypatch.setattr(rt, "_mirror_out", lambda r: asyncio.sleep(0))
    monkeypatch.setattr(rt, "_unmirror", fake_unmirror)

    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        await svc.handle_set(_Params({"what": "flip steaks", "minutes_from_now": 30}))
        await _drain(rt)
        p = _Params({"number": 1})
        await svc.handle_cancel(p)
        await _drain(rt)
        assert p.results[0]["ok"] is True
        svc.cancel_all()

    asyncio.run(scenario())
    assert [r["what"] for r in seen] == ["flip steaks"]


def test_mirror_disabled_by_env(rt, monkeypatch):
    monkeypatch.setenv("EVE_REMINDER_MIRROR", "0")
    called = []
    monkeypatch.setattr(rt, "_mirror_out", lambda r: called.append(r) or asyncio.sleep(0))

    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        p = _Params({"what": "quiet one", "minutes_from_now": 10})
        await svc.handle_set(p)
        await _drain(rt)
        assert p.results[0]["ok"] is True
        svc.cancel_all()

    asyncio.run(scenario())
    assert called == []


# ---------------------------------------------------------------------------
# Best-effort: broken mirror legs never break the set path
# ---------------------------------------------------------------------------
def test_broken_calendar_and_push_never_break_set(rt, monkeypatch):
    bad_cal = types.ModuleType("google_calendar_native")
    bad_cal.is_configured = lambda: True
    bad_cal.is_connected = lambda: (_ for _ in ()).throw(RuntimeError("gcal down"))
    bad_push = types.ModuleType("push_sender")
    bad_push.broadcast_data = lambda data, ttl_s=0: (_ for _ in ()).throw(RuntimeError("fcm down"))
    monkeypatch.setitem(sys.modules, "google_calendar_native", bad_cal)
    monkeypatch.setitem(sys.modules, "push_sender", bad_push)

    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        p = _Params({"what": "survives outages", "minutes_from_now": 20})
        await svc.handle_set(p)
        await _drain(rt)
        assert p.results[0]["ok"] is True
        assert [r["what"] for r in rt._load()] == ["survives outages"]
        svc.cancel_all()

    asyncio.run(scenario())


# ---------------------------------------------------------------------------
# Payload contract + event-id stamping (real _mirror_out/_unmirror, fake deps)
# ---------------------------------------------------------------------------
def test_mirror_out_pushes_alarm_contract_and_stamps_event_id(rt, monkeypatch):
    created, deleted, pushes = [], [], []
    monkeypatch.setitem(sys.modules, "google_calendar_native", _fake_gnative(created, deleted))
    monkeypatch.setitem(sys.modules, "push_sender", _fake_push(pushes))

    import time as _time
    r = {"id": "abc123def456", "due": _time.time() + 3600, "what": "dentist"}
    rt._save([dict(r)])
    asyncio.run(rt._mirror_out(r))

    # Calendar leg: titled event created, id stamped onto the stored record.
    assert created and created[0]["title"] == "Reminder: dentist"
    assert rt._load()[0]["gcal_event_id"] == "ev123"
    # Alarm leg: string-map payload matching the Android parser's contract.
    assert pushes == [{
        "type": "set_alarm", "id": "abc123def456",
        "due_epoch": str(int(r["due"])), "what": "dentist",
    }]


def test_unmirror_deletes_event_and_pushes_cancel(rt, monkeypatch):
    created, deleted, pushes = [], [], []
    monkeypatch.setitem(sys.modules, "google_calendar_native", _fake_gnative(created, deleted))
    monkeypatch.setitem(sys.modules, "push_sender", _fake_push(pushes))

    r = {"id": "abc123def456", "due": 9e12, "what": "dentist", "gcal_event_id": "ev123"}
    asyncio.run(rt._unmirror(r))

    assert deleted == ["ev123"]
    assert pushes == [{"type": "cancel_alarm", "id": "abc123def456"}]


def test_unmirror_without_event_id_only_cancels_alarm(rt, monkeypatch):
    created, deleted, pushes = [], [], []
    monkeypatch.setitem(sys.modules, "google_calendar_native", _fake_gnative(created, deleted))
    monkeypatch.setitem(sys.modules, "push_sender", _fake_push(pushes))

    asyncio.run(rt._unmirror({"id": "noevent00000", "due": 9e12, "what": "x"}))
    assert deleted == []
    assert pushes == [{"type": "cancel_alarm", "id": "noevent00000"}]


def _fake_approval_push(events: list):
    m = types.ModuleType("approval_push")

    async def notify_event(summary, *, title, tags="bell", priority="high"):
        events.append({"summary": summary, "title": title, "tags": tags})
        return True

    m.notify_event = notify_event
    return m


def test_fire_pushes_phone_notification(rt, monkeypatch):
    # The pocket leg: when a reminder comes DUE, an ntfy notification goes out —
    # works today with FCM unconfigured (ntfy is the live approval-push channel).
    monkeypatch.setenv("EVE_REMINDER_NAG", "0")
    events = []
    monkeypatch.setitem(sys.modules, "approval_push", _fake_approval_push(events))
    monkeypatch.setitem(sys.modules, "google_calendar_native", _fake_gnative([], []))
    monkeypatch.setitem(sys.modules, "push_sender", _fake_push([]))

    async def scenario():
        spoken = []

        async def announce(text):
            spoken.append(text)

        svc = rt.ReminderService(announce=announce)
        await svc.handle_set(_Params({"what": "flip steaks", "minutes_from_now": 0.001}))
        await asyncio.sleep(0.3)  # let the ~60ms timer fire
        await _drain(rt)
        assert spoken, "the spoken announce should still fire"
        svc.cancel_all()

    asyncio.run(scenario())
    assert [e["summary"] for e in events] == ["flip steaks"]
    assert "due" in events[0]["title"]


def test_missed_at_boot_pushes_phone_notification(rt, monkeypatch):
    monkeypatch.setenv("EVE_REMINDER_NAG", "0")
    events = []
    monkeypatch.setitem(sys.modules, "approval_push", _fake_approval_push(events))

    import time as _time
    rt._save([{"id": "late00000000", "due": _time.time() - 120, "what": "came due offline"}])

    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: asyncio.sleep(0))

        async def announce(text):
            return None

        svc._announce = announce
        await svc.start()
        await _drain(rt)
        svc.cancel_all()

    asyncio.run(scenario())
    assert [e["summary"] for e in events] == ["came due offline"]
    assert "missed" in events[0]["title"]


def test_calendar_min_minutes_skips_short_timers(rt, monkeypatch):
    monkeypatch.setenv("EVE_REMINDER_CAL_MIN_MINUTES", "30")
    created, deleted, pushes = [], [], []
    monkeypatch.setitem(sys.modules, "google_calendar_native", _fake_gnative(created, deleted))
    monkeypatch.setitem(sys.modules, "push_sender", _fake_push(pushes))

    import time as _time
    r = {"id": "egg000000000", "due": _time.time() + 300, "what": "egg timer"}
    asyncio.run(rt._mirror_out(r))

    assert created == []          # 5-min timer stays off the calendar
    assert len(pushes) == 1       # but the phone alarm still gets set
    assert pushes[0]["type"] == "set_alarm"
