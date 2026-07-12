# Tests for rituals.py — the proactive 5 AM morning protocol content engine.
# Uses stub briefing data (no network) + a stub dashboard so the assertions are
# deterministic and don't depend on live email/weather/calendar.
import rituals


_STUB_DATA = {
    "weather": {
        "place": "Worcester, Massachusetts",
        "now": {"temp_f": 70, "conditions": "overcast"},
        "today": {"high_f": 73, "low_f": 55, "precip_chance_pct": 99},
    },
    "email": {"count": 2, "unread": [{"from": "LinkedIn", "subject": "42 impressions"}]},
    "calendar": {"events": [{"what": "Client call", "when": "Mon Jun 22"}]},
    "inbox": {"new_items": 0},
}

_STUB_DASHBOARD = {
    "user": "Sam Rivers",
    "whys": [
        "God put you here for a purpose — don't miss it.",
        "You're building wealth for your family.",
    ],
    "goals": {"wealth": ["10x the business in 2026 — the assistant is the engine."], "fitness": []},
    "morning_nudge_domains": ["fitness", "eating"],
}


def test_morning_ritual_speaks_whys_goals_and_briefing():
    out = rituals.format_morning_ritual(_STUB_DATA, "Sam Rivers", dashboard=_STUB_DASHBOARD)
    # Whys verbatim (the anchor — must be recited as set, not paraphrased away).
    assert "God put you here for a purpose — don't miss it." in out
    assert "building wealth for your family" in out
    # Goals named.
    assert "10x the business" in out
    # Real briefing folded in (reused from briefing.build_fact_block).
    assert "70 degrees" in out and "overcast" in out
    # Fitness/eating nudge present.
    assert "fitness and eating" in out
    # Ordering: whys come before the briefing facts.
    assert out.index("purpose") < out.index("70 degrees")


def test_morning_ritual_degrades_without_dashboard():
    # No whys/goals configured -> ritual still builds (won't crash the morning), just
    # falls back to placeholders for the missing sections.
    out = rituals.format_morning_ritual(_STUB_DATA, "Sam Rivers", dashboard={})
    assert "no whys set yet" in out
    assert "no goals set yet" in out
    assert "70 degrees" in out  # briefing still delivered


def test_life_dashboard_template_is_valid():
    # The shipped life_dashboard.json is a TEMPLATE (no personal data committed): valid
    # JSON with the expected shape. Real users supply their own whys/goals via a gitignored
    # file pointed to by EVE_LIFE_DASHBOARD.
    dash = rituals.load_dashboard()
    assert isinstance(dash, dict)
    assert isinstance(dash.get("whys"), list)
    assert isinstance(dash.get("goals"), dict)
    assert isinstance(dash.get("morning_nudge_domains"), list)
