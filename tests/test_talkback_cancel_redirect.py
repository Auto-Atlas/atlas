# Cancel (and later redirect) riding the talk-back bridge (live-delegation-approvals):
# the owner cancels from the app; the RUNNING agent learns it at its next check-in via the
# handle_push RESPONSE (the MCP tools relay the body back as tool text). No new transport.
import asyncio
import importlib
import os
import tempfile

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
    calls = []

    async def deliver(row, kind=None, text=None):
        calls.append({"row": row, "kind": kind, "text": text})
    return deliver, calls


def _mint(store):
    return store.create("hermes", "long job", summary="long job", delivery="push",
                        requester="W", requester_tier="owner", ttl_s=3600)


def _push(payload, deliver, broadcast=None):
    return asyncio.run(a2a_fabric.handle_push(payload, deliver=deliver, broadcast=broadcast))


def test_working_push_on_cancel_requested_row_returns_stop_directive(store):
    cid, tok = _mint(store)
    store.request_cancel(cid)
    deliver, calls = _recorder()
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "working",
               "kind": "progress", "result": {"text": "step 3: writing tests"}}, deliver)
    assert r["ok"] is True and r.get("cancelled") is True
    assert "stop" in str(r.get("directive", "")).lower()
    assert calls == []                                  # the dead task's progress is not relayed
    assert store.get(cid)["status"] == store.CANCEL_REQUESTED   # stop not yet observed


def test_terminal_push_on_cancel_requested_row_finalizes_without_result_announce(store):
    cid, tok = _mint(store)
    store.request_cancel(cid)
    deliver, calls = _recorder()
    sent = []
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "completed",
               "result": {"text": "did it anyway"}}, deliver, broadcast=sent.append)
    assert r["ok"] is True and r.get("cancelled") is True
    row = store.get(cid)
    assert row["status"] == store.CANCELLED and row["delivered_at"] is not None
    assert calls == []                                  # never spoken/announced as a result
    kinds = [e["type"] for e in sent]
    assert kinds == ["agent_task_cancelled"], f"expected the cancelled event, got {kinds}"
    assert sent[0]["task_id"] == cid and sent[0]["status"] == "cancelled"


def test_question_push_on_cancel_requested_row_is_refused_with_directive(store):
    cid, tok = _mint(store)
    store.request_cancel(cid)
    deliver, calls = _recorder()
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "input_required",
               "question": "which env?"}, deliver)
    assert r.get("cancelled") is True and "stop" in str(r.get("directive", "")).lower()
    assert calls == []
    assert store.get(cid)["question"] is None           # nothing staged


def test_cancel_on_awaiting_row_closes_the_moot_approval_card(store):
    import approval_store
    cid, tok = _mint(store)
    # Stage the question exactly as handle_push does, then cancel, then the terminal push.
    deliver, calls = _recorder()
    _push({"correlation_id": cid, "callback_token": tok, "state": "input_required",
           "question": "which env?"}, deliver)
    aid = store.get(cid)["question"]["approval_id"]
    assert store.request_cancel(cid) == store.CANCEL_REQUESTED
    _push({"correlation_id": cid, "callback_token": tok, "state": "failed",
           "result": {"text": "gave up"}}, deliver, broadcast=lambda e: None)
    assert store.get(cid)["status"] == store.CANCELLED
    assert approval_store.get(aid)["status"] != "pending"   # card no longer actionable


def test_normal_rows_unaffected_by_cancel_plumbing(store):
    # Regression guard: the broadcast seam is optional and normal flow stays identical.
    cid, tok = _mint(store)
    deliver, calls = _recorder()
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "completed",
               "result": {"text": "done"}}, deliver)
    assert r["ok"] is True and store.get(cid)["status"] == "resolved"
    assert calls and calls[0]["kind"] == "agent_result"


def test_cancelled_event_broadcast_awaits_async_seam(store):
    # bridge.broadcast is a coroutine function in prod — the seam must be awaited (the
    # sync-capture tests above can pass even when an async seam would break).
    cid, tok = _mint(store)
    store.request_cancel(cid)
    deliver, _ = _recorder()
    sent = []

    async def broadcast(evt):
        sent.append(evt)

    _push({"correlation_id": cid, "callback_token": tok, "state": "completed",
           "result": {"text": "x"}}, deliver, broadcast=broadcast)
    assert [e["type"] for e in sent] == ["agent_task_cancelled"]


# ---- Route layer: the seam reaches prod handlers ----------------------------

def _route_app(store, broadcast=None):
    from aiohttp import web
    deliver, calls = _recorder()
    app = web.Application()
    a2a_fabric.add_inbound_route(app, "T", deliver=deliver, broadcast=broadcast)
    return app, calls


def test_push_route_passes_broadcast_seam(store):
    # The mounted route must hand the broadcast seam to handle_push — otherwise cancel
    # finalization events silently vanish in prod while unit tests stay green.
    from aiohttp.test_utils import TestClient, TestServer

    cid, tok = _mint(store)
    store.request_cancel(cid)
    sent = []
    app, _calls = _route_app(store, broadcast=sent.append)

    async def run():
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            resp = await client.post(f"/agent/a2a/T", json={
                "correlation_id": cid, "callback_token": tok, "state": "completed",
                "result": {"text": "x"}})
            return resp.status, await resp.json()
        finally:
            await client.close()

    status, body = asyncio.run(run())
    assert status == 200 and body.get("cancelled") is True
    assert [e["type"] for e in sent] == ["agent_task_cancelled"]


def test_answer_route_relays_stop_to_blocked_ask(store):
    # A hermes blocked in ask_eve polls /answer — cancel must reach it there, not stall
    # until the ask deadline.
    from aiohttp.test_utils import TestClient, TestServer

    cid, tok = _mint(store)
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "input_required",
               "question": "env?"}, _recorder()[0])
    qid = r["question_id"]
    assert store.request_cancel(cid) == store.CANCEL_REQUESTED
    app, _calls = _route_app(store)

    async def run():
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            resp = await client.post(f"/agent/a2a/T/answer",
                                     json={"correlation_id": cid, "question_id": qid},
                                     headers={"X-EVE-Callback-Token": tok})
            return resp.status, await resp.json()
        finally:
            await client.close()

    status, body = asyncio.run(run())
    assert status == 200
    assert body.get("cancelled") is True
    assert "stop" in str(body.get("directive", "")).lower()


# ---- Redirect riding the same check-in channel -------------------------------

def test_working_push_delivers_pending_redirect_once(store):
    cid, tok = _mint(store)
    store.set_redirect(cid, "only audit the checkout flow")
    deliver, calls = _recorder()
    sent = []
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "working",
               "kind": "progress", "result": {"text": "crawling site"}}, deliver,
              broadcast=sent.append)
    assert r["ok"] is True
    assert "only audit the checkout flow" in str(r.get("directive", ""))
    assert calls and calls[0]["kind"] == "agent_progress"   # progress still relayed (not a cancel)
    assert [e["type"] for e in sent] == ["agent_task_redirected"]
    assert sent[0]["task_id"] == cid and sent[0]["status"] == "redirect_delivered"
    # Second check-in: the steer was consumed, no repeat.
    r2 = _push({"correlation_id": cid, "callback_token": tok, "state": "working",
                "kind": "progress", "result": {"text": "next step"}}, deliver)
    assert "directive" not in r2


def test_answer_poll_relays_pending_redirect_and_unblocks(store):
    from aiohttp.test_utils import TestClient, TestServer

    cid, tok = _mint(store)
    r = _push({"correlation_id": cid, "callback_token": tok, "state": "input_required",
               "question": "which page?"}, _recorder()[0])
    qid = r["question_id"]
    store.set_redirect(cid, "skip that — audit checkout instead")
    app, _calls = _route_app(store)

    async def run():
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            resp = await client.post(f"/agent/a2a/T/answer",
                                     json={"correlation_id": cid, "question_id": qid},
                                     headers={"X-EVE-Callback-Token": tok})
            return resp.status, await resp.json()
        finally:
            await client.close()

    status, body = asyncio.run(run())
    assert status == 200
    assert "audit checkout instead" in str(body.get("directive", ""))
    # The question is moot: the row leaves AWAITING_USER so the app stops showing
    # "waiting on you" for a steer the owner already gave.
    assert store.get(cid)["status"] != store.AWAITING_USER


def test_talkback_header_documents_the_owner_control_channel():
    # The agent must know FROM THE DELEGATION CONTRACT that cancel/redirect directives ride
    # the tool RESPONSES — otherwise per-task prompts must say "obey whatever the tool
    # returns", which reads as a prompt-injection skeleton and safety-conscious agents
    # (hermes, live 2026-07-10) refuse the whole task.
    h = a2a_fabric.talkback_header("CID", "TOK")
    lower = h.lower()
    assert "cancel" in lower, "header must explain the owner-cancel directive"
    assert "new instructions" in lower, "header must explain owner redirects"
    assert "response" in lower, "header must say directives arrive in tool responses"
