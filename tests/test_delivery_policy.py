# Tests for delivery_policy — context-aware ping-back (EVE Agent Hub §11.H).
from datetime import datetime

import delivery_policy as dp


def test_no_quiet_window_by_default(monkeypatch):
    monkeypatch.delenv("EVE_QUIET_HOURS", raising=False)
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 3, 0)) is False   # 3am, but no window set
    assert dp.decide(quiet=False) == dp.SPEAK


def test_quiet_window_wrapping_midnight(monkeypatch):
    monkeypatch.setenv("EVE_QUIET_HOURS", "22-7")
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 23, 0)) is True    # 11pm -> quiet
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 2, 0)) is True     # 2am -> quiet
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 12, 0)) is False   # noon -> not quiet


def test_quiet_window_same_day(monkeypatch):
    monkeypatch.setenv("EVE_QUIET_HOURS", "9-17")
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 12, 0)) is True
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 20, 0)) is False


def test_malformed_window_is_no_quiet(monkeypatch):
    monkeypatch.setenv("EVE_QUIET_HOURS", "garbage")
    assert dp.in_quiet_hours(datetime(2026, 6, 22, 3, 0)) is False


def test_decide_quiet_notifies(monkeypatch):
    assert dp.decide(quiet=True) == dp.NOTIFY
    assert dp.decide(quiet=False) == dp.SPEAK


def test_headline_carries_the_answer_not_a_teaser():
    h = dp.headline({"agent": "hermes", "result": {"text": "Marco says 9am works"}})
    assert "Marco says 9am works" in h and "hermes" in h


def test_headline_falls_back_to_summary_when_no_text():
    h = dp.headline({"agent": "hermes", "summary": "text Marco", "result": {}})
    assert "text Marco" in h
