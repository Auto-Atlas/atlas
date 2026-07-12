# calendar_watch.py
#
# Proactive calendar surfacing (2026-07-02, owner-approved): EVE watches the same read-only
# Google-Calendar ICS feed get_calendar uses and, WITHOUT any cron subsystem (this is one more
# background task in the body's loop, like reminders and the agent poller):
#   - EVENT REMINDERS  — ~EVE_CAL_LEAD_MIN (15) minutes before each timed event
#   - MORNING LOOK-AHEAD — once a day at EVE_CAL_MORNING (08:00): today's schedule
#   - EVENING LOOK-AHEAD — once a day at EVE_CAL_EVENING (20:00): tomorrow's schedule
#
# Delivery mirrors agent_delivery's channel logic for non-row content: spoken when a session is
# live (quiet hours push instead), push-notified (ntfy -> Telegram) when away, always broadcast
# to the app feed. Calendar text is the user's own data but still enters the LLM as
# report-only content. A missed tick just doesn't repeat — the calendar remains queryable.
#
# Import invariant: no tool_policy/jarvis_core/bot/phone_bot (body-agnostic; bot.py wires it).
#
import os
from datetime import datetime, timedelta

import aiohttp
from loguru import logger

import approval_push
import delivery_policy
import try_announce
from calendar_tool import parse_ics_events

_TIMEOUT = aiohttp.ClientTimeout(total=15)


def enabled() -> bool:
    return bool(os.getenv("JARVIS_CALENDAR_ICS_URL")) and os.getenv("EVE_CAL_WATCH", "1") == "1"


def _lead_min() -> int:
    return int(float(os.getenv("EVE_CAL_LEAD_MIN", "15")))


def _fire_time(env, default):
    raw = os.getenv(env, default)
    try:
        h, m = raw.split(":")
        return int(h) % 24, int(m) % 60
    except Exception:
        h, m = default.split(":")
        return int(h), int(m)


async def fetch_events(days=2, *, ics_text=None, now=None):
    """Events (parse_ics_events dicts incl. starts_at iso) from now's midnight for `days`.
    Tests inject ics_text; live fetches the secret ICS URL (never echoed in errors)."""
    url = os.getenv("JARVIS_CALENDAR_ICS_URL", "")
    if ics_text is None:
        if not url:
            return []
        async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
            async with session.get(url) as resp:
                if resp.status != 200:
                    raise RuntimeError(f"calendar feed returned HTTP {resp.status}")
                ics_text = await resp.text()
    # Anchor the window on the CALLER's clock when given — every caller already
    # holds a `now` (tick, initiative). A bare datetime.now() here made every
    # fixed-date test go stale at midnight (found 2026-07-03).
    start = (now or datetime.now()).replace(hour=0, minute=0, second=0, microsecond=0)
    return parse_ics_events(ics_text, start, start + timedelta(days=days))


def due_reminders(events, now, lead_min, announced):
    """Timed events starting within (now, now+lead] not yet announced. Pure. The announced
    set is keyed (what, starts_at) so a recurring series reminds per-occurrence."""
    due = []
    horizon = now + timedelta(minutes=lead_min)
    for ev in events:
        if ev.get("all_day") or not ev.get("starts_at"):
            continue
        try:
            start = datetime.fromisoformat(ev["starts_at"])
        except ValueError:
            continue
        key = (ev.get("what"), ev["starts_at"])
        if key in announced:
            continue
        if now < start <= horizon:
            due.append(ev)
    return due


def events_on(events, day):
    """Events whose start date == day (a datetime.date). Pure."""
    out = []
    for ev in events:
        if not ev.get("starts_at"):
            continue
        try:
            if datetime.fromisoformat(ev["starts_at"]).date() == day:
                out.append(ev)
        except ValueError:
            continue
    return out


def reminder_instruction(ev, lead_min):
    return (
        f"A calendar event is coming up in about {lead_min} minutes. In ONE short, natural "
        "sentence, remind the user — name the event and its time. Report only.\n"
        f"EVENT: {ev.get('what', '')} at {ev.get('when', '')}"
    )


def _brief(evs):
    return "; ".join(f"{e.get('what', '')} ({e.get('when', '')})" for e in evs[:8])


def morning_instruction(evs):
    if not evs:
        return ("Give the user a one-sentence morning calendar note: today is completely "
                "clear — nothing scheduled.")
    return (
        f"Morning look-ahead: the user has {len(evs)} thing(s) on today's calendar. In one or "
        "two short, natural sentences, walk them through it in time order. Report only.\n"
        f"TODAY: {_brief(evs)}"
    )


def evening_instruction(evs):
    if not evs:
        return ("Give the user a one-sentence evening note: tomorrow's calendar is clear.")
    return (
        f"Evening look-ahead: tomorrow the user has {len(evs)} thing(s) scheduled. In one or "
        "two short, natural sentences, preview it so they can prep tonight. Report only.\n"
        f"TOMORROW: {_brief(evs)}"
    )


def _headline(prefix, evs_or_ev):
    if isinstance(evs_or_ev, dict):
        return f"{prefix}: {evs_or_ev.get('what', '')} at {evs_or_ev.get('when', '')}"[:180]
    return f"{prefix}: {_brief(evs_or_ev) or 'clear'}"[:180]


class WatchState:
    """In-memory per-process state: per-occurrence reminder dedupe + once-a-day brief keys.
    A restart may re-remind an event still inside the lead window — acceptable."""

    def __init__(self):
        self.announced = set()      # (what, starts_at)
        self.fired = set()          # "morning:2026-07-02" / "evening:2026-07-02"


async def _deliver(kind, instruction, headline, *, announce, broadcast, is_alive, body):
    # Channel logic mirrors agent_delivery for non-row content: quiet -> push;
    # live -> speak; dead -> push; broadcast always. No replay (a calendar stays queryable).
    try:
        res = broadcast({"type": kind, "text": body[:500]})
        import inspect as _inspect
        if _inspect.isawaitable(res):
            import asyncio as _asyncio
            _asyncio.ensure_future(res)
    except Exception:
        pass
    quiet = delivery_policy.in_quiet_hours()
    alive = is_alive() if is_alive is not None else True
    if not quiet and alive:
        st = await try_announce.deliver(announce, instruction, cid=None, is_alive=is_alive)
        if st == try_announce.SPOKEN:
            return "spoken"
    try:
        from persona import ASSISTANT_NAME  # configured self-name; EVE is a product
        res = await approval_push.notify(headline, "calendar", title=f"{ASSISTANT_NAME} — calendar")
        return "notified" if (res.get("ntfy") or res.get("telegram")) else "queued"
    except Exception as e:
        logger.debug(f"calendar notify failed: {e!r}")
        return "queued"


async def tick(state, *, announce, broadcast, is_alive, now=None, ics_text=None):
    """One watcher pass. Returns a list of ('reminder'|'morning'|'evening', status) it
    delivered — empty on a quiet tick. Fetch errors log and skip (never crash the loop)."""
    if not enabled() and ics_text is None:
        return []
    now = now or datetime.now()
    delivered = []
    try:
        events = await fetch_events(days=2, ics_text=ics_text, now=now)
    except Exception as e:
        msg = str(e).replace(os.getenv("JARVIS_CALENDAR_ICS_URL", "") or "\x00", "<calendar url>")
        logger.warning(f"calendar watch fetch failed: {msg}")
        return []

    lead = _lead_min()
    for ev in due_reminders(events, now, lead, state.announced):
        state.announced.add((ev.get("what"), ev["starts_at"]))
        st = await _deliver("calendar_event", reminder_instruction(ev, lead),
                            _headline("Coming up", ev), announce=announce,
                            broadcast=broadcast, is_alive=is_alive,
                            body=f"{ev.get('what', '')} at {ev.get('when', '')}")
        delivered.append(("reminder", st))

    # Daily briefs fire once, inside a 1-hour window after their set time (a boot later in
    # the day must not blurt a stale morning brief at 3pm).
    for name, env, default, day, builder in (
            ("morning", "EVE_CAL_MORNING", "08:00", now.date(), morning_instruction),
            ("evening", "EVE_CAL_EVENING", "20:00", now.date() + timedelta(days=1),
             evening_instruction)):
        h, m = _fire_time(env, default)
        fire_at = now.replace(hour=h, minute=m, second=0, microsecond=0)
        key = f"{name}:{now.date().isoformat()}"
        if key in state.fired or not (fire_at <= now < fire_at + timedelta(hours=1)):
            continue
        state.fired.add(key)
        evs = events_on(events, day)
        st = await _deliver("calendar_brief", builder(evs),
                            _headline("Today" if name == "morning" else "Tomorrow", evs),
                            announce=announce, broadcast=broadcast, is_alive=is_alive,
                            body=_brief(evs) or "clear")
        delivered.append((name, st))
    return delivered
