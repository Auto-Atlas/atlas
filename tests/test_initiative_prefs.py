# Prefs + scoring for the initiative engine: bias shifts, mute, persistence, validation.
import pytest

import initiative


def _item(source="calendar", urgency="high"):
    return initiative.Item(
        source=source, kind="k", urgency=urgency, headline="h",
        instruction="i", body="b", dedupe_key=f"{source}:x", source_ref="ref")


def test_enabled_default_on(monkeypatch):
    monkeypatch.delenv("EVE_INITIATIVE", raising=False)
    assert initiative.enabled() is True
    monkeypatch.setenv("EVE_INITIATIVE", "0")
    assert initiative.enabled() is False


def test_load_prefs_missing_file_gives_defaults(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "prefs.json"))
    assert initiative.load_prefs() == {"sources": {}}


def test_adjust_persists_and_shifts_urgency(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "prefs.json"))
    initiative.adjust("email", "less")
    prefs = initiative.load_prefs()
    assert prefs["sources"]["email"] == {"bias": -1, "muted": False}
    # med email demoted to low
    assert initiative.effective_urgency(_item("email", "med"), prefs) == "low"
    # bias clamps at -2
    initiative.adjust("email", "less")
    initiative.adjust("email", "less")
    assert initiative.load_prefs()["sources"]["email"]["bias"] == -2


def test_mute_drops_and_reset_restores(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "prefs.json"))
    initiative.adjust("calendar", "mute")
    assert initiative.effective_urgency(_item("calendar"), initiative.load_prefs()) is None
    # "more" unmutes AND promotes
    initiative.adjust("calendar", "more")
    prefs = initiative.load_prefs()
    assert prefs["sources"]["calendar"] == {"bias": 1, "muted": False}
    assert initiative.effective_urgency(_item("calendar", "med"), prefs) == "high"
    initiative.adjust("calendar", "reset")
    assert initiative.load_prefs()["sources"]["calendar"] == {"bias": 0, "muted": False}


def test_adjust_validates_arguments(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "prefs.json"))
    with pytest.raises(ValueError):
        initiative.adjust("email", "never")
    with pytest.raises(ValueError):
        initiative.adjust("", "mute")


def test_load_prefs_corrupt_file_falls_back(tmp_path, monkeypatch):
    p = tmp_path / "prefs.json"
    p.write_text("{not json")
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(p))
    assert initiative.load_prefs() == {"sources": {}}


def test_unknown_urgency_treated_as_med(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "prefs.json"))
    assert initiative.effective_urgency(_item(urgency="weird"), initiative.load_prefs()) == "med"
