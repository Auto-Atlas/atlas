# health_tool.py — EVE's window into the owner's body data (Vision Priority #1: HEALTH FIRST).
#
# Reads the LATEST phone-uploaded Health Connect snapshot from health_store. Read-only and
# trivial-tier (no confirmation): it can't act, only report. Honesty spine: the snapshot's AGE
# always rides along with an instruction to SPEAK the as-of time, and stale/missing data is
# said plainly — EVE never presents old numbers as current vitals.
#
# Import invariant (same as approval_api): no jarvis_core / bot / phone_bot imports here —
# jarvis_core imports US.

from __future__ import annotations

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

import health_store

# Past this age the numbers are "earlier today", not "now" — the tool switches to stale
# phrasing and tells EVE to say so. Watch->phone sync batches on Samsung's schedule, so
# minutes-old is normal; hours-old means the phone hasn't uploaded (app closed, permissions).
_STALE_AFTER_S = 2 * 3600

HEALTH_STATUS_SCHEMA = FunctionSchema(
    name="health_status",
    description=(
        "Read the user's latest body data from their watch/phone (via Samsung Health): "
        "heart rate (latest + today's range), steps today, last night's sleep, blood "
        "oxygen, blood pressure (only if a cuff synced one), recent workouts. Use when "
        "the user asks about their heart, sleep, steps, vitals, or how their body is "
        "doing. ALWAYS mention how fresh the data is (the result includes the age)."
    ),
    properties={},
    required=[],
)


def _age_phrase(age_s: float) -> str:
    if age_s < 90:
        return "moments ago"
    if age_s < 3600:
        return f"{int(age_s // 60)} minutes ago"
    return f"{age_s / 3600:.1f} hours ago"


async def handle_health_status(params: FunctionCallParams):
    snapshot, age_s = health_store.load()

    if snapshot is None:
        await params.result_callback(
            {
                "ok": False,
                "error": "no health snapshot has ever arrived from the phone",
                "instruction": (
                    "Tell the user plainly: you can't see their health data yet. The EVE "
                    "phone app needs Health Connect permissions granted (Health row in the "
                    "app) so it can start sending snapshots. Never guess numbers."
                ),
            }
        )
        return

    stale = age_s is not None and age_s > _STALE_AFTER_S
    await params.result_callback(
        {
            "ok": True,
            "as_of": _age_phrase(age_s or 0.0),
            "age_seconds": int(age_s or 0),
            "stale": stale,
            "data": snapshot,
            "instruction": (
                "Speak the numbers with their as-of time (e.g. 'as of 20 minutes ago'). "
                + (
                    "This snapshot is STALE (hours old) — say so plainly and suggest "
                    "opening the EVE app to refresh. Do not present these as current vitals."
                    if stale
                    else "Fields that are null with a reason are honest gaps — if asked "
                         "about one, say why it's missing rather than guessing."
                )
            ),
        }
    )
