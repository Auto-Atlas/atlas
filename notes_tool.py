#
# Notes recall — "read me my notes from Tuesday" / "what did I note about the
# Henderson job". Searches the real capture inbox (phone-synced markdown and
# text files, including the SMS 'jarvis note' channel) by keyword and/or age.
# Read-only; returns filename, date, and content so the model narrates facts.
#

import asyncio
import os
import re
from datetime import datetime, timedelta
from pathlib import Path

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

INBOX_DIR = Path(os.getenv("JARVIS_INBOX_DIR", str(Path.home() / "jarvis-inbox")))

SEARCH_NOTES_SCHEMA = FunctionSchema(
    name="search_notes",
    description=(
        "Search the user's saved notes (phone captures + 'jarvis note' texts) by keyword "
        "and/or how recent. Use for 'what were my notes about X', 'read me yesterday's "
        "notes', 'did I write anything down about the quote'."
    ),
    properties={
        "query": {"type": "string", "description": "Keyword(s) to find, e.g. 'roofing quote'. Empty = all recent notes."},
        "days_back": {"type": "number", "description": "How many days back to search. Default 7."},
    },
    required=[],
)


def _search_notes(query: str, days: float) -> list[dict]:
    """Blocking scan — runs in a thread so the voice loop never stalls.
    Recursive (rglob) to match check_inbox: a note synced into a subfolder
    used to show in the inbox but be invisible to search."""
    cutoff = datetime.now() - timedelta(days=days)
    words = [w for w in re.split(r"\W+", query) if len(w) > 2]

    candidates = []
    for p in INBOX_DIR.rglob("*"):
        try:
            if p.suffix.lower() not in (".md", ".txt") or p.name == "contacts.csv":
                continue
            rel = p.relative_to(INBOX_DIR)
            if any(part.startswith(".") for part in rel.parts):
                continue
            candidates.append((p, p.stat().st_mtime))
        except OSError:
            continue  # deleted/locked mid-scan
    candidates.sort(key=lambda x: x[1], reverse=True)

    hits = []
    for p, mtime_s in candidates:
        mtime = datetime.fromtimestamp(mtime_s)
        if mtime < cutoff:
            break
        try:
            # Capped read: notes are short; never slurp a huge stray file.
            with open(p, encoding="utf-8-sig", errors="replace") as f:
                text = f.read(4000).strip()
        except OSError:
            continue
        if words and not any(w in text.lower() or w in p.name.lower() for w in words):
            continue
        hits.append({"file": p.name, "when": f"{mtime:%a %b %d %I:%M %p}", "text": text[:400]})
        if len(hits) >= 8:
            break
    return hits


async def handle_search_notes(params: FunctionCallParams):
    query = str(params.arguments.get("query", "") or "").strip().lower()
    try:
        days = float(params.arguments.get("days_back") or 7)
    except Exception:
        days = 7
    if not INBOX_DIR.is_dir():
        await params.result_callback({"ok": False, "error": f"inbox folder missing at {INBOX_DIR}"})
        return

    hits = await asyncio.to_thread(_search_notes, query, days)
    logger.info(f"search_notes(q={query!r}, days={days}) -> {len(hits)} hits")
    await params.result_callback(
        {
            "ok": True,
            "count": len(hits),
            "notes": hits,
            "instruction": "Read the relevant note text naturally; say when it was written. If none matched, say so plainly.",
        }
    )
