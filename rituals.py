# rituals.py
#
# Proactive "life rituals" — EVE going from reactive (you tap to talk) to proactive
# (she runs your day). The flagship is the 5 AM morning protocol: she recites your
# WHYS, names your GOALS, folds in the real daily briefing, and nudges fitness/eating.
#
# Same honesty + code-orchestration contract as briefing.py: the facts are fetched by
# us (real, isolated, timeout-bounded) and EVE narrates ONE turn — she does not chain
# tools. The whys/goals come from life_dashboard.json (the user's real anchor), so
# nothing here is placeholder copy: it speaks exactly what he set.
#
import json
import os
from pathlib import Path

from loguru import logger

import briefing


def _dashboard_path() -> Path:
    # Personal file (gitignored, written by onboarding) first; the tracked
    # template only as the neutral fallback shape.
    local = Path(__file__).parent / "life_dashboard.local.json"
    tmpl = Path(__file__).parent / "life_dashboard.json"
    return Path(os.getenv("EVE_LIFE_DASHBOARD", str(local if local.is_file() else tmpl)))


def load_dashboard() -> dict:
    """Load the user's life dashboard (whys + goals). Missing/corrupt -> {} so a ritual
    degrades to a plain briefing rather than crashing the morning."""
    p = _dashboard_path()
    try:
        if p.is_file():
            return json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"rituals: could not read life dashboard ({e}); ritual will skip whys/goals")
    return {}


def _goal_lines(goals: dict) -> list[str]:
    lines: list[str] = []
    for domain, items in (goals or {}).items():
        for item in items or []:
            lines.append(f"- {domain.capitalize()}: {item}")
    return lines


def morning_whys_speech(dashboard: dict | None, user_name: str) -> str:
    """The WHYS as plain spoken text for DIRECT TTS — NOT an LLM instruction.

    A small local model paraphrases "recite these verbatim" down to a greeting
    (observed 2026-06-24: it collapsed them into a one-line greeting). The user's
    reasons are fixed text they set themselves, so we speak them word for word and
    never route them through a model. Returns "" if no whys are set."""
    whys = (load_dashboard() if dashboard is None else dashboard).get("whys") or []
    whys = [w.strip() for w in whys if w and w.strip()]
    if not whys:
        return ""
    body = " ".join(w.rstrip(".") + "." for w in whys)
    return f"{user_name}. Before anything else, here is why you get up. {body}"


def format_morning_ritual(
    data: dict, user_name: str, dashboard: dict | None = None, include_whys: bool = True
) -> str:
    """Build the 5 AM protocol as a single system instruction EVE delivers on the
    morning wake-up connect. Order: whys -> goals -> real briefing -> fitness/eating nudge.

    ``include_whys=False`` is the reliable path: the whys are spoken VERBATIM by
    direct TTS (morning_whys_speech) BEFORE this instruction, so here we tell the
    model they were just said and to pick up at the goals — never re-paraphrasing
    his reasons."""
    dashboard = load_dashboard() if dashboard is None else dashboard
    whys = dashboard.get("whys") or []
    goals = dashboard.get("goals") or {}
    nudge_domains = dashboard.get("morning_nudge_domains") or ["fitness", "eating"]

    fact_block = briefing.build_fact_block(data)
    whys_block = "\n".join(f"- {w}" for w in whys) if whys else "- (no whys set yet)"
    goal_block = "\n".join(_goal_lines(goals)) or "- (no goals set yet)"
    nudge = " and ".join(nudge_domains)

    if include_whys:
        whys_section = (
            f"1) {user_name}'s WHYS first — say these close to verbatim, with conviction. This is "
            f"why they get up:\n{whys_block}\n\n"
        )
    else:
        whys_section = (
            f"{user_name}'s WHYS were JUST spoken to them, verbatim — do NOT repeat or paraphrase "
            "them. Pick up right after, with the same weight:\n\n"
        )

    return (
        f"It is the 5 AM morning wake-up for {user_name}. Deliver their ritual now — like a trusted "
        f"chief of staff who cares about the whole person, NOT a list-reader. Speak it out loud in "
        f"this order, naturally and with weight, in short spoken sentences:\n\n"
        f"{whys_section}"
        f"2) {user_name}'s GOALS for this season — name them briefly so they're front of mind "
        f"today:\n{goal_block}\n\n"
        f"3) Today's REAL briefing (already fetched for you — do NOT call any tools, just fold in "
        f"what actually matters, skip anything empty or unavailable):\n{fact_block}\n\n"
        f"4) Close with a short, direct nudge on {nudge} for today, then ask what they want to take "
        f"on first. Keep the whole thing tight and inspiring — a charge for the day, not a lecture."
    )


def build_full_brief_speech(
    data: dict, user_name: str, dashboard: dict | None = None
) -> str:
    """The ENTIRE morning brief as ONE spoken monologue string — whys (verbatim) +
    goals + today's real briefing + a nudge.

    Spoken as a single continuous TTS block with the mic hard-muted (no model
    narration turn), so on speakerphone the mic never reopens mid-delivery and her
    own voice can't echo into a self-reply loop. Less live-phrased than the model
    narration, but bulletproof on a speaker."""
    dashboard = load_dashboard() if dashboard is None else dashboard
    out: list[str] = []

    whys = morning_whys_speech(dashboard, user_name)
    if whys:
        out.append(whys)

    goal_lines = []
    for _domain, items in (dashboard.get("goals") or {}).items():
        for it in items or []:
            if it and it.strip():
                goal_lines.append(it.strip().rstrip("."))
    if goal_lines:
        out.append("Keep your goals front of mind today. " + " ".join(g + "." for g in goal_lines))

    facts = briefing.build_fact_block(data)
    fact_text = " ".join(
        (line.strip()[2:].strip() if line.strip().startswith("- ") else line.strip())
        for line in facts.splitlines()
        if line.strip()
    )
    if fact_text:
        out.append("Here is your morning. " + fact_text)

    nudge = " and ".join(dashboard.get("morning_nudge_domains") or ["fitness", "eating"])
    out.append(f"Stay locked in on your {nudge} today. What do you want to take on first?")

    return " ".join(out)


def wake_text(dashboard: dict | None = None, user_nick: str = "") -> str:
    """The gentle 5 AM wake, spoken LOCALLY on the phone (Android TTS) so it needs no
    voice connection: the verbatim whys + a rise nudge. Multi-tenant: name/whys come
    from the tenant's dashboard config, not hardcoded. '' if no whys are set."""
    dash = load_dashboard() if dashboard is None else dashboard
    nick = (user_nick or "").strip() or ((dash.get("user") or "").split() or ["there"])[0]
    whys = morning_whys_speech(dash, nick)
    return (whys + " It's a new day. Rise up — it's yours.") if whys else ""


def build_strategy_task(
    data: dict, user_name: str, dashboard: dict | None = None, knowledge: str = ""
) -> str:
    """The task handed to a CAPABLE agent (Hermes/codex via agent_bridge) for the
    proactive morning strategist: given his real goals + today's real context, the
    1-3 highest-leverage CONCRETE moves to make TODAY. This is a reasoning task the
    small local voice model can't do — so it's delegated. Speech-shaped output."""
    dashboard = load_dashboard() if dashboard is None else dashboard
    goals = dashboard.get("goals") or {}
    goal_block = "\n".join(_goal_lines(goals)) or "- (no goals set yet)"
    fact_block = briefing.build_fact_block(data)
    import priorities as _priorities

    prio = _priorities.load()
    icp = prio.get("icp") or ""
    intent = prio.get("high_intent_signals") or ""
    icp_block = ""
    if icp or intent:
        icp_block = (
            f"{user_name}'s IDEAL clients (target these specifically, not generic businesses):\n"
            f"{icp}\nHigh-intent signals that mark a real lead: {intent}\n\n"
        )
    knowledge_block = ""
    if knowledge and knowledge.strip():
        knowledge_block = (
            f"From {user_name}'s OWN knowledge base (their wiki + notes — build on what they've "
            f"already figured out, cite it):\n{knowledge.strip()}\n\n"
        )
    # Optional, user-supplied business descriptor from the life dashboard. Absent -> the
    # strategist grounds purely in the user's goals, with no business name assumed.
    business = (dashboard.get("business") or "").strip()
    who = f"{user_name}, who runs {business}" if business else user_name
    return (
        f"You are the sharp chief-of-staff strategist for {who}. Their goals this season:\n"
        f"{goal_block}\n\n"
        f"{icp_block}"
        f"{knowledge_block}"
        f"Today's REAL context:\n{fact_block}\n\n"
        f"Give them the 1 to 3 HIGHEST-leverage, concrete moves to make TODAY toward those goals. "
        f"Specific actions they can actually execute today, with a real first step each — never "
        f"platitudes or generic advice. This is SPOKEN ALOUD: write it as natural speech a trusted "
        f"right hand would say — no markdown, no bullet symbols, no digits (spell numbers out), "
        f"under 90 words total."
    )


def extract_action_items(strategy: str) -> list[str]:
    """Best-effort split of the strategy prose into discrete, checkable moves.

    The strategist emits ordinal cues ("First, … Second, … Third, …"); split on
    those and trim each to a tight one-liner for a checkbox. Falls back to []."""
    import re

    if not strategy or not strategy.strip():
        return []
    parts = re.split(
        r"(?i)\b(?:first|second|third|fourth|fifth|sixth|finally|lastly)[,:]\s+",
        strategy.strip(),
    )
    items: list[str] = []
    for chunk in parts[1:]:  # parts[0] is the intro before the first ordinal
        chunk = chunk.strip()
        if not chunk:
            continue
        first_sentence = chunk.split(". ")[0].strip().rstrip(".")
        if len(first_sentence) >= 8:
            items.append(first_sentence)
    return items[:6]


def latest_strategy_text() -> str:
    """The most recent strategy block saved TODAY (or '' if none yet)."""
    from datetime import datetime

    d = Path(os.getenv("EVE_STRATEGY_DIR", str(Path(__file__).parent / "strategy")))
    f = d / f"{datetime.now():%Y-%m-%d}.md"
    if not f.is_file():
        return ""
    text = f.read_text(encoding="utf-8")
    # entries are appended under "## HH:MM — strategy …"; take the last block's body
    blocks = text.split("\n## ")
    body = blocks[-1]
    # drop the header line of that block
    return body.split("\n", 1)[1].strip() if "\n" in body else ""


def today_payload(user_name: str | None = None) -> dict:
    """Everything the phone 'Today' tab renders: whys, goals, today's strategy,
    and the strategy parsed into checkable action items. Pure read — safe to call
    from the API. Degrades gracefully (empty lists) if data is missing."""
    from datetime import datetime

    dash = load_dashboard()
    strategy = latest_strategy_text()
    return {
        "date": f"{datetime.now():%Y-%m-%d}",
        "user": user_name or dash.get("user") or os.getenv("JARVIS_USER_NICK", ""),
        "whys": dash.get("whys") or [],
        "goals": dash.get("goals") or {},
        "strategy": strategy,
        "action_items": extract_action_items(strategy),
    }


def save_strategy(plan: str, user_name: str) -> "Path":
    """Append the full strategist output to a dated markdown file so the deep
    version is kept for reference even though EVE only speaks the concise gist.
    Best-effort: returns the path, or raises only on a genuine write failure."""
    from datetime import datetime

    d = Path(os.getenv("EVE_STRATEGY_DIR", str(Path(__file__).parent / "strategy")))
    d.mkdir(parents=True, exist_ok=True)
    now = datetime.now()
    f = d / f"{now:%Y-%m-%d}.md"
    with f.open("a", encoding="utf-8") as fh:
        fh.write(f"\n## {now:%H:%M} — strategy for {user_name}\n\n{plan.strip()}\n")
    # Capture-back: also file it into the wiki so it becomes searchable knowledge on
    # the next ingest — closing the loop (read your wiki -> act -> write back).
    try:
        wd = Path(os.getenv(
            "EVE_WIKI_DIR", str(Path.home() / "ClaudeWiki" / "llm-wiki" / "wiki" / "pages")
        ))
        if wd.is_dir():
            wf = wd / f"eve-strategy-{now:%Y-%m-%d}.md"
            head = "" if wf.exists() else (
                f"---\ntype: strategy\nname: EVE daily strategy {now:%Y-%m-%d}\n"
                f"category: business-ops\nlast_updated: {now:%Y-%m-%d}\n---\n\n"
                f"# EVE daily strategy — {now:%Y-%m-%d}\n\n"
            )
            with wf.open("a", encoding="utf-8") as fh:
                fh.write(f"{head}## {now:%H:%M}\n\n{plan.strip()}\n\n")
    except Exception as e:
        logger.debug(f"wiki capture-back skipped: {e}")
    return f


if __name__ == "__main__":
    import asyncio

    async def _demo():
        data = await briefing.gather_briefing()
        nick = os.getenv("JARVIS_USER_NICK") or os.getenv("JARVIS_USER_NAME", "the user")
        print(format_morning_ritual(data, nick))
        print("\n--- STRATEGY TASK ---\n")
        print(build_strategy_task(data, nick))

    asyncio.run(_demo())
