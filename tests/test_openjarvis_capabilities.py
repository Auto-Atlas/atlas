"""Tests for the OpenJarvis capability map (openjarvis_capabilities.py).

These guard the two things the BMAD review flagged as drift/bloat risks:
  * capability_hint() stays within its char budget for the voice prompt.
  * delegation_context() actually names the key subsystems EVE should route to.
  * drift_report() catches a referenced command vanishing from the live CLI.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import openjarvis_capabilities as oc  # noqa: E402


def test_hint_within_char_cap():
    hint = oc.capability_hint()
    assert hint, "hint must be non-empty"
    assert len(hint) <= oc.HINT_MAX_CHARS


def test_hint_mentions_delegation():
    assert "jarvis_agent" in oc.capability_hint()


def test_delegation_context_names_key_subsystems():
    ctx = oc.delegation_context().lower()
    for subsystem in ("scheduler", "workflow", "memory", "channel", "research"):
        assert subsystem in ctx, f"{subsystem} missing from delegation context"


def test_referenced_commands_are_covered_by_intent_map():
    # Every referenced command should appear in at least one intent row, so the
    # drift check and the map can't silently disagree.
    blob = " ".join(cli for _, cli in oc._INTENT_MAP)
    for cmd in oc._REFERENCED_COMMANDS:
        assert f"jarvis {cmd}" in blob or cmd in blob, f"{cmd} not in intent map"


def test_drift_report_is_safe_without_jarvis(monkeypatch):
    # If the binary can't be introspected, report no drift (no false positives).
    monkeypatch.setattr(oc, "_live_command_names", lambda timeout=3.0: set())
    report = oc.drift_report()
    assert report["missing"] == []


def test_drift_report_flags_missing_command(monkeypatch):
    # Simulate a live CLI that dropped 'scheduler'.
    live = set(oc._REFERENCED_COMMANDS) - {"scheduler"}
    monkeypatch.setattr(oc, "_live_command_names", lambda timeout=3.0: live)
    report = oc.drift_report()
    assert "scheduler" in report["missing"]
