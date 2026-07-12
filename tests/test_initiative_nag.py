# Nag source + wiring: calendar nudges and fired reminders become OPEN items that
# re-surface until confirmed complete. Quiet hours hold everything (nothing consumed);
# exhausted items get ONE honest last call; complete_reminder closes the loop.
import asyncio
from datetime import datetime, timedelta

import initiative
import nag_store

NOW = datetime(2026, 7, 2, 9, 50, 0)


def _run(coro):
    return asyncio.run(coro)


def _ics(*events):
    blocks = "".join(
        f"BEGIN:VEVENT\r\nSUMMARY:{title}\r\nDTSTART:{dt:%Y%m%dT%H%M%S}\r\nEND:VEVENT\r\n"
        for title, dt in events)
    return f"BEGIN:VCALENDAR\r\n{blocks}END:VCALENDAR\r\n"


def test_calendar_nudge_opens_a_nag_loop(monkeypatch):
    monkeypatch.setenv("EVE_CAL_LEAD_MIN", "15")
    st = initiative.EngineState()
    start = NOW + timedelta(minutes=10)
    items = _run(initiative.calendar_source(st, NOW, 0.0,
                                            ics_text=_ics(("Dentist", start))))
    assert len(items) == 1
    open_now = nag_store.pending(NOW.timestamp())
    assert len(open_now) == 1 and "Dentist" in open_now[0]["what"]
    assert open_now[0]["source"] == "calendar"
    # default: moot 60 min past the event start
    assert open_now[0]["expire_at"] == start.timestamp() + 3600
    # re-running the source can't mint a second loop for the same event
    _run(initiative.calendar_source(st, NOW, 0.0, ics_text=_ics(("Dentist", start))))
    assert len(nag_store.pending(NOW.timestamp())) == 1


def test_calendar_nag_knob_off(monkeypatch):
    monkeypatch.setenv("EVE_CAL_LEAD_MIN", "15")
    monkeypatch.setenv("EVE_CAL_NAG", "0")
    _run(initiative.calendar_source(initiative.EngineState(), NOW, 0.0,
                                    ics_text=_ics(("Dentist", NOW + timedelta(minutes=10)))))
    assert nag_store.pending(NOW.timestamp()) == []


def test_nag_source_resurfaces_until_confirmed():
    rec = nag_store.add("flip the steaks", source="reminder", ref="r1",
                        due=NOW.timestamp(), expire_at=NOW.timestamp() + 7200,
                        now=NOW.timestamp(), interval=600)
    later = NOW + timedelta(minutes=11)
    items = _run(initiative.nag_source(initiative.EngineState(), later, 0.0))
    assert len(items) == 1
    it = items[0]
    assert (it.source, it.kind, it.urgency) == ("nag", "open_reminder", "high")
    assert "flip the steaks" in it.instruction and "NOT been confirmed" in it.instruction
    assert it.dedupe_key == f"nag:{rec['id']}:1"          # unique per repeat — engine won't drop it
    # confirmed done -> silence
    nag_store.complete(rec["id"])
    even_later = NOW + timedelta(minutes=22)
    assert _run(initiative.nag_source(initiative.EngineState(), even_later, 0.0)) == []


def test_nag_source_holds_through_quiet_hours(monkeypatch):
    nag_store.add("flip the steaks", source="reminder", ref="r1",
                  due=NOW.timestamp(), expire_at=NOW.timestamp() + 7200,
                  now=NOW.timestamp(), interval=600)
    monkeypatch.setenv("EVE_QUIET_HOURS", "0-23")          # everything is quiet
    later = NOW + timedelta(minutes=11)
    assert _run(initiative.nag_source(initiative.EngineState(), later, 0.0)) == []
    # nothing was consumed: the morning tick still fires it
    monkeypatch.delenv("EVE_QUIET_HOURS")
    assert len(_run(initiative.nag_source(initiative.EngineState(), later, 0.0))) == 1


def test_nag_source_final_mention_is_low_urgency():
    nag_store.add("flip the steaks", source="reminder", ref="r1",
                  due=NOW.timestamp(), expire_at=NOW.timestamp() + 700_000,
                  now=NOW.timestamp(), interval=600, repeats_max=1)
    t1 = NOW + timedelta(minutes=11)
    assert len(_run(initiative.nag_source(initiative.EngineState(), t1, 0.0))) == 1
    t2 = NOW + timedelta(minutes=22)
    items = _run(initiative.nag_source(initiative.EngineState(), t2, 0.0))
    assert len(items) == 1
    assert (items[0].kind, items[0].urgency) == ("open_reminder_final", "low")
    # and then true silence
    t3 = NOW + timedelta(minutes=33)
    assert _run(initiative.nag_source(initiative.EngineState(), t3, 0.0)) == []


def test_nag_master_knob_off(monkeypatch):
    nag_store.add("flip the steaks", source="reminder", ref="r1",
                  due=NOW.timestamp(), expire_at=NOW.timestamp() + 7200,
                  now=NOW.timestamp(), interval=600)
    monkeypatch.setenv("EVE_NAG", "0")
    later = NOW + timedelta(minutes=11)
    assert _run(initiative.nag_source(initiative.EngineState(), later, 0.0)) == []


def test_nag_is_a_known_source_for_adjust_surfacing():
    assert "nag" in initiative.KNOWN_SOURCES


class _Params:
    def __init__(self, **arguments):
        self.arguments = arguments
        self.out = None

    async def result_callback(self, res):
        self.out = res


def test_complete_reminder_tool_closes_and_disambiguates():
    # The voice tool runs on WALL CLOCK (pending() default) — expiries must be real-future.
    import time as _time

    import nag_tool
    exp = _time.time() + 7200
    nag_store.add("Dentist at 4:00 PM", source="calendar", ref="c1",
                  due=NOW.timestamp(), expire_at=exp, now=NOW.timestamp())
    nag_store.add("dentist forms to sign", source="reminder", ref="r9",
                  due=NOW.timestamp(), expire_at=exp, now=NOW.timestamp())

    p = _Params(what="dentist")                            # ambiguous: both match
    _run(nag_tool.handle_complete_reminder(p))
    assert p.out["needs_disambiguation"] and len(p.out["matches"]) == 2

    chosen = p.out["matches"][0]["id"]
    p2 = _Params(what=chosen)                              # re-call with the chosen id
    _run(nag_tool.handle_complete_reminder(p2))
    assert p2.out["ok"] is True and "completed" in p2.out
    assert len(nag_store.pending(NOW.timestamp())) == 1

    p3 = _Params(what="the moon landing")                  # no match -> honest, with the list
    _run(nag_tool.handle_complete_reminder(p3))
    assert p3.out["ok"] is False and len(p3.out["open"]) == 1


def test_complete_reminder_tool_snoozes():
    import nag_tool
    rec = nag_store.add("flip the steaks", source="reminder", ref="r1",
                        due=NOW.timestamp(), expire_at=NOW.timestamp() + 7200,
                        now=NOW.timestamp(), interval=600)
    p = _Params(what="steaks", snooze_minutes=30)
    _run(nag_tool.handle_complete_reminder(p))
    assert p.out["ok"] is True and p.out["snoozed"] == "flip the steaks"
    assert len(nag_store.pending(NOW.timestamp())) == 1    # paused, not closed
    assert nag_store.find(rec["id"])[0]["next_at"] > NOW.timestamp() + 29 * 60


def test_fired_reminder_enters_ack_loop(monkeypatch):
    # reminders_tool fire() hands the record to the nag store — fired is not finished.
    import time as _time

    import reminders_tool

    monkeypatch.setenv("JARVIS_REMINDERS_FILE", "/nonexistent")   # store path unused here
    r = {"id": "abc123", "what": "call Alex back", "due": _time.time()}
    reminders_tool._to_nag_store(r)
    open_now = nag_store.pending()
    assert [x["what"] for x in open_now] == ["call Alex back"]
    assert open_now[0]["source"] == "reminder" and open_now[0]["ref"] == "abc123"
