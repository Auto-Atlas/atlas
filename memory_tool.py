#
# Persistent memory — Jarvis finally remembers between restarts. Facts live
# as dated bullets in the LLM wiki (the owner's long-term knowledge layer), so
# every Claude session can read them too, and a memory pack of the newest
# entries is injected into the voice context at every boot.
#
#   remember(fact)   append one dated bullet to the wiki page
#   recall(query)    search the whole memory page (boot pack only holds the
#                    newest entries; recall reaches everything ever saved)
#
# Storage is plain markdown — human-editable, git-tracked with the wiki.
#

import os
import re
from datetime import datetime
from pathlib import Path

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

from persona import ASSISTANT_NAME, USER_NAME

import speaker_state

MEMORY_PAGE = Path(
    os.getenv("JARVIS_MEMORY_PAGE", str(Path.home() / "jarvis-memory.md"))
)


def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "guest"


def _page_for(name, tier) -> Path:
    """Owner -> the main page (the owner's facts, also the boot pack). Known/kid ->
    a per-speaker page, so recall is symmetrically isolated and a family member's
    facts never surface in the owner's recall (or vice-versa)."""
    if tier == "owner" or name is None:
        return MEMORY_PAGE
    return MEMORY_PAGE.parent / f"eve-memory-{_slug(name)}.md"

_HEADER = (
    f"# {ASSISTANT_NAME} memory\n\n"
    f"Long-term memory of the {ASSISTANT_NAME} voice assistant. One dated bullet per "
    "fact, appended by the `remember` voice tool; the newest entries are "
    "injected into the voice loop at every boot. Safe to edit by hand.\n\n"
)

REMEMBER_SCHEMA = FunctionSchema(
    name="remember",
    description=(
        "CALL THIS TOOL to save a fact to permanent memory. Without this tool call, "
        "you will forget everything when you restart — your conversation memory is "
        "temporary. You MUST call this whenever the user shares: a name (family, "
        "friends, pets), a birthday, a goal, a preference, a commitment, a deadline, "
        "or says 'remember this'. Do NOT say 'I will remember that' — CALL THIS TOOL."
    ),
    properties={
        "fact": {
            "type": "string",
            "description": f"The fact, stated compactly in third person, e.g. 'Mom's birthday is July 9' or '{USER_NAME}'s Q3 goal: 10 new automation clients'.",
        }
    },
    required=["fact"],
)

RECALL_SCHEMA = FunctionSchema(
    name="recall",
    description=(
        "Search permanent memory for facts matching a topic. Use when asked 'what do you "
        "remember about X', 'when is Y', or before answering anything that might have been "
        "told to you in a past session."
    ),
    properties={
        "query": {"type": "string", "description": "Topic or keyword, e.g. 'birthday', 'goals'. Empty returns the newest facts."}
    },
    required=[],
)


def _entries(page: Path | None = None) -> list[str]:
    page = page or MEMORY_PAGE
    if not page.is_file():
        return []
    return [
        line.strip()
        for line in page.read_text(encoding="utf-8").splitlines()
        if line.strip().startswith("- ")
    ]


_FACT_RE = re.compile(r"^-\s*(?:\[(\d{4}-\d{2}-\d{2})\]\s*)?(.*)$")
# Lightweight category inference (keyword-based) — gives the Memory tab structure
# without imposing schema on the hand-editable markdown vault.
_CATEGORIES = (
    ("faith", ("god", "faith", "church", "pray", "steward", "glorify", "purpose")),
    ("health", ("protein", "calorie", "calories", "eating", "meal", "fitness", "workout",
                "gym", "weight", "sleep", "diet")),
    ("family", ("wife", "kid", "kids", "son", "daughter", "family", "married", "child")),
    ("business", ("automation", "automations", "revenue", "client", "clients",
                  "lead", "leads", "sales", "roofer", "contractor", "hvac", "10x", "business")),
    ("goal", ("goal", "goals", "wants to", "aiming", "target", "this year")),
    ("preference", ("prefers", "likes", "hates", "doesn't like", "favorite")),
)


def _category(text: str) -> str:
    t = text.lower()
    for cat, kws in _CATEGORIES:
        if any(re.search(r"\b" + re.escape(k) + r"\b", t) for k in kws):
            return cat
    return "general"


def parse_facts(entries: list[str]) -> list[dict]:
    """Turn raw '- [YYYY-MM-DD] text' bullets into {text, date, category} — the
    structured view the phone Memory tab renders. Undated bullets get date=''."""
    out: list[dict] = []
    for raw in entries:
        m = _FACT_RE.match(raw.strip())
        date = (m.group(1) or "") if m else ""
        text = (m.group(2).strip() if m else raw.strip().lstrip("- ").strip())
        if text:
            out.append({"text": text, "date": date, "category": _category(text)})
    return out


def memory_pack(max_entries: int = 30) -> str:
    """The newest facts, formatted for boot injection. Empty string if none."""
    entries = _entries()[-max_entries:]
    if not entries:
        return ""
    return f"Facts you remember about {USER_NAME} from past sessions:\n" + "\n".join(entries)


async def handle_remember(params: FunctionCallParams):
    fact = str(params.arguments.get("fact", "")).strip()
    if not fact:
        await params.result_callback({"ok": False, "error": "nothing to remember"})
        return
    page = _page_for(speaker_state.current_speaker(), speaker_state.current_tier())
    try:
        page.parent.mkdir(parents=True, exist_ok=True)
        if not page.is_file():
            page.write_text(_HEADER, encoding="utf-8")
        with open(page, "a", encoding="utf-8") as f:
            f.write(f"- [{datetime.now():%Y-%m-%d}] {fact}\n")
    except Exception as e:
        await params.result_callback({"ok": False, "error": f"could not write memory: {e}"})
        return
    logger.info(f"remembered: {fact!r}")
    await params.result_callback(
        {"ok": True, "remembered": fact, "instruction": "Confirm in a few words; don't repeat the whole fact back robotically."}
    )


async def handle_recall(params: FunctionCallParams):
    query = str(params.arguments.get("query", "") or "").strip().lower()
    entries = _entries(_page_for(speaker_state.current_speaker(), speaker_state.current_tier()))
    if not entries:
        await params.result_callback({"ok": True, "matches": [], "note": "memory is empty so far"})
        return
    if query:
        words = [w for w in re.split(r"\W+", query) if len(w) > 2]
        matches = [e for e in entries if any(w in e.lower() for w in words)]
        if not matches:
            # A miss must SAY it's a miss. Returning the newest facts under
            # "matches" had the model answering unrelated questions from them.
            await params.result_callback(
                {
                    "ok": True,
                    "matches": [],
                    "note": (
                        "nothing in memory matched that query — tell the user so "
                        "plainly. The newest saved facts follow only as context; "
                        "do not present them as the answer."
                    ),
                    "recent_unrelated_facts": entries[-5:],
                }
            )
            return
    else:
        matches = entries[-10:]
    await params.result_callback({"ok": True, "matches": matches[-15:]})
