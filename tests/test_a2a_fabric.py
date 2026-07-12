# Tests for the A2A two-way comms fabric (Hermes). CI-safe: fake delegate runner + the SDK's
# in-memory stores. Never a live Hermes / network / inbox.
import asyncio
import importlib
import importlib.util
import json
import os
import pathlib
import tempfile
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

import a2a_fabric


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


def _recorder():
    """The injected deliver seam: records every (row, kind, text) handle_push emits."""
    calls = []

    async def deliver(row, kind=None, text=None):
        calls.append({"row": row, "kind": kind, "text": text})
    return deliver, calls


def test_a2a_sdk_api_present():
    # Load the CI verifier by path (scripts/ is not an importable package).
    p = pathlib.Path(__file__).resolve().parent.parent / "scripts" / "verify_a2a_sdk.py"
    spec = importlib.util.spec_from_file_location("verify_a2a_sdk", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    assert mod.verify() is True


def _ctx(task_text):
    from a2a.types import a2a_pb2 as pb
    ctx = MagicMock()
    ctx.task_id = "t1"
    ctx.context_id = "c1"
    ctx.current_task = None
    # a real proto Message: new_task_from_user_message validates role + text parts
    ctx.message = pb.Message(message_id="m1", task_id="t1", context_id="c1",
                             role=pb.Role.ROLE_USER, parts=[pb.Part(text=task_text)])
    ctx.get_user_input.return_value = task_text
    return ctx


def _async_updater():
    u = MagicMock()
    u.start_work = AsyncMock()
    u.complete = AsyncMock()
    u.failed = AsyncMock()
    u.requires_input = AsyncMock()
    u.new_agent_message = MagicMock(return_value=object())
    return u


def _eq():
    eq = MagicMock()
    eq.enqueue_event = AsyncMock()
    return eq


def test_adapter_emits_completed_on_success(monkeypatch):
    updater = _async_updater()
    monkeypatch.setattr(a2a_fabric, "_updater_factory", lambda eq, tid, cid: updater)
    ex = a2a_fabric.HermesAdapterExecutor(
        runner=AsyncMock(return_value={"ok": True, "text": "posted to telegram"}))
    asyncio.run(ex.execute(_ctx("post standup"), _eq()))
    updater.start_work.assert_awaited_once()
    updater.complete.assert_awaited_once()
    updater.failed.assert_not_called()


def test_adapter_emits_failed_on_blocker(monkeypatch):
    updater = _async_updater()
    monkeypatch.setattr(a2a_fabric, "_updater_factory", lambda eq, tid, cid: updater)
    ex = a2a_fabric.HermesAdapterExecutor(
        runner=AsyncMock(return_value={"ok": False, "text": "blocked: no creds"}))
    asyncio.run(ex.execute(_ctx("post standup"), _eq()))
    updater.failed.assert_awaited_once()
    updater.complete.assert_not_called()


def test_adapter_failed_on_runner_crash(monkeypatch):
    updater = _async_updater()
    monkeypatch.setattr(a2a_fabric, "_updater_factory", lambda eq, tid, cid: updater)
    ex = a2a_fabric.HermesAdapterExecutor(runner=AsyncMock(side_effect=RuntimeError("boom")))
    asyncio.run(ex.execute(_ctx("x"), _eq()))
    updater.failed.assert_awaited_once()


# ---- inbound bridge: handle_push (EVE shape) ---------------------------------

def _mint(store):
    return store.create("hermes", "post standup", summary="post standup", delivery="push",
                        requester="Owner", requester_tier="owner", ttl_s=3600)


def test_push_completed_delivers(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    r = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "completed",
         "result": {"text": "posted"}}, deliver=deliver))
    assert r["ok"] is True
    assert len(calls) == 1 and calls[0]["kind"] == "agent_result"
    assert store.get(cid)["status"] == "resolved"


def test_push_failed_delivers_blocker(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    r = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "failed",
         "result": {"text": "blocked: no creds"}}, deliver=deliver))
    assert r.get("failed") is True
    assert calls[0]["kind"] == "agent_blocker"
    assert store.get(cid)["status"] == "failed"


def test_push_bad_hmac_rejected(store):
    cid, _ = _mint(store)
    deliver, _ = _recorder()
    r = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": "WRONG", "state": "completed"},
        deliver=deliver))
    assert r.get("ok") is False and r.get("error") == "forbidden"


def test_push_unknown_cid_rejected(store):
    deliver, _ = _recorder()
    r = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": "nope", "callback_token": "x", "state": "completed"},
        deliver=deliver))
    assert r.get("ok") is False and r.get("error") == "unknown"


def test_push_duplicate_is_idempotent(store):
    cid, tok = _mint(store)
    deliver, _ = _recorder()
    payload = {"correlation_id": cid, "callback_token": tok, "state": "completed",
               "result": {"text": "done"}}
    asyncio.run(a2a_fabric.handle_push(payload, deliver=deliver))
    r2 = asyncio.run(a2a_fabric.handle_push(payload, deliver=deliver))
    assert r2.get("ok") is True and r2.get("note") == "already resolved"


# ---- native A2A payloads: whitelist normalizer, token-header correlation -----

def test_native_submitted_is_ignored_then_completed_resolves(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    sub = {"task": {"id": "srv-1", "status": {"state": "TASK_STATE_SUBMITTED"}}}
    r1 = asyncio.run(a2a_fabric.handle_push(sub, deliver=deliver,
                                            headers={"X-A2A-Notification-Token": tok}))
    assert r1["ok"] is True and store.get(cid)["status"] == "pending"   # NOT consumed
    done = {"statusUpdate": {"taskId": "srv-1", "status": {"state": "TASK_STATE_COMPLETED",
            "message": {"parts": [{"text": "posted to standup"}]}}}}
    r2 = asyncio.run(a2a_fabric.handle_push(done, deliver=deliver,
                                            headers={"X-A2A-Notification-Token": tok}))
    assert r2["ok"] is True
    row = store.get(cid)
    assert row["status"] == "resolved" and "posted to standup" in row["result"]["text"]


def test_native_bad_token_forbidden(store):
    _mint(store)
    deliver, _ = _recorder()
    done = {"statusUpdate": {"taskId": "x", "status": {"state": "TASK_STATE_COMPLETED"}}}
    r = asyncio.run(a2a_fabric.handle_push(done, deliver=deliver,
                                           headers={"X-A2A-Notification-Token": "WRONG"}))
    assert r["ok"] is False


def test_native_working_delivers_progress_no_state_change(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    upd = {"statusUpdate": {"taskId": "srv-1", "status": {"state": "TASK_STATE_WORKING",
           "message": {"parts": [{"text": "drafting"}]}}}}
    asyncio.run(a2a_fabric.handle_push(upd, deliver=deliver,
                                       headers={"X-A2A-Notification-Token": tok}))
    assert store.get(cid)["status"] == "pending"
    assert calls and calls[0]["kind"] == "agent_progress" and "drafting" in calls[0]["text"]


def test_native_empty_working_is_silent(store):
    # The adapter's bare start_work() emits WORKING with no message — lifecycle noise, not a
    # progress update worth speaking.
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    upd = {"statusUpdate": {"taskId": "srv-1", "status": {"state": "TASK_STATE_WORKING"}}}
    r = asyncio.run(a2a_fabric.handle_push(upd, deliver=deliver,
                                           headers={"X-A2A-Notification-Token": tok}))
    assert r["ok"] is True and not calls
    assert store.get(cid)["status"] == "pending"


def test_native_unknown_state_never_resolves(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    weird = {"statusUpdate": {"taskId": "srv-1",
                              "status": {"state": "TASK_STATE_AUTH_REQUIRED"}}}
    r = asyncio.run(a2a_fabric.handle_push(weird, deliver=deliver,
                                           headers={"X-A2A-Notification-Token": tok}))
    assert r["ok"] is True and store.get(cid)["status"] == "pending" and not calls


def test_native_input_required_stores_remote_task_id(store):
    cid, tok = _mint(store)
    deliver, _ = _recorder()
    ask = {"statusUpdate": {"taskId": "srv-9", "status": {"state": "TASK_STATE_INPUT_REQUIRED",
           "message": {"parts": [{"text": "which channel?"}]}}}}
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1"):
        r = asyncio.run(a2a_fabric.handle_push(ask, deliver=deliver,
                                               headers={"X-A2A-Notification-Token": tok}))
    assert r["staged"] is True
    q = store.get(cid)["question"]
    assert q["remote_task_id"] == "srv-9" and q["question"] == "which channel?"


# ---- non-terminal MCP notify kinds + progress cooldown -----------------------

def test_eve_shape_nonterminal_notify_kinds(store, monkeypatch):
    monkeypatch.setattr(a2a_fabric, "_PROGRESS_COOLDOWN_S", 0.0)
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    for kind in ("progress", "result", "blocker"):
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "working",
             "kind": kind, "result": {"text": f"{kind} text"}}, deliver=deliver))
        assert r["ok"] is True
    assert store.get(cid)["status"] == "pending"            # none consumed the row
    assert len(calls) == 3 and all(c["kind"] == "agent_progress" for c in calls)
    assert "interim result: result text" in calls[1]["text"]
    assert "hit a snag (still working): blocker text" in calls[2]["text"]


def test_progress_cooldown(store, monkeypatch):
    monkeypatch.setattr(a2a_fabric, "_PROGRESS_COOLDOWN_S", 300.0)
    cid, tok = _mint(store)
    a2a_fabric._last_progress.pop(cid, None)
    deliver, calls = _recorder()
    p = {"correlation_id": cid, "callback_token": tok, "state": "working",
         "kind": "progress", "result": {"text": "x"}}
    asyncio.run(a2a_fabric.handle_push(p, deliver=deliver))
    asyncio.run(a2a_fabric.handle_push(p, deliver=deliver))   # within cooldown
    assert len(calls) == 1


# ---- questions: CAS-first, qid nonce, dedupe, approval lifecycle -------------

def test_question_stages_gated_with_qid_and_awaiting(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1") as stage:
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "which channel?"}, deliver=deliver))
    assert r["staged"] is True and r["question_id"]
    row = store.get(cid)
    assert row["status"] == "awaiting_user"
    assert row["question"]["approval_id"] == "ap-1"
    stage.assert_called_once()
    # W9: the card must never be releasable by a lower tier — pinned to owner.
    assert stage.call_args.kwargs["tier"] == "owner"
    assert calls and calls[0]["kind"] == "agent_question"


def test_question_identical_reask_dedupes(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    p = {"correlation_id": cid, "callback_token": tok, "state": "input_required",
         "question": "which channel?"}
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1") as stage:
        r1 = asyncio.run(a2a_fabric.handle_push(p, deliver=deliver))
        r2 = asyncio.run(a2a_fabric.handle_push(p, deliver=deliver))
    assert r1["question_id"] == r2["question_id"]
    assert stage.call_count == 1 and len(calls) == 1


def test_question_reask_after_answer_returns_same_qid_and_keeps_answer(store):
    # W3: staged -> the owner answers -> hermes's MCP client retries the identical ask.
    # The retry must return the ORIGINAL qid and must NOT destroy the stored answer.
    cid, tok = _mint(store)
    deliver, _ = _recorder()
    p = {"correlation_id": cid, "callback_token": tok, "state": "input_required",
         "question": "which channel?"}
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1") as stage:
        r1 = asyncio.run(a2a_fabric.handle_push(p, deliver=deliver))
        assert store.set_answer(cid, "use standup") is not None
        r2 = asyncio.run(a2a_fabric.handle_push(p, deliver=deliver))
    assert r2["question_id"] == r1["question_id"]
    assert stage.call_count == 1
    assert store.take_answer(cid, r1["question_id"]) == "use standup"


def test_question_after_terminal_noops(store):
    cid, tok = _mint(store)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "done"})
    deliver, _ = _recorder()
    with patch("a2a_fabric.approval_store.stage") as stage:
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "?"}, deliver=deliver))
    stage.assert_not_called()
    assert r.get("staged") is not True


def test_question_stage_failure_reverts_row(store):
    cid, tok = _mint(store)
    deliver, _ = _recorder()
    with patch("a2a_fabric.approval_store.stage", side_effect=RuntimeError("db locked")):
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "?"}, deliver=deliver))
    assert r["ok"] is False and store.get(cid)["status"] == "pending"


def test_terminal_closes_unanswered_question_approval(store):
    cid, tok = _mint(store)
    deliver, _ = _recorder()
    with patch("a2a_fabric.approval_store.stage", return_value="ap-9"):
        asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "?"}, deliver=deliver))
    with patch("a2a_fabric.approval_store.deny") as deny:
        asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "completed",
             "result": {"text": "done anyway"}}, deliver=deliver))
        deny.assert_called_once_with("ap-9")
    assert store.get(cid)["status"] == "resolved"


def test_token_scrubbed_from_delivered_and_persisted_text(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "failed",
         "result": {"text": f"error invoking hermes -z with token {tok} oops"}},
        deliver=deliver))
    row = store.get(cid)
    assert tok not in (row["result"]["error"] or "")
    assert all(tok not in (c["text"] or "") for c in calls)


# ---- inbound routes: push + answer endpoint ----------------------------------

def test_add_inbound_route_delivers_to_handle_push(store):
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer

    cid, tok = _mint(store)
    deliver, _ = _recorder()
    app = web.Application()
    a2a_fabric.add_inbound_route(app, "doortoken", deliver=deliver)

    async def scenario():
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            ok = await client.post("/agent/a2a/doortoken", json={
                "correlation_id": cid, "callback_token": tok,
                "state": "completed", "result": {"text": "done"}})
            bad = await client.post("/agent/a2a/doortoken", json={
                "correlation_id": cid, "callback_token": "WRONG", "state": "completed"})
            return ok.status, bad.status
        finally:
            await client.close()

    ok_status, bad_status = asyncio.run(scenario())
    assert ok_status == 200
    assert bad_status == 403           # forbidden -> 403, HMAC enforced at the door
    assert store.get(cid)["status"] == "resolved"


def test_answer_endpoint_take_flow(store):
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer

    cid, tok = _mint(store)
    deliver, _ = _recorder()
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1"):
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "?"}, deliver=deliver))
    qid = r["question_id"]

    async def scenario():
        app = web.Application()
        a2a_fabric.add_inbound_route(app, "PATHTOKEN", deliver=deliver)
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            resp = await client.post("/agent/a2a/PATHTOKEN/answer",
                                     json={"correlation_id": cid, "question_id": qid},
                                     headers={"X-EVE-Callback-Token": tok})
            assert (await resp.json())["answered"] is False       # no answer yet
            store.set_answer(cid, "use standup")
            resp = await client.post("/agent/a2a/PATHTOKEN/answer",
                                     json={"correlation_id": cid, "question_id": qid},
                                     headers={"X-EVE-Callback-Token": "WRONG"})
            assert resp.status == 403                             # wrong token forbidden
            resp = await client.post("/agent/a2a/PATHTOKEN/answer",
                                     json={"correlation_id": cid, "question_id": qid},
                                     headers={"X-EVE-Callback-Token": tok})
            body = await resp.json()
            assert body["answered"] is True and body["answer"] == "use standup"
            resp = await client.post("/agent/a2a/PATHTOKEN/answer",
                                     json={"correlation_id": cid, "question_id": qid},
                                     headers={"X-EVE-Callback-Token": tok})
            assert (await resp.json())["answered"] is False       # single-fire
        finally:
            await client.close()
    asyncio.run(scenario())


def test_inbound_route_rejects_oversized_body(store):
    # W8/A7: the talk-back routes are tailnet-reachable; unbounded request.json() is a flood
    # vector. The route itself enforces the cap (abuse_guard only polices the SMS path).
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer

    deliver, _ = _recorder()
    app = web.Application()
    a2a_fabric.add_inbound_route(app, "doortoken", deliver=deliver)

    async def scenario():
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            big = {"correlation_id": "x", "callback_token": "y",
                   "state": "completed", "result": {"text": "A" * 300_000}}
            resp = await client.post("/agent/a2a/doortoken", json=big)
            return resp.status
        finally:
            await client.close()
    assert asyncio.run(scenario()) == 413


# ---- outbound: delegate / resume / app / flag --------------------------------

class _FakeClient:
    """A2A client stub: records the requests and yields nothing (the real answer comes via push)."""
    def __init__(self):
        self.sent = []

    async def send_message(self, req, *, context=None):
        self.sent.append(req)
        return
        yield  # make it an async generator


def test_delegate_creates_push_not_poll(store):
    # SAFETY (Winston #2): a2a_fabric.delegate MUST create delivery="push" so the poller, which
    # only claims delivery="poll" rows, can never ALSO run Hermes for the same task.
    fake = _FakeClient()
    cid = asyncio.run(a2a_fabric.delegate("post standup", requester="W", tier="owner",
                                          ttl_s=3600, client=fake))
    assert store.get(cid)["delivery"] == "push"
    assert len(fake.sent) == 1
    msg = fake.sent[0].message
    # A8: the server mints its own task id — the message must NOT pre-set one.
    assert not msg.task_id
    # The enriched task text carries the talk-back header + the original task.
    assert "post standup" in msg.parts[0].text
    assert cid in msg.parts[0].text and "ask_eve" in msg.parts[0].text
    # The push webhook + per-task token are registered on the request config.
    pnc = fake.sent[0].configuration.task_push_notification_config
    assert pnc.task_id == cid and pnc.token == store.get(cid)["callback_token"]
    assert fake.sent[0].configuration.return_immediately is True


def test_resume_mcp_stores_answer_for_ask_poll(store):
    # hermes talkback="mcp": resume stores the answer; the blocked ask_eve poll takes it.
    cid, tok = _mint(store)
    deliver, _ = _recorder()
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1"):
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "which?"}, deliver=deliver))
    qid = r["question_id"]
    with patch("a2a_fabric.approval_store.deny") as deny:
        res = asyncio.run(a2a_fabric.resume(cid, "use telegram"))
        deny.assert_called_once_with("ap-1")      # answered by voice => card is moot
    assert res["ok"] is True and res.get("stored") is True
    assert store.take_answer(cid, qid) == "use telegram"


def test_resume_not_awaiting_is_honest(store):
    cid, _ = _mint(store)
    res = asyncio.run(a2a_fabric.resume(cid, "hello"))
    assert res["ok"] is False and "waiting" in res["error"]


def test_resume_stale_question_notes_run_may_have_ended(store):
    # W6/A10: the honesty guard keys on PRE-answer evidence (asked_at older than the ask
    # window), not on the TTL-extended fresh row.
    import time as _t
    cid, tok = _mint(store)
    store.set_awaiting_user_cas(cid, {"qid": "q1", "question": "?", "approval_id": "",
                                      "asked_at": _t.time() - 100000})
    res = asyncio.run(a2a_fabric.resume(cid, "late answer"))
    assert res["ok"] is True
    assert "may have already ended" in (res.get("note") or "")


def test_enabled_flag(monkeypatch):
    monkeypatch.delenv("EVE_A2A_ENABLED", raising=False)
    assert a2a_fabric.enabled() is False
    monkeypatch.setenv("EVE_A2A_ENABLED", "1")
    assert a2a_fabric.enabled() is True


def test_delegate_with_session_prefixes_resume_line(store):
    fake = _FakeClient()
    cid = asyncio.run(a2a_fabric.delegate("Also include the metrics.", requester="W",
                                          tier="owner", ttl_s=3600, client=fake,
                                          session="20260702_x"))
    text = fake.sent[0].message.parts[0].text
    assert text.startswith("[RESUME-SESSION:20260702_x]\n")
    assert "Also include the metrics." in text and cid


def test_delegate_slash_command_passes_verbatim(store):
    # A "/command" must reach hermes as the FIRST token — no talk-back header prepended.
    fake = _FakeClient()
    asyncio.run(a2a_fabric.delegate("/goal show", requester="W", tier="owner",
                                    ttl_s=3600, client=fake))
    assert fake.sent[0].message.parts[0].text == "/goal show"


def test_push_completed_extracts_session_marker(store):
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "completed",
         "result": {"text": "posted to standup\n[hermes-session:20260702_y]"}},
        deliver=deliver))
    row = store.get(cid)
    assert row["result"]["session_id"] == "20260702_y"
    assert "hermes-session" not in row["result"]["text"]          # never spoken/persisted
    assert "hermes-session" not in (calls[-1]["text"] or "")


def test_talkback_header_contains_ids_and_rules():
    h = a2a_fabric.talkback_header("cid123", "tok456")
    assert "cid123" in h and "tok456" in h and "ask_eve" in h and "notify_eve" in h


def test_delegate_not_started_fails_row_and_raises(store, monkeypatch):
    import httpx

    async def refuse(url):
        raise httpx.ConnectError("refused")
    monkeypatch.setattr(a2a_fabric, "_client_factory", refuse)
    with pytest.raises(a2a_fabric.DelegateNotStarted):
        asyncio.run(a2a_fabric.delegate("t", requester="W", tier="owner", ttl_s=3600))
    rows = store.list_for_audit("hermes", 1)
    assert rows and rows[0]["status"] == "failed"
    # W5: superseded by the poller fallback — must NOT also replay as a blocker.
    assert store.failed_replays() == []


def test_delegate_ambiguous_error_fails_row_and_raises_ambiguous(store, monkeypatch):
    # W4: an ambiguous mid-send failure must NEVER fall back (no double-run); it raises
    # DelegateAmbiguous so the voice handler can answer honestly, and the row is failed +
    # marked delivered (the user was just told — no replay repeat).
    class _Boom:
        async def send_message(self, req):
            raise RuntimeError("stream broke mid-send")
            yield  # pragma: no cover

    async def factory(url):
        return _Boom()
    monkeypatch.setattr(a2a_fabric, "_client_factory", factory)
    with pytest.raises(a2a_fabric.DelegateAmbiguous) as exc:
        asyncio.run(a2a_fabric.delegate("t", requester="W", tier="owner", ttl_s=3600))
    cid = exc.value.cid
    assert store.get(cid)["status"] == "failed"
    assert store.failed_replays() == []               # already told; no replay repeat


# ---- real-SDK in-process round-trip (the repaired outbound leg) --------------

def test_real_sdk_roundtrip_inprocess(store, monkeypatch):
    """EVE delegate() -> real A2A JSON-RPC server (ASGITransport) -> fake runner completes ->
    real BasePushNotificationSender POST captured -> handle_push -> row resolved + delivered.
    Zero network."""
    import httpx
    monkeypatch.setenv("EVE_A2A_ADAPTER_KEY", "testkey")
    monkeypatch.setenv("EVE_A2A_HERMES_URL", "http://adapter.local")
    monkeypatch.setenv("EVE_A2A_INBOUND_URL", "http://push.local/agent/a2a/PATH")

    pushes = []
    deliver, calls = _recorder()

    async def scenario():
        async def fake_runner(spec, task, *, timeout_s=None):
            assert "correlation_id" in task            # enrichment header rode along
            return {"ok": True, "text": "posted to standup"}

        async def capture(request):
            body = json.loads(request.content.decode())
            r = await a2a_fabric.handle_push(
                body, deliver=deliver,
                headers={"X-A2A-Notification-Token":
                         request.headers.get("X-A2A-Notification-Token", "")})
            pushes.append((body, r))
            return httpx.Response(200, json={"ok": True})

        push_client = httpx.AsyncClient(transport=httpx.MockTransport(capture))
        monkeypatch.setattr(a2a_fabric, "_push_httpx_client", lambda: push_client)
        app = a2a_fabric.build_fabric_app(runner=fake_runner)   # AFTER the push patch (A4)
        eve_client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://adapter.local",
            headers={"X-EVE-A2A-Key": "testkey"})
        monkeypatch.setattr(a2a_fabric, "_shared_httpx_client", lambda: eve_client)

        cid = await a2a_fabric.delegate("post the standup", requester="W", tier="owner",
                                        ttl_s=3600)
        for _ in range(100):                           # drain the background executor + push
            if store.get(cid)["status"] == "resolved":
                break
            await asyncio.sleep(0.05)
        assert store.get(cid)["status"] == "resolved", f"pushes={pushes}"
        assert store.get(cid)["delivery"] == "push"
        assert any(r.get("ok") for _, r in pushes)
        assert any(c["kind"] == "agent_result" for c in calls)
        await eve_client.aclose()
        await push_client.aclose()
    asyncio.run(scenario())


def test_adapter_requires_auth_key(monkeypatch):
    import httpx
    monkeypatch.setenv("EVE_A2A_ADAPTER_KEY", "testkey")
    from a2a.utils import AGENT_CARD_WELL_KNOWN_PATH
    app = a2a_fabric.build_fabric_app()

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                     base_url="http://x") as c:
            r = await c.get(AGENT_CARD_WELL_KNOWN_PATH)
            assert r.status_code == 403
            r2 = await c.get(AGENT_CARD_WELL_KNOWN_PATH,
                             headers={"X-EVE-A2A-Key": "testkey"})
            assert r2.status_code == 200
    asyncio.run(scenario())


def test_first_progress_delivers_even_on_freshly_booted_clock(store, monkeypatch):
    # monotonic() starts at boot: on a machine up for <COOLDOWN seconds, a 0.0
    # "never seen" sentinel made now-0.0 < COOLDOWN true and swallowed the very
    # FIRST progress update (found by CI, whose VMs boot seconds before the run).
    monkeypatch.setattr(a2a_fabric, "_PROGRESS_COOLDOWN_S", 300.0)
    monkeypatch.setattr(a2a_fabric.time, "monotonic", lambda: 5.0)
    cid, tok = _mint(store)
    a2a_fabric._last_progress.pop(cid, None)
    deliver, calls = _recorder()
    asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "working",
         "kind": "progress", "result": {"text": "first update"}}, deliver=deliver))
    assert len(calls) == 1, "first progress must deliver regardless of machine uptime"
