# agent_delivery.py
#
# ONE shared delivery function for agent-task updates (talk-back spec §4.3) — the poller
# (bot.py) and the inbound push bridge (a2a_fabric.handle_push) both call this, so poll-path
# and push-path updates reach the owner identically: spoken at the mic, push-notified
# (ntfy -> Telegram) when he's away or in quiet hours, always broadcast to the mobile app,
# and left for replay when nothing actually landed.
#
# delivered_at discipline (spec §4.3): ONLY terminal kinds (result/blocker) may mark_delivered.
# Progress never touches it; questions resurface by STATE (agent_tasks.list_awaiting), not by
# delivered_at — so a delivered progress line or question can never poison the terminal
# result's replay.
#
# Import invariant: agent_tasks + try_announce + delivery_policy + approval_push only.
# NEVER tool_policy/jarvis_core/bot/phone_bot (webhook-surface safe).
#
import inspect
import os

from loguru import logger

import agent_tasks
import approval_push
import delivery_policy
import try_announce

AGENT_RESULT = "agent_result"
AGENT_BLOCKER = "agent_blocker"
AGENT_QUESTION = "agent_question"
AGENT_PROGRESS = "agent_progress"

SPOKEN = "spoken"
NOTIFIED = "notified"
QUEUED = "queued"
BROADCAST_ONLY = "broadcast_only"


def _derive_kind(row):
    status = row.get("status")
    if status == agent_tasks.AWAITING_USER:
        return AGENT_QUESTION
    if status == agent_tasks.FAILED or row.get("effective_status") == "failed":
        return AGENT_BLOCKER
    return AGENT_RESULT


def _instruction(row, kind, text):
    if kind == AGENT_QUESTION:
        return try_announce.question_instruction(row)
    if kind == AGENT_PROGRESS:
        return try_announce.progress_instruction(row, text)
    if kind == AGENT_BLOCKER:
        return try_announce.blocker_instruction(row)
    return try_announce.live_instruction(row)


def _headline(row, kind, text):
    if kind == AGENT_QUESTION:
        from persona import ASSISTANT_NAME  # configured self-name; EVE is a product
        q = (row.get("question") or {}).get("question") or "needs your input"
        window = int(float(os.getenv("EVE_TALKBACK_ASK_WAIT_S", "840")) // 60)
        return (f"{row.get('agent', 'agent')} is asking: {str(q)[:200]} — "
                f"say to {ASSISTANT_NAME}: answer {row.get('agent', 'the agent')}: <your answer> "
                f"(within ~{window} min)")
    if kind == AGENT_PROGRESS:
        return f"{row.get('agent', 'agent')} update: {str(text or '')[:140]}"
    return delivery_policy.headline(row)


async def _notify(row, kind, text):
    # The question notify deep-links the approval card (approval_id) so tapping Review opens
    # it; a question whose staging crashed mid-way has no card — fall back to the row id.
    if kind == AGENT_QUESTION:
        nid = (row.get("question") or {}).get("approval_id") or row["id"]
    else:
        nid = row["id"]
    from persona import ASSISTANT_NAME  # configured self-name; EVE is a product
    titles = {AGENT_QUESTION: f"{ASSISTANT_NAME} — {row.get('agent', 'an agent')} needs your answer",
              AGENT_BLOCKER: f"{ASSISTANT_NAME} — {row.get('agent', 'an agent')} hit a blocker",
              AGENT_PROGRESS: f"{ASSISTANT_NAME} — {row.get('agent', 'an agent')} update"}
    try:
        res = await approval_push.notify(_headline(row, kind, text), nid,
                                         title=titles.get(kind, f"{ASSISTANT_NAME} — task update"))
    except Exception as e:  # notify() never raises today; stay total anyway
        logger.debug(f"deliver_update notify failed: {e!r}")
        return False
    return bool(res.get("ntfy") or res.get("telegram"))


def _broadcast(broadcast, row, kind, text):
    try:
        res = broadcast({"type": kind, "agent": row.get("agent"), "summary": row.get("summary"),
                         "text": str(text or "")[:500], "cid": row.get("id"),
                         # The app's Approvals live feed keys per-task cards on task_id and
                         # paints state from status; cid stays for older consumers.
                         "task_id": row.get("id"), "status": row.get("status")})
        if inspect.isawaitable(res):
            import asyncio
            asyncio.ensure_future(res)
    except Exception:
        pass


async def deliver_update(row, *, announce, broadcast, is_alive=None, kind=None, text=None) -> str:
    """Deliver one agent-task update to the owner through the RIGHT channel for the moment.
    Returns SPOKEN / NOTIFIED / QUEUED / BROADCAST_ONLY. `kind` forces the update type
    (progress rows never change status, so their callers must force AGENT_PROGRESS);
    `text` overrides the delivered text (progress lines, summarized results)."""
    kind = kind or _derive_kind(row)
    if text is None:
        if kind == AGENT_QUESTION:
            text = (row.get("question") or {}).get("question") or ""
        else:
            result = row.get("result") or {}
            text = str(result.get("text") or result.get("error") or "")
    _broadcast(broadcast, row, kind, text)

    quiet = delivery_policy.in_quiet_hours()
    alive = is_alive() if is_alive is not None else True

    if kind == AGENT_PROGRESS:
        # Progress is informational: spoken live outside quiet hours, otherwise app-broadcast
        # only. It NEVER push-notifies (no 2am buzz per update) and NEVER marks delivered.
        if quiet or not alive:
            return BROADCAST_ONLY
        st = await try_announce.deliver(announce, _instruction(row, kind, text),
                                        cid=None, is_alive=is_alive)
        return SPOKEN if st == try_announce.SPOKEN else BROADCAST_ONLY

    if kind == AGENT_QUESTION:
        # A blocked agent outranks the clock: a live session hears the question even in quiet
        # hours; otherwise the highest-priority notify carries the question + the exact voice
        # command. Questions never mark_delivered — resurfacing keys on AWAITING_USER state.
        if alive:
            st = await try_announce.deliver(announce, _instruction(row, kind, text),
                                            cid=None, is_alive=is_alive)
            if st == try_announce.SPOKEN:
                return SPOKEN
        return NOTIFIED if await _notify(row, kind, text) else QUEUED

    # Terminal kinds (result / blocker): quiet hours notify; else speak; dead-session or
    # failed-speak falls back to notify. mark_delivered ONLY when something actually landed
    # (try_announce.deliver marks on SPOKEN itself).
    #
    # Unsolicited standing-link rows get RESURFACING discipline on top: a push is a heads-up,
    # not delivery — EVE still owes a SPOKEN mention, so a successful notify must NOT consume
    # delivered_at ("we never wake EVE up when Hermes messages"). Safe asymmetry by replay
    # loop: link MESSAGES (resolved) ride claim_replays, which only ever speaks — unmarked is
    # always safe. Link BLOCKERS (failed) ride failed_replays, which re-pushes every ~60s —
    # they stay unmarked only in the quiet branch (the replay watcher is quiet-gated, and the
    # morning tick speaks them); an unmarked notify outside quiet hours would buzz forever.
    unsol = try_announce.unsolicited(row)
    if quiet:
        if await _notify(row, kind, text):
            if not unsol:
                agent_tasks.mark_delivered(row["id"])
            return NOTIFIED
        return QUEUED
    st = await try_announce.deliver(announce, _instruction(row, kind, text),
                                    cid=row["id"], is_alive=is_alive)
    if st == try_announce.SPOKEN:
        if unsol and kind != AGENT_BLOCKER and \
                os.getenv("EVE_AGENT_LINK_ALWAYS_PUSH", "1") == "1":
            # Presence is unknowable from process-liveness: on an always-on mic, "spoken"
            # can mean spoken to an EMPTY ROOM (proven live 2026-07-04 — Hermes's first-sale
            # message was marked delivered unheard). Unsolicited messages are rare (daily-
            # budgeted) and matter, so mirror them to the phone even when spoken. Messages
            # only: fresh blockers re-push via failed_replays when needed, and a morning
            # blocker replay through this path would double-buzz after the overnight push.
            await _notify(row, kind, text)
        return SPOKEN
    if await _notify(row, kind, text):
        if not (unsol and kind != AGENT_BLOCKER):
            agent_tasks.mark_delivered(row["id"])
        return NOTIFIED
    return QUEUED
