"""BYDAY expansion in weekly RRULE — a MWF meeting must show all three days,
not just one. Guards the calendar_tool.parse_ics_events fix.

parse_ics_events returns public dicts with a 'when' string like
'Mon Jun 15 09:00 AM' (the internal _sort key is stripped), so we assert on that.
"""

import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from calendar_tool import parse_ics_events  # noqa: E402


def _ics(rrule: str) -> str:
    # Mon Jun 15 2026, 09:00
    return (
        "BEGIN:VEVENT\r\n"
        "SUMMARY:Standup\r\n"
        "DTSTART:20260615T090000\r\n"
        f"RRULE:{rrule}\r\n"
        "END:VEVENT\r\n"
    )


def _weekdays(events) -> set[str]:
    return {e["when"].split()[0] for e in events}  # 'Mon', 'Wed', ...


def test_weekly_byday_expands_all_named_days():
    # One-week window Mon..Sun; MWF rule must yield Mon, Wed, Fri.
    start = datetime(2026, 6, 15, 0, 0)
    end = datetime(2026, 6, 21, 23, 59)
    events = parse_ics_events(_ics("FREQ=WEEKLY;BYDAY=MO,WE,FR"), start, end)
    assert len(events) == 3, events
    assert _weekdays(events) == {"Mon", "Wed", "Fri"}, events


def test_weekly_byday_preserves_time_of_day():
    start = datetime(2026, 6, 15, 0, 0)
    end = datetime(2026, 6, 21, 23, 59)
    events = parse_ics_events(_ics("FREQ=WEEKLY;BYDAY=MO,WE,FR"), start, end)
    assert all("09:00 AM" in e["when"] for e in events), events


def test_weekly_without_byday_unchanged():
    # No BYDAY: still one occurrence per week on the anchor weekday (Mon).
    start = datetime(2026, 6, 15, 0, 0)
    end = datetime(2026, 6, 28, 23, 59)
    events = parse_ics_events(_ics("FREQ=WEEKLY"), start, end)
    assert len(events) == 2, events
    assert _weekdays(events) == {"Mon"}, events
