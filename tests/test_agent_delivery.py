# Tests for agent_delivery.deliver_update — the ONE shared reach-owner delivery path
# (talk-back spec §4.3). Pins the delivered_at discipline: terminal kinds only; progress and
# questions never touch it (a delivered progress line must not poison the result's replay).
import asyncio
import importlib
import os
import tempfile
from unittest.mock import AsyncMock

import pytest

import agent_delivery


@pytest.fixture
def store(monkeypatch):
    d = tempfile.mkdtemp()
    db = os.path.join(d, "approvals.db")
    import approval_store
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    approval_store.set_db_path(db)
    import agent_tasks
    importlib.reload(agent_tasks)
    return agent_tasks


def _row(store, status=None, question=None):
    cid, _tok = store.create("hermes", "post standup", summary="post standup", delivery="push",
                             requester="W", requester_tier="owner", ttl_s=3600)
    if question:
        store.set_awaiting_user_cas(cid, question)
    if status == "failed":
        store.fail(cid, "blocked: no creds")
    elif status == "resolved":
        store.resolve(cid)
        store.finish(cid, {"ok": True, "text": "done"})
    return store.get(cid)


def _run(coro):
    return asyncio.run(coro)


def test_live_result_speaks_and_marks(store):
    row = _row(store, "resolved")
    ann, sent = AsyncMock(), []
    st = _run(agent_delivery.deliver_update(row, announce=ann, broadcast=sent.append,
                                            is_alive=lambda: True))
    assert st == "spoken" and ann.await_count == 1
    assert store.get(row["id"])["delivered_at"] is not None
    assert sent and sent[0]["type"] == "agent_result" and sent[0]["cid"] == row["id"]


def test_live_blocker_uses_blocker_kind(store):
    row = _row(store, "failed")
    sent = []
    _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=sent.append,
                                       is_alive=lambda: True))
    assert sent[0]["type"] == "agent_blocker"


def test_quiet_hours_notify_marks_iff_landed(store, monkeypatch):
    row = _row(store, "resolved")
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "notified" and store.get(row["id"])["delivered_at"] is not None


def test_quiet_hours_total_notify_failure_stays_undelivered(store, monkeypatch):
    # Today's poller marks delivered even when BOTH channels return False — this pins the fix.
    row = _row(store, "resolved")
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    bad = AsyncMock(return_value={"ntfy": False, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", bad)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "queued" and store.get(row["id"])["delivered_at"] is None


def test_dead_session_falls_back_to_notify(store, monkeypatch):
    # A task update must REACH the owner, not queue silently into a dead session.
    row = _row(store, "resolved")
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: False))
    assert st == "notified" and ok.await_count == 1
    assert store.get(row["id"])["delivered_at"] is not None


def test_dead_session_notify_failure_queues_for_replay(store, monkeypatch):
    row = _row(store, "resolved")
    bad = AsyncMock(return_value={"ntfy": False, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", bad)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: False))
    assert st == "queued" and store.get(row["id"])["delivered_at"] is None


def test_progress_never_marks_delivered(store, monkeypatch):
    row = _row(store)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                       is_alive=lambda: True,
                                       kind=agent_delivery.AGENT_PROGRESS, text="halfway"))
    assert store.get(row["id"])["delivered_at"] is None


def test_progress_quiet_hours_broadcast_only(store, monkeypatch):
    # No 2am buzz per progress line: quiet hours => app broadcast only.
    row = _row(store)
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    notify = AsyncMock()
    monkeypatch.setattr(agent_delivery.approval_push, "notify", notify)
    ann, sent = AsyncMock(), []
    st = _run(agent_delivery.deliver_update(row, announce=ann, broadcast=sent.append,
                                            is_alive=lambda: True,
                                            kind=agent_delivery.AGENT_PROGRESS, text="halfway"))
    assert st == "broadcast_only" and notify.await_count == 0 and ann.await_count == 0
    assert sent[0]["type"] == "agent_progress" and sent[0]["text"] == "halfway"


def test_question_speaks_even_in_quiet_hours_when_alive(store, monkeypatch):
    # A blocked agent outranks the clock: a live session hears the question at 2am.
    row = _row(store, question={"qid": "q1", "question": "which channel?",
                                "approval_id": "ap1", "asked_at": 1.0})
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    ann = AsyncMock()
    st = _run(agent_delivery.deliver_update(row, announce=ann, broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "spoken" and ann.await_count == 1
    inst = ann.await_args[0][0]
    assert "which channel?" in inst and "hermes" in inst
    assert store.get(row["id"])["delivered_at"] is None     # questions never mark


def test_question_away_notifies_with_approval_deeplink_and_voice_command(store, monkeypatch):
    row = _row(store, question={"qid": "q1", "question": "which channel?",
                                "approval_id": "ap1", "asked_at": 1.0})
    calls = []

    async def notify(summary, aid, title=""):
        calls.append((summary, aid, title))
        return {"ntfy": True, "telegram": False}
    monkeypatch.setattr(agent_delivery.approval_push, "notify", notify)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: False))
    assert st == "notified"
    summary, aid, title = calls[0]
    assert aid == "ap1"
    assert "which channel?" in summary and "answer hermes" in summary.lower()
    assert "hermes" in title                                # honest notification title
    assert store.get(row["id"])["delivered_at"] is None     # questions never mark


def test_question_missing_approval_id_notify_still_works(store, monkeypatch):
    # Crash-between-CAS-and-stage degradation: the notify falls back to the row id.
    row = _row(store, question={"qid": "q1", "question": "?", "approval_id": "",
                                "asked_at": 1.0})
    calls = []

    async def notify(summary, aid, title=""):
        calls.append(aid)
        return {"ntfy": True, "telegram": False}
    monkeypatch.setattr(agent_delivery.approval_push, "notify", notify)
    _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                       is_alive=lambda: False))
    assert calls == [row["id"]]


def _link_row(store, kind="message"):
    # Exactly what a2a_fabric.handle_link mints: unsolicited standing-link row, terminal
    # immediately. Blocker rows carry NO result.unsolicited flag — only the requester stamp.
    cid, _tok = store.create("hermes", "store made a sale", summary="message from hermes",
                             delivery="push", requester="link:hermes", requester_tier="agent",
                             ttl_s=3600)
    if kind == "blocker":
        store.fail(cid, "shopify token expired")
    else:
        store.resolve(cid)
        store.finish(cid, {"ok": True, "text": "store made a sale", "unsolicited": True})
    return store.get(cid)


def test_link_message_quiet_notify_stays_replayable(store, monkeypatch):
    # "Wake EVE up": an overnight push for an unsolicited Hermes message is a heads-up, NOT
    # delivery — delivered_at stays NULL so the replay watcher SPEAKS it once quiet hours end.
    row = _link_row(store)
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "notified" and ok.await_count == 1
    assert store.get(row["id"])["delivered_at"] is None
    assert row["id"] in [r["id"] for r in store.claim_replays()]


def test_link_message_dead_session_notify_stays_replayable(store, monkeypatch):
    # Message lands while the session is torn down: push goes out, and EVE still owes a
    # spoken mention at the next live session (claim_replays only speaks — never re-pushes,
    # so unmarked is always safe for resolved link rows).
    row = _link_row(store)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: False))
    assert st == "notified"
    assert store.get(row["id"])["delivered_at"] is None


def test_link_message_spoken_marks_like_any_result(store, monkeypatch):
    # Once EVE actually SAYS it, the debt is paid — no morning re-mention.
    row = _link_row(store)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "spoken"
    assert store.get(row["id"])["delivered_at"] is not None


def test_link_message_spoken_also_pushes_belt_and_braces(store, monkeypatch):
    # Process-liveness is not owner-presence: a spoken link message may have landed in an
    # empty room (live incident 2026-07-04), so it mirrors to the phone too — unless the
    # owner turns the knob off.
    row = _link_row(store)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "spoken" and ok.await_count == 1

    monkeypatch.setenv("EVE_AGENT_LINK_ALWAYS_PUSH", "0")
    row2 = _link_row(store)
    st = _run(agent_delivery.deliver_update(row2, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "spoken" and ok.await_count == 1        # knob off: no second push


def test_delegation_result_spoken_never_pushes(store, monkeypatch):
    # Belt-and-braces is for unsolicited rows only; a spoken delegation result stays quiet.
    row = _row(store, "resolved")
    notify = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", notify)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "spoken" and notify.await_count == 0


def test_link_blocker_quiet_notify_stays_replayable(store, monkeypatch):
    # Overnight link blocker: push as heads-up, row stays in failed_replays for the morning
    # spoken resurface (the replay watcher is quiet-gated, so no overnight re-push).
    row = _link_row(store, "blocker")
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "notified"
    assert store.get(row["id"])["delivered_at"] is None
    assert row["id"] in [r["id"] for r in store.failed_replays()]


def test_link_blocker_awake_notify_marks_no_push_storm(store, monkeypatch):
    # OUTSIDE quiet hours, failed_replays re-delivers every ~60s — a successful notify must
    # terminate the loop for blockers (unlike messages), or the phone buzzes forever.
    row = _link_row(store, "blocker")
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: False))
    assert st == "notified"
    assert store.get(row["id"])["delivered_at"] is not None


def test_delegation_result_quiet_notify_still_marks(store, monkeypatch):
    # The resurfacing exemption is for UNSOLICITED rows only — real delegation results keep
    # the proven contract (notify == delivered).
    row = _row(store, "resolved")
    monkeypatch.setattr(agent_delivery.delivery_policy, "in_quiet_hours", lambda: True)
    ok = AsyncMock(return_value={"ntfy": True, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", ok)
    st = _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=lambda e: None,
                                            is_alive=lambda: True))
    assert st == "notified" and store.get(row["id"])["delivered_at"] is not None


def test_broadcast_always_fires_even_when_everything_else_fails(store, monkeypatch):
    row = _row(store, "resolved")
    bad = AsyncMock(return_value={"ntfy": False, "telegram": False})
    monkeypatch.setattr(agent_delivery.approval_push, "notify", bad)
    sent = []
    _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=sent.append,
                                       is_alive=lambda: False))
    assert len(sent) == 1


def test_broadcast_payload_carries_task_id_and_status(store):
    # The app's Approvals live feed keys per-task cards on a stable task_id and needs the
    # row status to paint Working / Waiting-on-you / Done / Failed. cid stays for
    # backward compat (older consumers key on it).
    row = _row(store)
    sent = []
    _run(agent_delivery.deliver_update(row, announce=AsyncMock(), broadcast=sent.append,
                                       is_alive=lambda: True,
                                       kind=agent_delivery.AGENT_PROGRESS, text="halfway"))
    evt = sent[0]
    assert evt["type"] == "agent_progress"
    assert evt["task_id"] == row["id"] and evt["cid"] == row["id"]
    assert evt["status"] == row["status"]
    assert evt["agent"] == "hermes"
