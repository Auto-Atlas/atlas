# agent_tasks.py
#
# The unified firewall store for delegated agent work (EVE Agent Hub spec §4.1, §11.A).
# ONE table in approvals.db carries a delegated unit of work across TWO lifecycle axes:
#   status   = work lifecycle; delivery = push|poll (only poll rows are claim/reapable by
#              EVE's poller). delivered_at IS NULL on a resolved row == "still needs speaking".
# Mirrors approval_store/skill_feed: WAL, busy_timeout, fresh per-call connection, atomic
# single-winner CAS, wall-clock TTL (survives restart), read-computed expiry.
#
# Import invariant: stdlib + approval_store ONLY. NEVER jarvis_core/bot/phone_bot/tool_policy
# — that would pull the voice runtime into the approval_api / webhook process.
#
import json
import secrets
import time
import uuid

from approval_store import _connect

PENDING = "pending"
CLAIMED = "claimed"
RESOLVING = "resolving"
RESOLVED = "resolved"
FAILED = "failed"
AWAITING_USER = "awaiting_user"   # blocked-agent question outstanding (spec §11.L; non-reaping)
ANSWERED = "answered"             # owner answered; the agent's ask-poll takes it (talk-back §4.4)
CANCEL_REQUESTED = "cancel_requested"  # owner cancelled; agent told to stop at next check-in
CANCELLED = "cancelled"           # terminal: the stop was observed (or nothing ever started)


def _row_to_dict(row, now: float) -> dict:
    expires_at = row["created_at"] + row["ttl_s"]
    seconds_left = max(0.0, expires_at - now)
    status = row["status"]
    # Compute (never persist) expiry for still-open rows whose TTL passed — a SELECT never
    # takes the write lock, so a real create() can't lose to a read (approval_store rule).
    # AWAITING_USER/ANSWERED expire too: an adapter crash mid-question must not leave an
    # immortal row that resume_delegate would pretend to answer (talk-back §4.4).
    effective = "expired" if (status in (PENDING, AWAITING_USER, ANSWERED, CANCEL_REQUESTED)
                              and seconds_left <= 0) else status
    return {
        "id": row["id"], "agent": row["agent"], "task": row["task"],
        "summary": row["summary"], "callback_token": row["callback_token"],
        "delivery": row["delivery"], "status": status, "effective_status": effective,
        "claim_token": row["claim_token"], "claimed_at": row["claimed_at"],
        "claimed_until": row["claimed_until"], "claim_count": row["claim_count"],
        "requester": row["requester"], "requester_tier": row["requester_tier"],
        "created_at": row["created_at"], "ttl_s": row["ttl_s"],
        "resolved_at": row["resolved_at"], "delivered_at": row["delivered_at"],
        "result": json.loads(row["result_json"]) if row["result_json"] else None,
        "question": json.loads(row["question_json"]) if row["question_json"] else None,
        "answer": json.loads(row["answer_json"]) if row["answer_json"] else None,
        "redirect": json.loads(row["redirect_json"]) if row["redirect_json"] else None,
        "trace_id": row["trace_id"], "depth": row["depth"],
        "seconds_left": seconds_left,
    }


def create(agent, task, *, summary, delivery, requester, requester_tier,
           ttl_s, trace_id="", depth=0):
    """Mint a delegated task. Returns (correlation_id, callback_token). The callback_token is
    the capability a callback must echo to resolve THIS request (confused-deputy block)."""
    cid = uuid.uuid4().hex
    token = secrets.token_urlsafe(32)
    now = time.time()
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO agent_tasks (id, agent, task, summary, callback_token, delivery, "
            "status, claim_count, requester, requester_tier, created_at, ttl_s, trace_id, depth) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (cid, agent, task, summary, token, delivery, PENDING, 0, requester,
             requester_tier, now, int(ttl_s), trace_id, int(depth)),
        )
        conn.commit()
    finally:
        conn.close()
    return cid, token


def get(cid):
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM agent_tasks WHERE id=?", (cid,)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(row, time.time()) if row else None


def resolve(cid, *, claim_token=None):
    """Atomic single-fire: pending|claimed|awaiting_user|answered -> resolving, TTL-guarded,
    lease-fenced. Accepts PENDING so a direct push callback on a never-claimed row wins (spec
    §11.A — else silent loss masquerading as idempotency); accepts AWAITING_USER/ANSWERED so a
    run that finished after an (un)answered question still wins (talk-back §4.4 — else the
    terminal push is dropped as 'already resolved'). When claim_token is given it fences a
    reaped zombie poller (a callback/poller whose lease was requeued cannot resolve)."""
    now = time.time()
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, resolved_at=? "
            "WHERE id=? AND status IN (?, ?, ?, ?) AND (created_at + ttl_s) > ? "
            "AND (? IS NULL OR claim_token IS NULL OR claim_token = ?)",
            (RESOLVING, now, cid, PENDING, CLAIMED, AWAITING_USER, ANSWERED, now,
             claim_token, claim_token),
        )
        conn.commit()
        if cur.rowcount != 1:
            return None
        row = conn.execute("SELECT * FROM agent_tasks WHERE id=?", (cid,)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(row, now) if row else None


def finish(cid, result):
    """resolving -> resolved, recording the result. NEVER sets delivered_at (spec §11.C):
    only a SPOKEN announce marks delivery, so a result that came home to a dead session
    stays visible to session-start replay. A rowcount of 0 means someone stole the RESOLVING
    row out from under the finalizer — that is silent result loss, so it logs loudly (W1)."""
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, result_json=? WHERE id=? AND status=?",
            (RESOLVED, json.dumps(result), cid, RESOLVING),
        )
        conn.commit()
        if cur.rowcount != 1:
            import logging  # stdlib-only module: no loguru here (import invariant)
            logging.getLogger(__name__).error(
                "agent_tasks.finish lost cid=%s: row was not RESOLVING — "
                "the result was NOT persisted", cid)
    finally:
        conn.close()


def fail(cid, error):
    """-> failed, but never overwrite a row that already resolved (callback won the race).
    Accepts AWAITING_USER/ANSWERED: a run that failed after an (un)answered question must
    still surface as a blocker (talk-back §4.4)."""
    conn = _connect()
    try:
        conn.execute(
            "UPDATE agent_tasks SET status=?, result_json=? "
            "WHERE id=? AND status IN (?, ?, ?, ?, ?)",
            (FAILED, json.dumps({"ok": False, "error": str(error)}), cid,
             PENDING, CLAIMED, RESOLVING, AWAITING_USER, ANSWERED),
        )
        conn.commit()
    finally:
        conn.close()


def set_awaiting_user(cid):
    """-> awaiting_user: a delegate asked the owner a question mid-task (A2A input_required).
    Only a still-open row may enter this state; never overwrite a resolved/failed row (a late
    question can't reopen finished work). Non-reaping, like the state's spec intent (§11.L)."""
    conn = _connect()
    try:
        conn.execute(
            "UPDATE agent_tasks SET status=? WHERE id=? AND status IN (?, ?, ?)",
            (AWAITING_USER, cid, PENDING, CLAIMED, RESOLVING),
        )
        conn.commit()
    finally:
        conn.close()


def get_by_token(token):
    """Row lookup for native-A2A push correlation (indexed): the a2a server mints its own task
    id, so a push can only be matched by the per-task token it carries. The caller MUST still
    constant-time compare the returned row's callback_token against the presented one."""
    if not token:
        return None
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM agent_tasks WHERE callback_token=?",
                           (str(token),)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(row, time.time()) if row else None


def set_awaiting_user_cas(cid, question):
    """Single-winner: open row -> AWAITING_USER, storing the question atomically. CAS-FIRST so
    a duplicate/post-terminal input_required can never stage a phantom approval (talk-back
    §4.2). RESOLVING is deliberately EXCLUDED (deviation from talk-back spec §4.4, per plan
    review W1): a question racing a terminal finalization must LOSE, else finish()'s
    status=RESOLVING guard silently no-ops and the completed result is destroyed."""
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, question_json=?, answer_json=NULL "
            "WHERE id=? AND status IN (?,?,?)",
            (AWAITING_USER, json.dumps(question), cid, PENDING, CLAIMED, ANSWERED))
        conn.commit()
        return cur.rowcount == 1
    finally:
        conn.close()


def update_question(cid, qid, **fields):
    """Patch the stored question (e.g. approval_id after staging) guarded by qid."""
    conn = _connect()
    try:
        row = conn.execute("SELECT question_json FROM agent_tasks WHERE id=?", (cid,)).fetchone()
        if not row or not row["question_json"]:
            return False
        q = json.loads(row["question_json"])
        if q.get("qid") != qid:
            return False
        q.update(fields)
        cur = conn.execute("UPDATE agent_tasks SET question_json=? WHERE id=? AND status=?",
                           (json.dumps(q), cid, AWAITING_USER))
        conn.commit()
        return cur.rowcount == 1
    finally:
        conn.close()


def revert_awaiting(cid, qid):
    """Fail-closed undo when staging the approval failed: AWAITING_USER -> PENDING, question
    cleared — guarded by qid so a racing newer question is never clobbered."""
    conn = _connect()
    try:
        row = conn.execute("SELECT question_json FROM agent_tasks WHERE id=?", (cid,)).fetchone()
        if not row or not row["question_json"]:
            return False
        if json.loads(row["question_json"]).get("qid") != qid:
            return False
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, question_json=NULL WHERE id=? AND status=?",
            (PENDING, cid, AWAITING_USER))
        conn.commit()
        return cur.rowcount == 1
    finally:
        conn.close()


def set_answer(cid, answer, *, extend_s=1800):
    """AWAITING_USER -> ANSWERED, storing {qid, answer}; extends ttl_s so a completion that
    lands after a slow human answer still passes resolve()'s TTL guard. Returns the fresh row
    (the caller closes the linked approval) or None if the row wasn't awaiting."""
    now = time.time()
    conn = _connect()
    try:
        row = conn.execute("SELECT question_json, created_at, ttl_s FROM agent_tasks "
                           "WHERE id=? AND status=?", (cid, AWAITING_USER)).fetchone()
        if not row or not row["question_json"]:
            return None
        qid = json.loads(row["question_json"]).get("qid")
        new_ttl = max(int(row["ttl_s"]), int(now - row["created_at"] + extend_s))
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, answer_json=?, ttl_s=? WHERE id=? AND status=?",
            (ANSWERED, json.dumps({"qid": qid, "answer": str(answer)}), new_ttl, cid,
             AWAITING_USER))
        conn.commit()
        if cur.rowcount != 1:
            return None
        fresh = conn.execute("SELECT * FROM agent_tasks WHERE id=?", (cid,)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(fresh, now) if fresh else None


def take_answer(cid, qid):
    """Atomic single-fire, qid-correlated: a late answer to question 1 can never satisfy
    question 2 (talk-back §4.4). Returns the answer string exactly once, else None."""
    conn = _connect()
    try:
        row = conn.execute("SELECT answer_json FROM agent_tasks WHERE id=?", (cid,)).fetchone()
        if not row or not row["answer_json"]:
            return None
        a = json.loads(row["answer_json"])
        if a.get("qid") != qid:
            return None
        cur = conn.execute(
            "UPDATE agent_tasks SET answer_json=NULL WHERE id=? AND answer_json=?",
            (cid, row["answer_json"]))
        conn.commit()
        return a.get("answer") if cur.rowcount == 1 else None
    finally:
        conn.close()


def list_awaiting():
    """Unanswered, TTL-live questions — for session replay + the voice lookup ('answer hermes:
    ...' — never a cid in the owner's mouth)."""
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM agent_tasks WHERE status=? AND answer_json IS NULL "
            "AND (created_at + ttl_s) > ? ORDER BY created_at ASC",
            (AWAITING_USER, now)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def set_redirect(cid, instructions):
    """Stage an owner steer for a running task; delivered single-fire at the agent's next
    talk-back check-in (a2a_fabric.handle_push / the /answer poll). Latest wins. Refused on
    terminal rows and on cancel_requested (cancel outranks steer). Returns bool."""
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET redirect_json=? WHERE id=? AND status IN (?, ?, ?, ?) "
            "AND (created_at + ttl_s) > ?",
            (json.dumps(str(instructions)), cid, PENDING, CLAIMED, AWAITING_USER, ANSWERED,
             time.time()))
        conn.commit()
        return cur.rowcount == 1
    finally:
        conn.close()


def take_redirect(cid):
    """Atomically consume the pending steer (single-fire: read-and-clear guarded by the
    exact value read, so a racing set_redirect is never lost). Returns the instructions
    or None."""
    conn = _connect()
    try:
        row = conn.execute("SELECT redirect_json FROM agent_tasks WHERE id=?",
                           (cid,)).fetchone()
        if not row or not row["redirect_json"]:
            return None
        cur = conn.execute(
            "UPDATE agent_tasks SET redirect_json=NULL WHERE id=? AND redirect_json=?",
            (cid, row["redirect_json"]))
        conn.commit()
        if cur.rowcount != 1:
            return None                       # a newer steer landed mid-take; next check-in gets it
        return json.loads(row["redirect_json"])
    finally:
        conn.close()


def list_active():
    """Non-terminal, TTL-live rows — the app's live 'Agent Activity' section (delegations
    currently in an agent's hands, waiting on the owner, or being cancelled)."""
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM agent_tasks WHERE status IN (?, ?, ?, ?, ?, ?) "
            "AND (created_at + ttl_s) > ? ORDER BY created_at DESC",
            (PENDING, CLAIMED, RESOLVING, AWAITING_USER, ANSWERED, CANCEL_REQUESTED,
             now)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def list_recent(limit=20):
    """Most recent rows regardless of status — the app's recent-history tail (Done / Failed
    / Cancelled cards below the live ones)."""
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM agent_tasks ORDER BY created_at DESC LIMIT ?",
            (int(limit),)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def mark_delivered(cid):
    """Set delivered_at — called ONLY after a SPOKEN announce (spec §11.C)."""
    conn = _connect()
    try:
        conn.execute("UPDATE agent_tasks SET delivered_at=? WHERE id=?", (time.time(), cid))
        conn.commit()
    finally:
        conn.close()


def request_cancel(cid):
    """Owner cancels a delegated task (live-delegation-approvals). An unstarted poll row
    (still PENDING — the poller claims only PENDING, so nothing is running) goes straight to
    terminal CANCELLED and delivered (the owner caused it; there is nothing to replay).
    Anything already in an agent's hands becomes CANCEL_REQUESTED — a cooperative signal the
    agent takes at its next check-in; finalize_cancel() terminalizes when the stop is
    actually observed. RESOLVING loses the race on purpose: the terminal result is landing
    right now. Returns the new status, or None (terminal / absent / mid-resolve)."""
    now = time.time()
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, resolved_at=?, delivered_at=?, result_json=? "
            "WHERE id=? AND status=? AND delivery='poll'",
            (CANCELLED, now, now,
             json.dumps({"ok": False, "cancelled": True, "text": "cancelled by owner"}),
             cid, PENDING))
        if cur.rowcount == 1:
            conn.commit()
            return CANCELLED
        cur = conn.execute(
            "UPDATE agent_tasks SET status=? WHERE id=? AND status IN (?, ?, ?, ?)",
            (CANCEL_REQUESTED, cid, PENDING, CLAIMED, AWAITING_USER, ANSWERED))
        conn.commit()
        return CANCEL_REQUESTED if cur.rowcount == 1 else None
    finally:
        conn.close()


def finalize_cancel(cid):
    """cancel_requested -> cancelled (terminal + delivered), recording the audit result.
    Called when the stop is observed: the agent's next check-in acknowledged the cancel, or
    its terminal push landed on a cancel-requested row. Returns the row, or None if the row
    wasn't cancel_requested (idempotent)."""
    now = time.time()
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, resolved_at=?, delivered_at=?, result_json=? "
            "WHERE id=? AND status=?",
            (CANCELLED, now, now,
             json.dumps({"ok": False, "cancelled": True, "text": "cancelled by owner"}),
             cid, CANCEL_REQUESTED))
        conn.commit()
        if cur.rowcount != 1:
            return None
        row = conn.execute("SELECT * FROM agent_tasks WHERE id=?", (cid,)).fetchone()
    finally:
        conn.close()
    return _row_to_dict(row, now) if row else None


def claim_for(agent, lease_s, *, max_claims=3):
    """Token-CAS lease claim of poll-delivery PENDING rows for `agent` (spec §11.A). Mirrors
    skill_feed._claim's single-winner pattern (stamp a token, then SELECT by it), adding a
    claimed_until lease so a crashed claimant can be reaped. claim_count is the attempt
    counter; a row at max_claims is no longer claimable (reap fails it)."""
    token = uuid.uuid4().hex
    now = time.time()
    conn = _connect()
    try:
        conn.execute(
            "UPDATE agent_tasks SET status=?, claim_token=?, claimed_at=?, claimed_until=?, "
            "claim_count=claim_count+1 WHERE status=? AND agent=? AND delivery='poll' "
            "AND (created_at + ttl_s) > ? AND claim_count < ?",
            (CLAIMED, token, now, now + lease_s, PENDING, agent, now, int(max_claims)),
        )
        conn.commit()
        rows = conn.execute("SELECT * FROM agent_tasks WHERE claim_token=?", (token,)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def reap(now=None, *, max_claims=3):
    """Requeue poll rows whose lease expired (agent died mid-claim): clear ALL lease columns;
    at/over max_claims -> failed (no infinite silent requeue). Returns rows transitioned."""
    now = time.time() if now is None else now
    conn = _connect()
    try:
        dead = conn.execute(
            "SELECT id, claim_count FROM agent_tasks WHERE status=? AND delivery='poll' "
            "AND claimed_until IS NOT NULL AND claimed_until < ?", (CLAIMED, now)).fetchall()
        changed = 0
        for r in dead:
            if r["claim_count"] >= max_claims:
                cur = conn.execute(
                    "UPDATE agent_tasks SET status=?, result_json=?, claim_token=NULL, "
                    "claimed_at=NULL, claimed_until=NULL WHERE id=? AND status=?",
                    (FAILED, json.dumps({"ok": False, "error": "abandoned"}), r["id"], CLAIMED))
            else:
                cur = conn.execute(
                    "UPDATE agent_tasks SET status=?, claim_token=NULL, claimed_at=NULL, "
                    "claimed_until=NULL WHERE id=? AND status=?", (PENDING, r["id"], CLAIMED))
            changed += cur.rowcount
        conn.commit()
        return changed
    finally:
        conn.close()


def claim_replays():
    """Resolved rows not yet spoken — oldest first, for session-start replay (spec §11.C).
    Keyed on delivered_at IS NULL ALONE (not a claim-mutated status), so a crashed replay
    re-surfaces rather than silently dropping."""
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM agent_tasks WHERE status=? AND delivered_at IS NULL "
            "ORDER BY resolved_at ASC", (RESOLVED,)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def failed_replays():
    """Failed rows never spoken — blockers must survive a dead session for session-start
    replay exactly like results (talk-back §4.3); before this they were lost forever."""
    now = time.time()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM agent_tasks WHERE status=? AND delivered_at IS NULL "
            "ORDER BY created_at ASC", (FAILED,)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]


def reap_stale_resolving(max_age_s=600):
    """Push rows stuck RESOLVING (crash between resolve() and finish/fail) are invisible to
    replay and un-resolvable (resolve() won't re-fire); fail them honestly after max_age_s."""
    now = time.time()
    conn = _connect()
    try:
        cur = conn.execute(
            "UPDATE agent_tasks SET status=?, result_json=? "
            "WHERE status=? AND delivery='push' AND resolved_at IS NOT NULL AND resolved_at < ?",
            (FAILED, json.dumps({"ok": False, "error": "lost mid-resolve (process crash)"}),
             RESOLVING, now - max_age_s))
        conn.commit()
        return cur.rowcount
    finally:
        conn.close()


def list_for_audit(agent=None, limit=50):
    """The queryable audit trail ('did that email go out?')."""
    now = time.time()
    conn = _connect()
    try:
        if agent:
            rows = conn.execute(
                "SELECT * FROM agent_tasks WHERE agent=? ORDER BY created_at DESC LIMIT ?",
                (agent, int(limit))).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM agent_tasks ORDER BY created_at DESC LIMIT ?",
                (int(limit),)).fetchall()
    finally:
        conn.close()
    return [_row_to_dict(r, now) for r in rows]
