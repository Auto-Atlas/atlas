#
# search_history — lets Jarvis recall ANY past conversation across every surface
# (this desktop voice loop, the phone, and the OpenJarvis typed chats) by querying
# the central conversation archive (conversation_archive.py / ~/.openjarvis/history.db).
# This is the retrieval half of the "second brain": the assistant can look up what
# was actually said before instead of guessing, and learn from it.
#
# The archive is refreshed from the voice transcripts on each call (idempotent, the
# files are small), so a search always reflects the latest turns.
#

import asyncio

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

import conversation_archive as archive

# Map the loose words the LLM might pass to canonical archive source tags.
_SOURCE_ALIASES = {
    "phone": "phone-voice",
    "phone-voice": "phone-voice",
    "desktop": "desktop-voice",
    "desktop-voice": "desktop-voice",
    "voice": "desktop-voice",
    "typed": "typed-chat",
    "typed-chat": "typed-chat",
    "chat": "typed-chat",
    "text": "typed-chat",
}
_MAX_MATCHES = 8

SEARCH_HISTORY_SCHEMA = FunctionSchema(
    name="search_history",
    description=(
        "Search ALL saved past conversations across every surface — this desktop voice "
        "loop, the phone, and typed chats — and return the most relevant ones with when "
        "they happened, which surface, the title, and a matching snippet. Use whenever the "
        "user refers to something from before ('what did we say about the pool app', "
        "'remember when', 'last time we talked about'), or to review how a past "
        "conversation went. Returns REAL saved conversations, never invented ones."
    ),
    properties={
        "query": {
            "type": "string",
            "description": "What to look for, e.g. 'pool app', 'the beach plan', 'set voice'.",
        },
        "source": {
            "type": "string",
            "description": (
                "Optional surface filter: 'phone', 'desktop', or 'typed'. Omit to search all."
            ),
        },
    },
    required=["query"],
)


def _fmt_when(ms: int) -> str:
    from datetime import datetime

    try:
        return datetime.fromtimestamp(ms / 1000).strftime("%b %d, %-I:%M %p")
    except (ValueError, OSError):
        # %-I is platform-specific; fall back to a portable format.
        try:
            return datetime.fromtimestamp(ms / 1000).strftime("%b %d, %H:%M")
        except Exception:
            return "unknown time"


def _run_search(query: str, source: str | None) -> dict:
    """Synchronous DB work (connect + refresh + search), run off the voice loop."""
    conn = archive.connect()
    try:
        archive.ingest_transcripts(conn)  # keep the archive current; idempotent
    except Exception as exc:  # a transcript hiccup must not break recall
        logger.debug(f"search_history: ingest skipped: {exc}")
    hits = archive.search(conn, query, limit=_MAX_MATCHES)
    if source:
        hits = [h for h in hits if h.get("source") == source]
    matches = [
        {
            "when": _fmt_when(h.get("started_at", 0)),
            "surface": h.get("source", ""),
            "title": h.get("title", ""),
            "snippet": (h.get("snippet") or "").replace("\n", " ").strip(),
        }
        for h in hits
    ]
    conn.close()
    return matches


async def handle_search_history(params: FunctionCallParams):
    query = str(params.arguments.get("query", "") or "").strip()
    raw_source = str(params.arguments.get("source", "") or "").strip().lower()
    source = _SOURCE_ALIASES.get(raw_source) if raw_source else None

    if not query:
        await params.result_callback(
            {"ok": False, "error": "give me something to search for (a word or phrase)."}
        )
        return

    try:
        matches = await asyncio.to_thread(_run_search, query, source)
    except Exception as exc:
        logger.warning(f"search_history({query!r}) failed: {exc}")
        await params.result_callback({"ok": False, "error": f"could not search history: {exc}"})
        return

    logger.info(f"search_history(query={query!r}, source={source}) -> {len(matches)} match(es)")
    if not matches:
        await params.result_callback(
            {"ok": True, "query": query, "count": 0,
             "note": "no past conversation mentions that."}
        )
        return
    await params.result_callback(
        {"ok": True, "query": query, "count": len(matches), "matches": matches}
    )
