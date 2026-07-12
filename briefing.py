# briefing.py
#
# Code-orchestrated morning briefing. Instead of asking the local model to CHAIN
# four tool calls on connect (slow, unreliable on an 8B model, and it blew past
# the app's connect timeout), we fetch the real data ourselves — concurrently,
# each source isolated with its own timeout + try/except — then hand EVE ONE
# compact fact block to narrate in a single fast turn.
#
# Honesty contract (same as the rest of the stack): a source that fails is
# reported as "unavailable", never invented. Nothing here fabricates data.
#
import asyncio

from loguru import logger

_SOURCE_TIMEOUT_S = float(__import__("os").getenv("JARVIS_BRIEFING_SOURCE_TIMEOUT", "12"))


async def _safe(coro, label: str):
    """Await a source with a hard timeout; any failure becomes {'error': ...} so a
    single dead source never sinks the whole briefing."""
    try:
        return await asyncio.wait_for(coro, timeout=_SOURCE_TIMEOUT_S)
    except Exception as e:
        logger.warning(f"briefing: {label} unavailable ({e})")
        return {"error": str(e) or type(e).__name__}


async def _weather() -> dict:
    import weather_tool

    return await weather_tool.fetch_weather()


async def _email(limit: int) -> dict:
    import email_tool

    if not (email_tool.GMAIL_USER and email_tool.GMAIL_APP_PASSWORD):
        return {"error": "not configured"}
    msgs = await asyncio.to_thread(email_tool._fetch_unread, limit)
    return {"count": len(msgs), "unread": msgs}


async def _inbox() -> dict:
    import inbox_tool

    return await asyncio.to_thread(inbox_tool.check_inbox)


async def _calendar(days: int = 1) -> dict:
    import aiohttp
    import calendar_tool
    from datetime import datetime, timedelta

    if not getattr(calendar_tool, "ICS_URL", ""):
        return {"error": "not connected"}
    timeout = aiohttp.ClientTimeout(total=_SOURCE_TIMEOUT_S)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        async with session.get(calendar_tool.ICS_URL) as resp:
            if resp.status != 200:
                raise RuntimeError(f"calendar feed HTTP {resp.status}")
            ics = await resp.text()
    start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    events = calendar_tool.parse_ics_events(ics, start, start + timedelta(days=days))
    return {"count": len(events), "events": events}


async def gather_briefing(email_limit: int = 5) -> dict:
    """Fetch weather + email + calendar + inbox concurrently. Each value is the
    source's real payload, or {'error': reason} if it failed."""
    weather, mail, calendar, inbox = await asyncio.gather(
        _safe(_weather(), "weather"),
        _safe(_email(email_limit), "email"),
        _safe(_calendar(), "calendar"),
        _safe(_inbox(), "inbox"),
    )
    return {"weather": weather, "email": mail, "calendar": calendar, "inbox": inbox}


def _event_label(e: dict) -> str:
    title = e.get("what") or e.get("summary") or "event"
    when = e.get("when") or ""
    return f"{title}" + (f" ({when})" if when else "")


def build_fact_block(data: dict) -> str:
    """Render the fetched briefing data into a compact, human-narratable fact list
    (one '- ' line per source, failures shown as 'unavailable', never invented).
    Shared by the daily briefing and the morning ritual so both narrate identical facts."""
    facts: list[str] = []

    w = data.get("weather") or {}
    if "error" not in w:
        now = w.get("now", {})
        today = w.get("today", {})
        facts.append(
            f"Weather ({w.get('place', 'home')}): now {now.get('temp_f')} degrees, "
            f"{now.get('conditions')}; today's high {today.get('high_f')}, low {today.get('low_f')}, "
            f"{today.get('precip_chance_pct')} percent chance of precipitation."
        )
    else:
        facts.append("Weather: unavailable.")

    em = data.get("email") or {}
    if "error" not in em:
        subs = "; ".join(
            f"{m.get('from', '?')}: {m.get('subject', '')}" for m in (em.get("unread") or [])[:5]
        )
        facts.append(f"Email: {em.get('count', 0)} unread." + (f" Top: {subs}" if subs else ""))
    else:
        facts.append("Email: unavailable.")

    cal = data.get("calendar") or {}
    if "error" not in cal:
        evs = cal.get("events") or []
        facts.append(
            "Calendar today: " + ("; ".join(_event_label(e) for e in evs[:5]) if evs else "clear.")
        )
    else:
        facts.append("Calendar: unavailable.")

    ib = data.get("inbox") or {}
    if "error" not in ib:
        n = ib.get("new_items")
        if n is None:
            items = ib.get("items") or ib.get("notes") or []
            n = len(items) if isinstance(items, list) else 0
        facts.append(f"Inbox: {n} new capture(s)." if n else "Inbox: nothing new.")

    return "\n".join(f"- {f}" for f in facts)


def format_briefing(data: dict, user_name: str) -> str:
    """Turn the fetched data into a system instruction EVE narrates in ONE turn.
    The facts are already real + fetched, so she summarizes — she does NOT call tools."""
    fact_block = build_fact_block(data)
    return (
        f"{user_name} just connected — his first time today. Here is his REAL morning data "
        f"(already fetched for you — do NOT call any tools, just summarize it):\n{fact_block}\n\n"
        "Give a SHORT spoken rundown — two or three natural sentences, only what actually matters "
        "(skip anything unavailable or empty) — then ask if he'd like you to handle any of it. "
        "Do not read it back verbatim or list everything. If he says yes to something, draft it and "
        "bring it back for his approval before doing anything."
    )
