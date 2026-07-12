# Tests for agent_tasks — the unified delegate-task firewall store (EVE Agent Hub).
import importlib
import os
import tempfile
import time

import pytest


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


def test_migration_creates_both_tables_on_fresh_db(store):
    # A fresh v0 DB must end up with BOTH skill_feed (v2) AND agent_tasks (v3) — the
    # literal-version regression (a v2 block writing the bumped constant would skip v3).
    from approval_store import _connect
    conn = _connect()
    try:
        names = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
        assert "agent_tasks" in names
        assert "skill_feed" in names
        assert conn.execute("PRAGMA user_version").fetchone()[0] == 5
    finally:
        conn.close()


def test_create_then_direct_resolve_on_pending_row_wins(store):
    cid, tok = store.create("hermes", "say hi", summary="hi", delivery="push",
                            requester="Owner", requester_tier="owner", ttl_s=3600)
    row = store.get(cid)
    assert row["status"] == "pending" and row["callback_token"] == tok
    won = store.resolve(cid)                       # direct push callback on a never-claimed row
    assert won is not None and won["status"] == "resolving"
    store.finish(cid, {"ok": True, "text": "hi there"})
    done = store.get(cid)
    assert done["status"] == "resolved"
    assert done["delivered_at"] is None            # finish never delivers
    assert done["result"] == {"ok": True, "text": "hi there"}


def test_resolve_is_single_winner(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    assert store.resolve(cid) is not None
    assert store.resolve(cid) is None              # second caller = idempotent no-op


def test_resolve_refuses_expired_row(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=0)
    time.sleep(0.01)
    assert store.resolve(cid) is None              # TTL-guarded inside the CAS


def test_resolve_fenced_by_claim_token(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    claimed = store.claim_for("hermes", lease_s=60)
    real = claimed[0]["claim_token"]
    assert store.resolve(cid, claim_token="zombie-stale-token") is None   # fenced out
    assert store.resolve(cid, claim_token=real) is not None               # real lease wins


def test_replay_keys_on_delivered_at_null_only(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True})
    assert [r["id"] for r in store.claim_replays()] == [cid]
    store.mark_delivered(cid)
    assert store.claim_replays() == []             # delivered rows never replay


def test_fail_does_not_clobber_resolved(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "done"})
    store.fail(cid, "late timeout")                # must NOT overwrite a resolved row
    assert store.get(cid)["status"] == "resolved"


def test_claim_for_is_single_winner_and_sets_lease(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    first = store.claim_for("hermes", lease_s=60)
    assert [r["id"] for r in first] == [cid]
    assert first[0]["claim_token"] and first[0]["claimed_until"] > time.time()
    assert store.claim_for("hermes", lease_s=60) == []     # already claimed


def test_claim_for_ignores_push_rows(store):
    store.create("hermes", "t", summary="t", delivery="push",
                 requester="W", requester_tier="owner", ttl_s=3600)
    assert store.claim_for("hermes", lease_s=60) == []     # push is never poller-claimed


def test_reap_requeues_expired_lease(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.claim_for("hermes", lease_s=-1)                  # already-expired lease
    assert store.reap(now=time.time()) == 1
    row = store.get(cid)
    assert row["status"] == "pending" and row["claim_token"] is None
    assert row["claim_count"] == 1


def test_reap_fails_task_past_max_claims(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    for _ in range(3):
        store.claim_for("hermes", lease_s=-1, max_claims=3)
        store.reap(now=time.time(), max_claims=3)
    assert store.get(cid)["status"] == "failed"            # abandoned, not an infinite loop


def test_poller_drives_detached_task_to_resolved(store):
    # Pins the poller's store contract (Task 6): claim with a lease, resolve fenced by the
    # claim_token, finish — the same path the callback uses.
    cid, _ = store.create("hermes", "big job", summary="big", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    claimed = store.claim_for("hermes", lease_s=60)
    assert claimed and claimed[0]["id"] == cid
    won = store.resolve(cid, claim_token=claimed[0]["claim_token"])
    assert won is not None
    store.finish(cid, {"ok": True, "text": "all done"})
    assert store.get(cid)["result"]["text"] == "all done"


def test_list_for_audit_returns_recent(store):
    store.create("hermes", "a", summary="a", delivery="poll",
                 requester="W", requester_tier="owner", ttl_s=3600)
    store.create("hermes", "b", summary="b", delivery="poll",
                 requester="W", requester_tier="owner", ttl_s=3600)
    rows = store.list_for_audit(agent="hermes")
    assert len(rows) == 2 and {r["task"] for r in rows} == {"a", "b"}


def test_set_awaiting_user(store):
    # A2A input_required moves an open row to awaiting_user...
    cid, _ = store.create("hermes", "q", summary="q", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.set_awaiting_user(cid)
    assert store.get(cid)["status"] == "awaiting_user"


def test_set_awaiting_user_never_reopens_resolved(store):
    # ...but a late question can NOT reopen finished work.
    cid, _ = store.create("hermes", "q", summary="q", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "done"})
    store.set_awaiting_user(cid)
    assert store.get(cid)["status"] == "resolved"


# ---- v4: qid-correlated Q&A storage (agent talk-back) ------------------------

def _mk(store, delivery="push"):
    return store.create("hermes", "post standup", summary="post standup", delivery=delivery,
                        requester="W", requester_tier="owner", ttl_s=3600)


def test_v4_columns_and_get_by_token(store):
    cid, tok = _mk(store)
    row = store.get_by_token(tok)
    assert row and row["id"] == cid
    assert store.get_by_token("nope") is None
    assert store.get(cid)["question"] is None and store.get(cid)["answer"] is None


def test_v4_migration_idempotent_and_race_safe(store):
    # _migrate runs on every _connect from three processes; the ALTERs must tolerate a racer
    # having already added the column (spec §7 racing-migration requirement).
    import sqlite3
    from approval_store import _add_column, _connect
    conn = _connect()
    try:
        _add_column(conn, "agent_tasks", "question_json TEXT")   # duplicate: must not raise
        _add_column(conn, "agent_tasks", "answer_json TEXT")
        with pytest.raises(sqlite3.OperationalError):
            _add_column(conn, "nonexistent_table", "x TEXT")     # real errors still surface
    finally:
        conn.close()


def test_awaiting_cas_single_winner_and_question_stored(store):
    cid, _ = _mk(store)
    q = {"qid": "q1", "question": "which channel?", "approval_id": "", "asked_at": 1.0}
    assert store.set_awaiting_user_cas(cid, q) is True
    assert store.set_awaiting_user_cas(cid, {**q, "qid": "q2"}) is False  # already awaiting
    row = store.get(cid)
    assert row["status"] == "awaiting_user" and row["question"]["qid"] == "q1"
    assert store.update_question(cid, "q1", approval_id="ap1") is True
    assert store.get(cid)["question"]["approval_id"] == "ap1"
    assert store.update_question(cid, "WRONG", approval_id="x") is False


def test_awaiting_cas_refuses_terminal_and_resolving(store):
    cid, _ = _mk(store)
    store.resolve(cid)
    # W1: a question racing a terminal finalization must LOSE (RESOLVING excluded), else
    # finish() would silently no-op and the completed result would be destroyed.
    assert store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0}) is False
    store.finish(cid, {"ok": True, "text": "done"})
    assert store.set_awaiting_user_cas(cid, {"qid": "q2", "question": "?", "asked_at": 1.0}) is False
    assert store.get(cid)["status"] == "resolved"
    assert store.get(cid)["result"]["text"] == "done"


def test_revert_awaiting(store):
    cid, _ = _mk(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    assert store.revert_awaiting(cid, "WRONG") is False
    assert store.revert_awaiting(cid, "q1") is True
    row = store.get(cid)
    assert row["status"] == "pending" and row["question"] is None


def test_set_and_take_answer_qid_correlated(store):
    cid, _ = _mk(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    row = store.set_answer(cid, "use standup", extend_s=1800)
    assert row["status"] == "answered" and row["answer"]["qid"] == "q1"
    assert store.take_answer(cid, "WRONG") is None          # qid mismatch never satisfies
    assert store.take_answer(cid, "q1") == "use standup"
    assert store.take_answer(cid, "q1") is None             # single-fire


def test_set_answer_requires_awaiting_and_extends_ttl(store):
    cid, _ = _mk(store)
    assert store.set_answer(cid, "x") is None               # not awaiting
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    before = store.get(cid)["ttl_s"]
    row = store.set_answer(cid, "x", extend_s=99999)
    assert row["ttl_s"] >= 99999 and row["ttl_s"] >= before


def test_list_awaiting(store):
    cid, _ = _mk(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    assert cid in [r["id"] for r in store.list_awaiting()]
    store.set_answer(cid, "x")
    assert cid not in [r["id"] for r in store.list_awaiting()]


# ---- lifecycle correctness: terminal wins over open questions; expiry; reapers ----

def test_terminal_wins_over_unanswered_question(store):
    cid, _ = _mk(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    assert store.resolve(cid) is not None          # was: None (silent loss of the result)
    store.finish(cid, {"ok": True, "text": "done anyway"})
    assert store.get(cid)["status"] == "resolved"


def test_fail_wins_over_answered(store):
    cid, _ = _mk(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    store.set_answer(cid, "x")
    store.fail(cid, "blew up later")
    assert store.get(cid)["status"] == "failed"


def test_question_racing_terminal_finalization_loses(store):
    # W1: input_required between resolve() and finish() must be rejected and the result
    # must persist — RESOLVING is not a valid source for AWAITING_USER.
    cid, _ = _mk(store)
    assert store.resolve(cid) is not None
    assert store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0}) is False
    store.finish(cid, {"ok": True, "text": "the real result"})
    row = store.get(cid)
    assert row["status"] == "resolved" and row["result"]["text"] == "the real result"


def test_awaiting_rows_expire(store, monkeypatch):
    cid, _ = _mk(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "asked_at": 1.0})
    real = time.time
    monkeypatch.setattr(store.time, "time", lambda: real() + 4000)  # past ttl_s=3600
    assert store.get(cid)["effective_status"] == "expired"
    assert store.list_awaiting() == []


def test_failed_replays(store):
    cid, _ = _mk(store)
    store.fail(cid, "no creds")
    assert cid in [r["id"] for r in store.failed_replays()]
    store.mark_delivered(cid)
    assert cid not in [r["id"] for r in store.failed_replays()]


def test_reap_stale_resolving(store):
    cid, _ = _mk(store)
    store.resolve(cid)                              # stuck: crash before finish/fail
    assert store.reap_stale_resolving(max_age_s=0) == 1
    assert store.get(cid)["status"] == "failed"


def test_reap_stale_resolving_leaves_fresh_rows(store):
    cid, _ = _mk(store)
    store.resolve(cid)
    assert store.reap_stale_resolving(max_age_s=600) == 0
    assert store.get(cid)["status"] == "resolving"


# ---- Cancel (live-delegation-approvals: owner stops a running delegated task) ----

def test_request_cancel_on_unstarted_poll_row_cancels_outright(store):
    # A poll row still PENDING has never been claimed — nothing is running, so cancel is
    # immediate and terminal (and delivered: the owner caused it, there is nothing to replay).
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    assert store.request_cancel(cid) == store.CANCELLED
    row = store.get(cid)
    assert row["status"] == store.CANCELLED and row["delivered_at"] is not None


def test_request_cancel_on_running_push_row_is_cooperative(store):
    # A push row is already in the agent's hands — cancel is a REQUEST the agent honors at
    # its next check-in; the row is not terminal until then (no lie that it's dead).
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    assert store.request_cancel(cid) == store.CANCEL_REQUESTED
    assert store.get(cid)["status"] == store.CANCEL_REQUESTED


def test_request_cancel_covers_awaiting_user(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    assert store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "which env?"})
    assert store.request_cancel(cid) == store.CANCEL_REQUESTED


def test_request_cancel_is_noop_on_terminal_rows(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "done"})
    assert store.request_cancel(cid) is None
    assert store.get(cid)["status"] == store.RESOLVED


def test_request_cancel_loses_to_inflight_resolving(store):
    # RESOLVING means the terminal result is landing RIGHT NOW — cancel must lose that race,
    # never orphan a resolving row.
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    assert store.request_cancel(cid) is None


def test_finalize_cancel_terminalizes_only_cancel_requested(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.request_cancel(cid)
    row = store.finalize_cancel(cid)
    assert row is not None and row["status"] == store.CANCELLED
    assert store.get(cid)["delivered_at"] is not None
    assert store.finalize_cancel(cid) is None      # idempotent: second call is a no-op


def test_cancelled_rows_never_replay(store):
    # Neither the failed-replay loop nor claim_replays may resurface a cancelled task.
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.request_cancel(cid)
    store.finalize_cancel(cid)
    assert cid not in [r["id"] for r in store.failed_replays()]
    assert cid not in [r["id"] for r in store.claim_replays()]


def test_cancel_requested_row_expires_at_ttl(store):
    # An agent that never checks in must not leave an immortal cancel_requested row.
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=1)
    store.request_cancel(cid)
    time.sleep(1.1)
    assert store.get(cid)["effective_status"] == "expired"


def test_poller_never_claims_a_cancel_requested_row(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="poll",
                          requester="W", requester_tier="owner", ttl_s=3600)
    # Force the cooperative state onto a poll row (as if cancel raced the claim), then claim.
    from approval_store import _connect
    conn = _connect()
    conn.execute("UPDATE agent_tasks SET status=? WHERE id=?", (store.CANCEL_REQUESTED, cid))
    conn.commit()
    conn.close()
    assert all(r["id"] != cid for r in store.claim_for("hermes", lease_s=60))


# ---- Redirect (live-delegation-approvals: owner steers a running task) ----

def test_set_redirect_on_running_row_and_take_once(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    assert store.set_redirect(cid, "focus on the pricing page only") is True
    assert store.get(cid)["redirect"] == "focus on the pricing page only"
    assert store.take_redirect(cid) == "focus on the pricing page only"
    assert store.take_redirect(cid) is None            # single-fire
    assert store.get(cid)["redirect"] is None


def test_set_redirect_latest_wins(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.set_redirect(cid, "first steer")
    store.set_redirect(cid, "second steer")
    assert store.take_redirect(cid) == "second steer"


def test_set_redirect_refused_on_terminal_and_cancelling_rows(store):
    cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                          requester="W", requester_tier="owner", ttl_s=3600)
    store.request_cancel(cid)                          # cancel outranks steer
    assert store.set_redirect(cid, "x") is False
    cid2, _ = store.create("hermes", "t", summary="t", delivery="push",
                           requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid2)
    store.finish(cid2, {"ok": True, "text": "done"})
    assert store.set_redirect(cid2, "x") is False
