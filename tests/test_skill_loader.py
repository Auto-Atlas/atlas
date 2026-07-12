# tests/test_skill_loader.py
from skill_loader import load_skills, skill_catalog

def test_loads_frontmatter_and_body():
    skills = load_skills("skills")
    assert "get_weather" in skills
    s = skills["get_weather"]
    assert s.risk == "low"
    assert s.requires_confirmation is False
    assert "never invent" in s.catalog
    assert "Never invent weather" in s.body

def test_catalog_is_one_line_per_tool():
    skills = load_skills("skills")
    cat = skill_catalog(skills)
    assert "- get_weather:" in cat

def test_unparseable_frontmatter_fails_safe(tmp_path):
    bad = tmp_path / "bad.md"
    bad.write_text("---\ntool: danger\nrisk: [unclosed\n---\nbody")
    skills = load_skills(str(tmp_path))
    assert skills["danger"].requires_confirmation is True   # fail-safe restrictive
    assert skills["danger"].risk == "high"


def test_persona_prompt_shrinks_and_keeps_gates():
    import persona
    # This assertion guards against RUNAWAY prompt growth — a regression back
    # toward the old ~7,200-char prompt with its ~5,100-char per-tool prose block.
    # It is NOT a tight per-char budget. The original prompt was 7,207 chars; the
    # migration replaced per-tool prose with compact catalog lines. The prompt now
    # also carries an intentionally warmer, anti-repetition persona voice plus a
    # short dynamic OpenJarvis capability hint appended at import. The ceiling has
    # been raised for legitimate tool growth before (3800 -> 4500 -> 5500 -> 6000 ->
    # 6500; complete_reminder landed at 5,539 against the old 5,500 — the previous
    # features had already spent the headroom). The 6000 -> 6500 bump is the four
    # AutoInvoice "books" tools (get_cash_pulse, list_unpaid_invoices, lookup_customer,
    # create_lead) plus look — genuine new-tool catalog lines, tightened to one short
    # clause each. 6,500 still asserts a real shrink from 7,207 (the old per-tool prose
    # block alone, ~5,100 chars, would blow past what the catalog uses) while restoring
    # headroom for several more catalog lines, so contributors adding ONE tool never
    # have to trim existing catalog lines just to fit under a magic number (which has
    # twice degraded catalog quality on unrelated tools). If you ever exceed 6,500, that
    # signals real bloat — investigate first; bump the number only for genuine new-tool
    # catalog lines, never for prose.
    # Same-day merge note: the seven Wealth OS + ResearchOS catalog lines
    # (get_wealth_summary, get_planned_purchases, get_budget_envelope,
    # get_goal_scorecard, start_research, research_status,
    # list_research_decisions) landed alongside the books tools — all genuine
    # new-tool one-liners. Combined they land at 6,596, so the ceiling moves
    # 6,500 -> 6,800: two branch-local ceilings were each honest alone but the
    # merge holds BOTH tool sets. 6,800 keeps ~200 chars of catalog headroom and
    # still asserts a real shrink from the old 7,207-char per-tool prose prompt.
    assert len(persona.SYSTEM_PROMPT) < 6800, (
        f"SYSTEM_PROMPT is {len(persona.SYSTEM_PROMPT)} chars (ceiling 6800). This "
        f"guards against runaway growth back toward the old ~7,200-char prompt, NOT "
        f"a tight budget. If you're over, you've likely reintroduced per-tool prose "
        f"or many heavy catalog lines — trim those, do NOT touch the gate matrix."
    )
    # The always-on obligation gates must survive the migration:
    assert "MUST actually CALL remember" in persona.SYSTEM_PROMPT
    assert "MUST" in persona.SYSTEM_PROMPT and "delegate" in persona.SYSTEM_PROMPT


def test_migration_preserves_every_non_negotiable():
    """No 'never from memory' / 'Use whenever' / 'MUST' rule from persona.py:41-109
    may be silently dropped (Codex). Each must survive either in the gate matrix
    OR a catalog line. 'never from memory' rules (weather/calendar/news) belong in
    catalog lines; obligation rules belong in the gate matrix."""
    import persona
    prompt = persona.SYSTEM_PROMPT.lower()
    # Descriptive non-negotiables that must appear somewhere (catalog lines):
    for tool in ("get_weather", "get_calendar", "check_email", "get_news",
                 "open_on_pc", "set_voice", "start_challenger_mode", "system_report"):
        assert tool in prompt, f"{tool} dropped from the migrated prompt"
    # The 'never answer X from memory' stance must persist for live-data tools:
    assert "never" in prompt and "memory" in prompt
