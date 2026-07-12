#
# Calendar — reads the user's REAL Google Calendar via its secret iCal URL
# (no OAuth dance: Google Calendar -> Settings -> your calendar -> "Integrate
# calendar" -> "Secret address in iCal format" -> paste into .env as
# JARVIS_CALENDAR_ICS_URL). Read-only by construction.
#
# Recurring events: the basic weekly/daily RRULE cases are expanded for the
# requested window; exotic recurrence is reported as "recurring" rather than
# guessed. A missing/failed fetch is reported honestly.
#

import os
import re
from datetime import datetime, timedelta

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

ICS_URL = os.getenv("JARVIS_CALENDAR_ICS_URL", "")
_TIMEOUT = aiohttp.ClientTimeout(total=10)

# ICS BYDAY tokens -> Python datetime.weekday() (Mon=0 .. Sun=6).
_WEEKDAY = {"MO": 0, "TU": 1, "WE": 2, "TH": 3, "FR": 4, "SA": 5, "SU": 6}

GET_CALENDAR_SCHEMA = FunctionSchema(
    name="get_calendar",
    description=(
        "Read the user's real calendar. Use for 'what's on my calendar', 'am I free "
        "Thursday', 'what do I have tomorrow'. Returns events in the next N days."
    ),
    properties={
        "days": {"type": "number", "description": "How many days ahead to look, 1-14. Default 2 (today + tomorrow)."}
    },
    required=[],
)


def _parse_dt(value: str) -> datetime | None:
    """ICS DTSTART value -> local naive datetime (UTC 'Z' converted)."""
    value = value.strip()
    try:
        if value.endswith("Z"):
            from datetime import timezone

            return (
                datetime.strptime(value, "%Y%m%dT%H%M%SZ")
                .replace(tzinfo=timezone.utc)
                .astimezone()
                .replace(tzinfo=None)
            )
        if "T" in value:
            return datetime.strptime(value, "%Y%m%dT%H%M%S")
        return datetime.strptime(value, "%Y%m%d")  # all-day
    except ValueError:
        return None


def parse_ics_events(ics: str, start: datetime, end: datetime) -> list[dict]:
    events = []
    for block in re.findall(r"BEGIN:VEVENT(.*?)END:VEVENT", ics, re.S):
        # ICS lines end \r\n — the \r? is load-bearing, without it nothing matches.
        summary = re.search(r"^SUMMARY(?:;[^:]*)?:(.*?)\r?$", block, re.M)
        dtstart = re.search(r"^DTSTART(?:;[^:]*)?:(\S+?)\r?$", block, re.M)
        rrule = re.search(r"^RRULE:(\S+?)\r?$", block, re.M)
        if not (summary and dtstart):
            continue
        title = summary.group(1).strip().replace("\\,", ",")
        first = _parse_dt(dtstart.group(1))
        if first is None:
            continue
        all_day = "T" not in dtstart.group(1)

        occurrences: list[datetime] = []
        if rrule:
            rule = dict(kv.split("=", 1) for kv in rrule.group(1).split(";") if "=" in kv)
            freq = rule.get("FREQ", "")
            until = _parse_dt(rule.get("UNTIL", "")) if rule.get("UNTIL") else None
            byday = [_WEEKDAY[d] for d in rule.get("BYDAY", "").split(",") if d in _WEEKDAY]
            if freq == "WEEKLY" and byday:
                # MWF-style rule: expand EACH named weekday within each week,
                # not one occurrence per 7-day step (which dropped Wed/Fri).
                week = first - timedelta(days=first.weekday())  # Monday of anchor week
                for _ in range(20):
                    if week > end or (until and week > until):
                        break
                    for wd in byday:
                        occ = week + timedelta(days=wd)  # carries first's time-of-day
                        if occ < first or (until and occ > until):
                            continue
                        if start <= occ <= end:
                            occurrences.append(occ)
                    week += timedelta(days=7)
            elif freq in ("DAILY", "WEEKLY"):
                step = timedelta(days=1 if freq == "DAILY" else 7)
                cur = first
                # Fast-forward near the window, then collect occurrences in it.
                if cur < start:
                    skips = int((start - cur) / step)
                    cur += step * skips
                for _ in range(20):
                    if cur > end or (until and cur > until):
                        break
                    if cur >= start:
                        occurrences.append(cur)
                    cur += step
            elif start <= first <= end:
                occurrences.append(first)  # report at least the series anchor
        elif start <= first <= end:
            occurrences.append(first)

        for when in occurrences:
            events.append(
                {
                    "what": title,
                    "when": f"{when:%a %b %d}" + ("" if all_day else f" {when:%I:%M %p}"),
                    # Machine-readable start for the proactive calendar watcher
                    # (calendar_watch.py); the spoken surface keeps using "when".
                    "starts_at": when.isoformat(),
                    "all_day": all_day,
                    "_sort": when,
                }
            )
    events.sort(key=lambda e: e["_sort"])
    for e in events:
        del e["_sort"]
    return events[:20]


ADD_CALENDAR_EVENT_SCHEMA = FunctionSchema(
    name="add_calendar_event",
    description=(
        "Add an event to the user's real Google Calendar. Use for 'put X on my calendar', "
        "'schedule Y for Thursday at 2'. GATED: the first call returns a draft to read back "
        "('I'll add Dentist, Thursday July 10th at 2pm — put it on?'); only a second call with "
        "confirmed=true actually creates it. Convert spoken times to the start format yourself."
    ),
    properties={
        "title": {"type": "string", "description": "Event title, e.g. 'Dentist'."},
        "start": {"type": "string",
                  "description": "Start as 'YYYY-MM-DD HH:MM' (24h, local), or 'YYYY-MM-DD' "
                                 "for an all-day event."},
        "duration_min": {"type": "number",
                         "description": "Length in minutes (default 60). Ignored for all-day."},
        "all_day": {"type": "boolean", "description": "True for an all-day event."},
        "confirmed": {"type": "boolean",
                      "description": "Set true ONLY on the re-call after the user approves."},
    },
    required=["title", "start"],
)


def _parse_start(raw: str):
    """'YYYY-MM-DD HH:MM' / ISO / 'YYYY-MM-DD' -> datetime, else None. The voice LLM does the
    natural-language part; this only accepts unambiguous machine formats."""
    raw = (raw or "").strip().replace("T", " ")
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, fmt)
        except ValueError:
            continue
    return None


async def _connector_write_status():
    """Probe the OpenJarvis gcalendar connector: (available, connected).

    available=False means the daemon is unreachable or errored — the caller
    falls back to the webhook without noise. Split out so tests can patch it.
    """
    from openjarvis_client import OpenJarvisClient

    try:
        detail = await OpenJarvisClient().connector_detail("gcalendar")
        return True, bool(detail.get("connected"))
    except Exception:
        return False, False


async def _connector_create_event(title, start_str, duration, all_day):
    """Create the event via the OpenJarvis gcalendar connector (OAuth path)."""
    from openjarvis_client import OpenJarvisClient

    return await OpenJarvisClient().gcalendar_create_event(
        title, start_str, duration_min=duration, all_day=all_day
    )


async def handle_add_calendar_event(params: FunctionCallParams):
    """Calendar WRITE. Preferred path: the OpenJarvis gcalendar connector — the
    'Connect Google Calendar' OAuth consent already grants the full calendar scope
    (docs/google-calendar.md). Legacy fallback: the owner's Google Apps Script
    webhook (EVE_CAL_WRITE_URL + EVE_CAL_WRITE_TOKEN — docs/calendar-write.md).
    EVE_CAL_CONNECTOR=0 disables the connector probe entirely (legacy behavior).
    tool_policy's confirm gate runs BEFORE this handler (skills/add_calendar_event.md
    sets requires_confirmation), so this executes post-approval."""
    a = params.arguments or {}
    url = os.getenv("EVE_CAL_WRITE_URL", "")
    token = os.getenv("EVE_CAL_WRITE_TOKEN", "")
    title = str(a.get("title") or "").strip()
    if not title:
        await params.result_callback({"ok": False, "error": "the event needs a title."})
        return
    start = _parse_start(str(a.get("start") or ""))
    if start is None:
        await params.result_callback({
            "ok": False,
            "error": "I couldn't pin down the date — give start as YYYY-MM-DD HH:MM."})
        return
    # Date-only start (no clock time given) => all-day, unless the flag says otherwise.
    all_day = bool(a.get("all_day")) or (":" not in str(a.get("start") or ""))
    try:
        duration = max(5, min(24 * 60, int(a.get("duration_min") or 60)))
    except (TypeError, ValueError):
        duration = 60
    when = f"{start:%A %B %d}" + ("" if all_day else f" at {start:%I:%M %p}")
    ok_result = {
        "ok": True, "created": {"title": title, "when": when},
        "instruction": "Confirm in ONE short sentence that the event is on the calendar."}

    # --- Preferred path: EVE's own Google connection (self-contained,
    #     works on a bare jarvis-sidecar install — docs/google-calendar.md) ---
    import google_calendar_native as gnative
    if gnative.is_connected():
        try:
            await gnative.create_event(title, start, duration_min=duration,
                                       all_day=all_day)
        except Exception as e:
            # Connected-but-failed is a real error (revoked token, Google
            # rejection) — report it, don't cascade into another write path.
            logger.warning(f"add_calendar_event native Google write failed: {e}")
            await params.result_callback({
                "ok": False, "error": f"the event was NOT created: {e}"})
            return
        logger.info(f"add_calendar_event created {title!r} via native Google "
                    f"({'all-day' if all_day else duration})")
        await params.result_callback(ok_result)
        return

    # --- Second choice: OpenJarvis gcalendar connector (OAuth) ---
    if os.getenv("EVE_CAL_CONNECTOR", "1") != "0":
        available, connected = await _connector_write_status()
        if available and connected:
            start_str = f"{start:%Y-%m-%d}" if all_day else f"{start:%Y-%m-%d %H:%M}"
            try:
                await _connector_create_event(title, start_str, duration, all_day)
            except Exception as e:
                # Connected-but-failed is a real error (revoked token, Google
                # rejection). Do NOT fall through to the webhook — that risks
                # a duplicate event or masking a broken connection.
                logger.warning(f"add_calendar_event connector write failed: {e}")
                await params.result_callback({
                    "ok": False,
                    "error": f"the event was NOT created: {e}"})
                return
            logger.info(
                f"add_calendar_event created {title!r} via gcalendar connector "
                f"({'all-day' if all_day else duration})")
            await params.result_callback(ok_result)
            return

    # --- Legacy fallback: Apps Script webhook ---
    if not url:
        await params.result_callback({
            "ok": False,
            "error": ("calendar writing is not set up — connect Google Calendar "
                      "(the connect_google_calendar tool starts it), or configure the "
                      "legacy Apps Script webhook (EVE_CAL_WRITE_URL). "
                      "Reading the calendar still works.")})
        return
    payload = {"token": token, "title": title, "start": start.isoformat(),
               "duration_min": duration, "all_day": all_day}
    try:
        async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
            async with session.post(url, json=payload) as resp:
                body = await resp.text()
                try:
                    import json as _json
                    data = _json.loads(body)
                except ValueError:
                    data = {}
                if resp.status != 200 or not data.get("ok"):
                    raise RuntimeError(data.get("error") or f"webhook returned HTTP {resp.status}")
    except Exception as e:
        msg = str(e) or type(e).__name__
        if url:
            msg = msg.replace(url, "<calendar webhook>")   # the URL is a secret, like the ICS one
        await params.result_callback({
            "ok": False, "error": f"the event was NOT created: {msg}"})
        return
    logger.info(f"add_calendar_event created {title!r} ({'all-day' if all_day else duration})")
    await params.result_callback({
        "ok": True, "created": {"title": title, "when": when},
        "instruction": "Confirm in ONE short sentence that the event is on the calendar."})


CONNECT_GOOGLE_CALENDAR_SCHEMA = FunctionSchema(
    name="connect_google_calendar",
    description=(
        "Check, start, or remove the Google Calendar connection. Use for 'connect my "
        "calendar', 'is my calendar connected', 'disconnect my calendar'. Connecting is "
        "read-only here: it reports status and gives the consent link — the user "
        "completes the Google consent in their browser. Disconnecting REVOKES access and "
        "is GATED: first call with disconnect=true returns a draft to read back; only a "
        "second call adding confirmed=true actually revokes."
    ),
    properties={
        "disconnect": {"type": "boolean",
                       "description": "True to remove the connection (revokes the token)."},
        "confirmed": {"type": "boolean",
                      "description": "With disconnect: set true ONLY on the re-call after "
                                     "the user approves."},
    },
    required=[],
)


async def handle_connect_google_calendar(params: FunctionCallParams):
    """Check/start the Google Calendar connection.

    Preferred: EVE's own OAuth flow (google_calendar_native — works on a bare
    sidecar install; needs EVE_GOOGLE_CLIENT_ID/SECRET in .env). Second: the
    OpenJarvis gcalendar connector when that daemon is around. The consent
    always happens in the user's browser against Google — EVE never sees the
    password; tokens land on this box, chmod 600. docs/google-calendar.md has
    the one-time OAuth-client setup."""
    import google_calendar_native as gnative

    from persona import ASSISTANT_NAME  # configured self-name; EVE is a product

    a = params.arguments or {}
    if a.get("disconnect"):
        if not gnative.is_connected():
            await params.result_callback({
                "ok": True, "connected": False,
                "instruction": "Say the calendar isn't connected, so there's nothing to remove."})
            return
        if not a.get("confirmed"):
            await params.result_callback({
                "ok": True, "draft": "disconnect Google Calendar",
                "instruction": (f"Read back: this removes {ASSISTANT_NAME}'s access to the Google "
                                "Calendar (the token is revoked) — confirm? Only after a "
                                "clear yes, call again with disconnect and confirmed true.")})
            return
        try:
            await gnative.disconnect()
        except Exception as e:
            await params.result_callback({
                "ok": False, "error": f"disconnect failed: {e}"})
            return
        logger.info("connect_google_calendar: disconnected + revoked")
        await params.result_callback({
            "ok": True, "connected": False,
            "instruction": "Say the Google Calendar connection is removed and access revoked."})
        return

    if gnative.is_connected():
        await params.result_callback({
            "ok": True, "connected": True, "via": "native",
            "instruction": "Say Google Calendar is already connected and writing events works."})
        return
    if gnative.is_configured():
        url = gnative.start_connect_flow()
        await params.result_callback({
            "ok": True, "connected": False, "connect_url": url, "via": "native",
            "instruction": ("Say the Google consent page is opening in the browser "
                            "(the link also works on this machine) — approve it once "
                            "and the calendar is connected for reading and writing.")})
        return

    from openjarvis_client import OpenJarvisClient

    client = OpenJarvisClient()
    try:
        detail = await client.connector_detail("gcalendar")
    except Exception as e:
        await params.result_callback({
            "ok": False,
            "error": (f"calendar connection isn't set up: no Google OAuth client in the "
                      f"configuration (EVE_GOOGLE_CLIENT_ID/SECRET — see "
                      f"docs/google-calendar.md), and OpenJarvis isn't reachable ({e}).")})
        return
    if detail.get("connected"):
        await params.result_callback({
            "ok": True, "connected": True,
            "instruction": "Say Google Calendar is already connected and writing events works."})
        return
    setup = detail.get("oauth_setup") or {}
    if setup.get("has_credentials"):
        connect_url = f"{client.base_url}/v1/connectors/gcalendar/oauth/start"
        await params.result_callback({
            "ok": True, "connected": False, "connect_url": connect_url,
            "instruction": (f"Tell the user to open the connect link on this machine and "
                            f"approve Google's consent screen once; after that {ASSISTANT_NAME} can "
                            "read and write the calendar.")})
        return
    await params.result_callback({
        "ok": True, "connected": False,
        "setup_needed": "google_oauth_client",
        "setup_doc": "docs/google-calendar.md",
        "instruction": ("Explain that a one-time Google OAuth client setup is needed "
                        "first — the steps are in the Google Calendar setup guide "
                        "(docs/google-calendar.md); after pasting the client ID and "
                        "secret into OpenJarvis, connecting is one consent click.")})


async def handle_get_calendar(params: FunctionCallParams):
    try:
        days = max(1, min(14, int(params.arguments.get("days") or 2)))
    except Exception:
        days = 2

    # Preferred: the native Google connection (same consent as writes) — the
    # ICS feed below stays as the fallback for installs that never connected.
    import google_calendar_native as gnative
    if gnative.is_connected():
        try:
            events = await gnative.list_events(days)
        except Exception as e:
            logger.warning(f"get_calendar native Google read failed: {e}")
            await params.result_callback({
                "ok": False, "error": f"calendar lookup failed: {e}"})
            return
        logger.info(f"get_calendar(days={days}) -> {len(events)} events (native)")
        await params.result_callback({
            "ok": True, "window_days": days, "events": events,
            "instruction": ("Summarize the schedule naturally, day by day. "
                            "If empty, say the calendar is clear.")})
        return

    if not ICS_URL:
        await params.result_callback(
            {
                "ok": False,
                "error": (
                    "calendar is not connected yet — the secret iCal URL from Google "
                    "Calendar settings needs to be added to the configuration"
                ),
            }
        )
        return
    try:
        async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
            async with session.get(ICS_URL) as resp:
                if resp.status != 200:
                    raise RuntimeError(f"calendar feed returned HTTP {resp.status}")
                ics = await resp.text()
        start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
        events = parse_ics_events(ics, start, start + timedelta(days=days))
    except Exception as e:
        # aiohttp errors can embed the request URL in their message — and ours
        # is the SECRET calendar address. Scrub it before the error is spoken
        # aloud and persisted in the transcript log.
        msg = str(e) or type(e).__name__
        if ICS_URL:
            msg = msg.replace(ICS_URL, "<calendar url>")
        await params.result_callback({"ok": False, "error": f"calendar lookup failed: {msg}"})
        return
    logger.info(f"get_calendar(days={days}) -> {len(events)} events")
    await params.result_callback(
        {
            "ok": True,
            "window_days": days,
            "events": events,
            "instruction": "Summarize the schedule naturally, day by day. If empty, say the calendar is clear.",
        }
    )
