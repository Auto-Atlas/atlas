"""memory_tool.parse_facts — structured Memory-tab view of the markdown vault."""

from __future__ import annotations

import memory_tool as m


def test_parse_dated_bullet():
    [f] = m.parse_facts(["- [2026-06-22] Owner 2026 goal: 10x the business"])
    assert f["date"] == "2026-06-22"
    assert f["text"].startswith("Owner 2026 goal")
    assert f["category"] == "business"


def test_parse_undated_bullet():
    [f] = m.parse_facts(["- He prefers the male voice"])
    assert f["date"] == "" and f["text"] == "He prefers the male voice"
    assert f["category"] == "preference"


def test_categories():
    cats = {f["text"]: f["category"] for f in m.parse_facts([
        "- Glorify God in the work",
        "- Eats 200g protein a day",
        "- Has a wife and kids",
        "- Runs an automations business",
        "- Random unclassified note about the weather",
    ])}
    assert cats["Glorify God in the work"] == "faith"
    assert cats["Eats 200g protein a day"] == "health"
    assert cats["Has a wife and kids"] == "family"
    assert cats["Runs an automations business"] == "business"
    assert cats["Random unclassified note about the weather"] == "general"


def test_blank_and_non_bullets_skipped():
    assert m.parse_facts(["- ", "  ", "- [2026-01-01] real fact"]) == [
        {"text": "real fact", "date": "2026-01-01", "category": "general"}
    ]
