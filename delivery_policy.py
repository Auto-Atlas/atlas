# delivery_policy.py
#
# Context-aware ping-back delivery (EVE Agent Hub spec §11.H). When a delegated result comes home,
# decide HOW to surface it: SPEAK it aloud (the default — at a conversational gap), or, during
# quiet hours, HOLD the voice and NOTIFY with the answer HEADLINE (not a teaser) so the owner still
# gets it without being spoken at, say, 2am.
#
# Opt-in: with EVE_QUIET_HOURS unset there is no quiet window, decide() always returns SPEAK, and
# behavior is byte-identical to before. Pure + import-light so the decision is unit-testable without
# the voice pipeline; the poller/callback wire it.
#
import os
from datetime import datetime

SPEAK = "speak"
NOTIFY = "notify"
HOLD = "hold"


def _quiet_window():
    """Parse EVE_QUIET_HOURS ('22-7' = 22:00..07:00). None if unset/malformed (no quiet hours)."""
    raw = os.getenv("EVE_QUIET_HOURS", "").strip()
    if not raw or "-" not in raw:
        return None
    try:
        a, b = raw.split("-", 1)
        return int(a) % 24, int(b) % 24
    except ValueError:
        return None


def in_quiet_hours(now: datetime | None = None) -> bool:
    win = _quiet_window()
    if win is None:
        return False
    start, end = win
    if start == end:
        return False
    hour = (now or datetime.now()).hour
    if start < end:
        return start <= hour < end
    return hour >= start or hour < end   # window wraps past midnight


def decide(*, quiet: bool) -> str:
    """SPEAK by default (today's behavior); during quiet hours NOTIFY (silent push) instead."""
    return NOTIFY if quiet else SPEAK


def headline(row: dict) -> str:
    """A notification body that CARRIES THE ANSWER (not 'you have a result'). Untrusted result
    text is included verbatim-but-clipped; the caller never acts on it, only surfaces it."""
    agent = row.get("agent") or "an agent"
    result = row.get("result") or {}
    text = str(result.get("text") or result.get("result") or "").strip()
    if text:
        return f"{agent}: {text[:140]}"
    summary = row.get("summary") or row.get("task") or "a task"
    return f"the {agent} agent finished: {str(summary)[:140]}"
