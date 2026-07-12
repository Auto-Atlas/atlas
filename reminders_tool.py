#
# Reminders & timers — "remind me in 20 minutes to flip the steaks" finally
# has something to fire it. Deterministic: the reminder is a JSON record on
# disk plus an asyncio timer; when it comes due, Jarvis speaks it through the
# same announce path as incoming texts. Restart-proof: pending reminders are
# reloaded at boot, and anything that came due while the process was down is
# announced as missed instead of silently dropped.
#
# Delivery contract: a reminder is removed from disk ONLY after its announce
# call returned without raising. If the body that scheduled it can no longer
# speak (the phone session that set it is gone), the record stays on disk and
# the desktop loop announces it as missed at its next boot — late beats
# never, and never-silently beats both.
#

import asyncio
import json
import os
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path

from loguru import logger

try:
    import fcntl  # posix-only; the real deploy is Linux
except ImportError:  # pragma: no cover - non-posix dev machines
    fcntl = None
    logger.warning("fcntl unavailable — reminders store has NO cross-process lock on this platform")

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

from persona import USER_NAME

_STORE = Path(os.getenv("JARVIS_REMINDERS_FILE", str(Path(__file__).parent / "reminders.json")))
_LOCK = _STORE.with_name(_STORE.name + ".lock")


@contextmanager
def _locked(exclusive: bool = True):
    """Cross-process file lock around a read-modify-write of the store.

    Desktop (bot.py) and phone (phone_bot.py) are separate processes that both
    read-modify-write the same JSON. Without this, A loads, B loads, A saves,
    B saves -> A's reminder is lost. We flock a sidecar <store>.lock for the
    whole load->modify->save so the RMW is atomic across processes. The lock is
    always released by the `with`, and never held across an await/announce.
    """
    if fcntl is None:  # pragma: no cover - non-posix degraded path
        yield
        return
    # Open (create) the lock file fresh each time; flock is advisory and tied to
    # the open fd, so one fd acquired+released per op can never deadlock.
    fd = os.open(str(_LOCK), os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)

SET_REMINDER_SCHEMA = FunctionSchema(
    name="set_reminder",
    description=(
        "Set a real reminder or timer that will be spoken aloud when due. Use for "
        "'remind me in 20 minutes to X', 'set a timer for 5 minutes', or 'remind me "
        "at 4:30 to X'. Give minutes_from_now OR at_time, never both. The reminder "
        "also rings the phone like an alarm clock and lands on the calendar "
        "automatically — no separate calendar or alarm step is needed."
    ),
    properties={
        "what": {"type": "string", "description": "What to say when it fires, e.g. 'call Alex back'."},
        "minutes_from_now": {"type": "number", "description": "Minutes until it fires, e.g. 20. Omit if using at_time."},
        "at_time": {"type": "string", "description": "24h clock time today/tomorrow like '16:30'. Omit if using minutes_from_now."},
    },
    required=["what"],
)

LIST_REMINDERS_SCHEMA = FunctionSchema(
    name="list_reminders",
    description="List every pending reminder/timer with when it fires. Also use before cancelling one.",
    properties={},
    required=[],
)

CANCEL_REMINDER_SCHEMA = FunctionSchema(
    name="cancel_reminder",
    description="Cancel a pending reminder by its number from list_reminders.",
    properties={"number": {"type": "number", "description": "The reminder's number from list_reminders."}},
    required=["number"],
)


def _load() -> list[dict]:
    if not _STORE.is_file():
        return []
    try:
        items = json.loads(_STORE.read_text(encoding="utf-8"))
        return items if isinstance(items, list) else []
    except Exception as e:
        # A corrupt store must not silently become "no reminders": keep the
        # evidence aside and shout, instead of swallowing every pending one.
        try:
            corrupt = _STORE.with_name(_STORE.name + ".corrupt")
            _STORE.replace(corrupt)
            logger.error(f"reminders store unreadable ({e}) — moved to {corrupt}")
        except OSError:
            logger.error(f"reminders store unreadable ({e})")
        return []


def _save(items: list[dict]) -> None:
    # Atomic: write a sibling temp file, then os.replace (atomic rename). A
    # crash mid-write can no longer corrupt (and thereby erase) every pending
    # reminder. The temp name is process-unique so two writers can't clobber a
    # shared temp before the rename.
    tmp = _STORE.with_name(f"{_STORE.name}.{os.getpid()}.tmp")
    tmp.write_text(json.dumps(items, indent=1), encoding="utf-8")
    os.replace(str(tmp), str(_STORE))


def _mutate(fn) -> None:
    """Run fn(items)->new_items as one atomic load->modify->save under the
    cross-process lock, so the RMW reads fresh state and can't lose a
    concurrent writer's change."""
    with _locked(exclusive=True):
        _save(fn(_load()))


def _new_id() -> str:
    return uuid.uuid4().hex[:12]


def _nag_enabled() -> bool:
    # Ack-loop handoff (EVE_REMINDER_NAG, default on): a fired reminder becomes an OPEN item
    # the initiative engine re-surfaces until confirmed done — never a one-shot into silence.
    return os.getenv("EVE_REMINDER_NAG", "1") == "1"


def _mirror_enabled() -> bool:
    # Outbound mirroring (EVE_REMINDER_MIRROR, default on): a set reminder ALSO rings
    # the phone like a real alarm clock (FCM data push -> the app's AlarmManager) and
    # lands on the Google calendar. The reminders store stays the source of truth —
    # the mirrors are conveniences, so every mirror call is best-effort and can never
    # break the set/cancel/announce path.
    return os.getenv("EVE_REMINDER_MIRROR", "1") == "1"


# Strong refs to in-flight mirror tasks: a bare create_task result can be GC'd
# mid-flight, silently killing the mirror. Tasks remove themselves when done.
_MIRROR_TASKS: set = set()


def _spawn_mirror(coro) -> None:
    t = asyncio.create_task(coro)
    _MIRROR_TASKS.add(t)
    t.add_done_callback(_MIRROR_TASKS.discard)


async def _mirror_out(r: dict) -> None:
    """Best-effort fan-out of a freshly set reminder: (1) a Google Calendar event,
    (2) a set_alarm push so the PHONE rings at due time even if every voice session
    is dead. Each leg fails independently and only logs — the on-disk reminder and
    its asyncio timer are already in place before this ever runs."""
    # Leg 1: calendar. Skip sub-threshold timers (a 3-minute egg timer on the
    # calendar is clutter); EVE_REMINDER_CAL_MIN_MINUTES=0 (default) mirrors all.
    try:
        cal_min = float(os.getenv("EVE_REMINDER_CAL_MIN_MINUTES", "0")) * 60.0
        if r["due"] - time.time() >= cal_min:
            import google_calendar_native as gnative
            if gnative.is_configured() and gnative.is_connected():
                ev = await gnative.create_event(
                    f"Reminder: {r['what']}",
                    datetime.fromtimestamp(r["due"]),
                    duration_min=int(os.getenv("EVE_REMINDER_CAL_DURATION_MIN", "15")),
                )
                if ev.get("id"):
                    # Stamp the event id onto the stored record so cancel can
                    # take the calendar event down with it.
                    def _stamp(cur):
                        for x in cur:
                            if x.get("id") == r["id"]:
                                x["gcal_event_id"] = ev["id"]
                        return cur
                    _mutate(_stamp)
                    logger.info(f"reminder {r['what']!r} mirrored to calendar ({ev['id']})")
    except Exception as e:
        logger.warning(f"reminder {r['what']!r}: calendar mirror failed (reminder still set): {e}")
    # Leg 2: phone alarm. String values by FCM contract; the app schedules a
    # Doze-exempt AlarmManager alarm keyed by the reminder id.
    try:
        import push_sender
        results = await asyncio.to_thread(push_sender.broadcast_data, {
            "type": "set_alarm", "id": r["id"],
            "due_epoch": str(int(r["due"])), "what": r["what"],
        })
        if not results:
            logger.debug("reminder set_alarm push skipped: no registered devices")
    except Exception as e:
        logger.warning(f"reminder {r['what']!r}: phone alarm push failed (reminder still set): {e}")


async def _unmirror(r: dict) -> None:
    """Best-effort teardown of a cancelled reminder's mirrors: delete the calendar
    event we created (if any) and tell the phone to drop its alarm."""
    ev_id = r.get("gcal_event_id", "")
    if ev_id:
        try:
            import google_calendar_native as gnative
            await gnative.delete_event(ev_id)
        except Exception as e:
            logger.warning(f"cancelled reminder {r['what']!r}: calendar event not removed: {e}")
    try:
        import push_sender
        await asyncio.to_thread(
            push_sender.broadcast_data, {"type": "cancel_alarm", "id": r["id"]}
        )
    except Exception as e:
        logger.warning(f"cancelled reminder {r['what']!r}: phone alarm not cancelled: {e}")


async def _fire_push(r: dict, *, missed: bool = False) -> None:
    """Push the DUE reminder to the phone as a notification. This is the leg that
    makes a desktop-set reminder reach the owner's pocket: announce() speaks into
    whatever room the owning process is in (possibly empty), and the FCM alarm
    mirror is inert until Firebase is configured — ntfy is the channel approval
    pushes already ride, so it works today. Best-effort like every mirror leg."""
    try:
        import approval_push
        when = datetime.fromtimestamp(r["due"])
        await approval_push.notify_event(
            r["what"],
            title=("EVE — reminder (missed while offline)" if missed
                   else f"EVE — reminder due ({when:%I:%M %p})"),
            tags="alarm_clock",
        )
    except Exception as e:
        logger.warning(f"reminder {r['what']!r}: phone notification failed: {e}")


def _to_nag_store(r: dict) -> None:
    """Register a fired reminder with the ack loop. Total: a nag-store hiccup must never
    break the fire/clear path (the reminder was already spoken)."""
    try:
        import nag_store
        ttl_s = float(os.getenv("EVE_REMINDER_NAG_TTL_MIN", "120")) * 60.0
        nag_store.add(r["what"], source="reminder", ref=r["id"], due=r["due"],
                      expire_at=time.time() + ttl_s)
    except Exception as e:
        logger.warning(f"reminder {r['what']!r} fired but ack-loop handoff failed: {e}")


class ReminderService:
    def __init__(self, announce):
        self._announce = announce
        # Keyed by reminder id, NOT due timestamp — two reminders set for the
        # same minute used to collide and cancel each other.
        self._tasks: dict[str, asyncio.Task] = {}

    async def start(self):
        """Reload pending reminders; voice anything missed while offline."""
        with _locked(exclusive=True):
            items = _load()
            changed = False
            for r in items:  # legacy records predate ids
                if "id" not in r:
                    r["id"] = _new_id()
                    changed = True
            now = time.time()
            missed = [r for r in items if r["due"] <= now]
            pending = [r for r in items if r["due"] > now]
            # Persist the id backfill now (under the lock) so the records we go
            # on to announce/clear below are stable; the announce stays OUT of
            # the lock so we never hold it across an await.
            if changed:
                _save(items)
        for r in pending:
            self._schedule(r)
        if missed and _mirror_enabled():
            # Came due while every process was down — the phone never heard about
            # these either, so push them before attempting the spoken announce.
            for r in missed:
                _spawn_mirror(_fire_push(r, missed=True))
        if missed:
            lines = "; ".join(
                f"{r['what']} (was due {datetime.fromtimestamp(r['due']):%I:%M %p})" for r in missed[:5]
            )
            try:
                await self._announce(
                    f"While you were offline these reminders came due: {lines}. "
                    f"Tell {USER_NAME} about them in one or two short sentences."
                )
            except Exception as e:
                # Couldn't speak them — keep the records for the next boot.
                logger.warning(f"missed reminders not announced, kept on disk: {e}")
                return
            # Missed-while-offline reminders enter the ack loop too: "announced as missed"
            # is even less confirmation than a live announce.
            if _nag_enabled():
                for r in missed:
                    _to_nag_store(r)
            # Clear only the records we just announced; re-read fresh under the
            # lock so a reminder another process set meanwhile survives.
            missed_ids = {r["id"] for r in missed}
            _mutate(lambda cur: [x for x in cur if x.get("id") not in missed_ids])
        if pending:
            logger.info(f"Reminders reloaded: {len(pending)} pending, {len(missed)} missed")

    def cancel_all(self):
        """Cancel every in-process timer. Records stay on disk — the desktop
        loop picks them up as pending/missed at its next boot. Used when a
        phone session ends so its timers can't fire into a dead pipeline."""
        for task in self._tasks.values():
            task.cancel()
        self._tasks.clear()

    def _schedule(self, r: dict):
        async def fire():
            await asyncio.sleep(max(0, r["due"] - time.time()))
            self._tasks.pop(r["id"], None)
            nagging = _nag_enabled()
            # Phone notification BEFORE the spoken announce: the announce can only
            # reach the room this process is in — the push reaches the pocket, and
            # must go out even if the announce then fails on a dead pipeline.
            if _mirror_enabled():
                _spawn_mirror(_fire_push(r))
            try:
                await self._announce(
                    f"REMINDER DUE RIGHT NOW: {r['what']!r}. Tell {USER_NAME} clearly and briefly — "
                    "this is the reminder he asked for."
                    + (" Also mention, briefly, that you'll check back until he says it's done."
                       if nagging else "")
                )
            except Exception as e:
                # Announce failed (dead session, stopped pipeline). The record
                # stays on disk so it surfaces as missed instead of vanishing.
                logger.warning(f"reminder {r['what']!r} not announced — kept on disk: {e}")
                return
            # Fired is not FINISHED: hand the record to the ack loop (nag_store) so the
            # initiative engine re-surfaces it until {USER_NAME} confirms complete
            # (complete_reminder) — "remind me to flip the steaks" now survives being
            # spoken into a moment of not-listening.
            if nagging:
                _to_nag_store(r)
            # Clear just this fired record; re-read fresh under the lock so a
            # concurrently-added reminder isn't clobbered.
            _mutate(lambda cur: [x for x in cur if x.get("id") != r["id"]])

        self._tasks[r["id"]] = asyncio.create_task(fire())

    async def handle_set(self, params: FunctionCallParams):
        what = str(params.arguments.get("what", "")).strip()
        mins = params.arguments.get("minutes_from_now")
        at = str(params.arguments.get("at_time", "") or "").strip()
        if not what:
            await params.result_callback({"ok": False, "error": "no reminder text given"})
            return
        try:
            if mins is not None:
                due = time.time() + float(mins) * 60
            elif at:
                hh, mm = at.split(":")
                target = datetime.now().replace(hour=int(hh), minute=int(mm), second=0, microsecond=0)
                if target <= datetime.now():
                    target += timedelta(days=1)  # past time today -> tomorrow
                due = target.timestamp()
            else:
                await params.result_callback(
                    {"ok": False, "error": "need minutes_from_now or at_time"}
                )
                return
        except Exception as e:
            await params.result_callback({"ok": False, "error": f"could not parse the time: {e}"})
            return

        r = {"id": _new_id(), "due": due, "what": what}
        # Locked RMW: re-read fresh and append so a reminder set on the other
        # process (desktop vs phone) between our load and save isn't lost.
        _mutate(lambda cur: cur + [r])
        self._schedule(r)
        # Fan out to the calendar + phone alarm AFTER the reminder is safely on
        # disk and armed — fire-and-forget so the voice reply is never delayed
        # by Google or FCM round-trips.
        if _mirror_enabled():
            _spawn_mirror(_mirror_out(r))
        when = datetime.fromtimestamp(due)
        logger.info(f"reminder set: {what!r} at {when:%H:%M:%S}")
        await params.result_callback(
            {"ok": True, "set": True, "fires_at": f"{when:%I:%M %p}", "what": what}
        )

    async def handle_list(self, params: FunctionCallParams):
        with _locked(exclusive=False):  # shared: consistent read vs a writer mid-replace
            items = sorted(_load(), key=lambda r: r["due"])
        await params.result_callback(
            {
                "ok": True,
                "count": len(items),
                "reminders": [
                    {"number": i + 1, "what": r["what"], "fires_at": f"{datetime.fromtimestamp(r['due']):%I:%M %p}"}
                    for i, r in enumerate(items)
                ],
            }
        )

    async def handle_cancel(self, params: FunctionCallParams):
        try:
            n = int(params.arguments.get("number", 0))
        except Exception:
            n = 0
        # The whole pick-by-index + save must be one locked RMW: reading fresh
        # under the lock keeps the index stable and avoids clobbering a reminder
        # the other process added between our load and save. The callback is
        # invoked OUTSIDE the lock so we never hold it across an await.
        victim = None
        with _locked(exclusive=True):
            items = sorted(_load(), key=lambda r: r["due"])
            if 1 <= n <= len(items):
                victim = items.pop(n - 1)
                _save(items)
        if victim is None:
            await params.result_callback({"ok": False, "error": f"no reminder number {n} — list them first"})
            return
        task = self._tasks.pop(victim.get("id") or "", None)
        if task:
            task.cancel()
        # Take the mirrors down with it (calendar event + phone alarm), best-effort.
        if _mirror_enabled():
            _spawn_mirror(_unmirror(victim))
        logger.info(f"reminder cancelled: {victim['what']!r}")
        await params.result_callback({"ok": True, "cancelled": victim["what"]})
