# Generalization contract tests (talk-back spec §4.7): jarvis and open_claw are registry rows
# against the SAME gated bridge — result/blocker/question flow identically to hermes, so
# flipping `enabled` is a config change, not a rewrite. Plus the flag-off guard and the
# INPUT_REQUIRED resume contract test that gates any future talkback="a2a" row.
import asyncio
import importlib
import os
import tempfile
from unittest.mock import patch

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


def _mint_agent(store, agent):
    return store.create(agent, "do a thing", summary="do a thing", delivery="push",
                        requester="W", requester_tier="owner", ttl_s=3600)


@pytest.mark.parametrize("agent", ["jarvis", "open_claw"])
def test_other_agents_results_and_blockers_flow_the_same_bridge(store, agent):
    cid, tok = _mint_agent(store, agent)
    deliver, calls = _recorder()
    r = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "completed",
         "result": {"text": "done"}}, deliver=deliver))
    assert r["ok"] is True and store.get(cid)["status"] == "resolved"
    assert calls[0]["kind"] == "agent_result"

    cid2, tok2 = _mint_agent(store, agent)
    r2 = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid2, "callback_token": tok2, "state": "failed",
         "result": {"text": "blocked"}}, deliver=deliver))
    assert r2.get("failed") is True and store.get(cid2)["status"] == "failed"
    assert calls[-1]["kind"] == "agent_blocker"


@pytest.mark.parametrize("agent", ["jarvis", "open_claw"])
def test_other_agents_questions_gate_identically(store, agent):
    cid, tok = _mint_agent(store, agent)
    deliver, calls = _recorder()
    with patch("a2a_fabric.approval_store.stage", return_value="ap-1") as stage:
        r = asyncio.run(a2a_fabric.handle_push(
            {"correlation_id": cid, "callback_token": tok, "state": "input_required",
             "question": "may I?"}, deliver=deliver))
    assert r["staged"] is True
    stage.assert_called_once()                       # staged, NEVER executed
    assert stage.call_args.args[0] == f"resume_{agent}"
    assert store.get(cid)["status"] == "awaiting_user"
    assert calls[0]["kind"] == "agent_question"


def test_registry_talkback_capabilities():
    from delegate_registry import REGISTRY
    assert REGISTRY["hermes"].talkback == "mcp" and REGISTRY["hermes"].enabled
    assert REGISTRY["jarvis"].talkback == "http" and not REGISTRY["jarvis"].enabled
    assert REGISTRY["open_claw"].talkback == "http" and not REGISTRY["open_claw"].enabled


def test_no_a2a_talkback_rows_without_the_resume_contract_test():
    # W13: talkback="a2a" resumes by remote task id over the SDK — a row may only carry it
    # once test_resume_a2a_input_required_contract (below) exists AND passes. Today: none do.
    from delegate_registry import REGISTRY
    assert all(s.talkback != "a2a" for s in REGISTRY.values())


def test_flag_off_no_routes_no_enrichment(monkeypatch):
    monkeypatch.delenv("EVE_A2A_ENABLED", raising=False)
    assert a2a_fabric.enabled() is False
    from aiohttp import web
    app = web.Application()
    # sms_webhook mounts the a2a routes only when enabled(); simulate its guard:
    if a2a_fabric.enabled():
        a2a_fabric.add_inbound_route(app, "T", deliver=None)
    assert not any("/agent/a2a" in str(r.resource) for r in app.router.routes())


def test_resume_a2a_input_required_contract(store, monkeypatch):
    """The gate for any future talkback='a2a' registry row: against the REAL SDK handler, a
    task that enters INPUT_REQUIRED can be resumed by sending a follow-up message with the
    server-minted task id — the executor runs again with current_task set and completes."""
    import httpx
    from fastapi import FastAPI
    from a2a.helpers import new_task_from_user_message
    from a2a.server.agent_execution import AgentExecutor
    from a2a.server.request_handlers import DefaultRequestHandler
    from a2a.server.routes import (add_a2a_routes_to_fastapi, create_agent_card_routes,
                                   create_jsonrpc_routes)
    from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
    from a2a.types import a2a_pb2 as pb
    from a2a.utils import DEFAULT_RPC_URL

    monkeypatch.setenv("EVE_A2A_ADAPTER_KEY", "testkey")
    seen = {"first": None, "resume": None, "task_ids": []}

    class Probe(AgentExecutor):
        async def execute(self, context, event_queue):
            u = TaskUpdater(event_queue, context.task_id, context.context_id)
            if context.current_task is None:
                await event_queue.enqueue_event(new_task_from_user_message(context.message))
                seen["first"] = context.get_user_input()
                seen["task_ids"].append(context.task_id)
                await u.requires_input(
                    u.new_agent_message([pb.Part(text="which channel?")]))
            else:
                seen["resume"] = context.get_user_input()
                seen["task_ids"].append(context.task_id)
                await u.complete(u.new_agent_message([pb.Part(text="done: standup")]))

        async def cancel(self, context, event_queue):
            pass

    app = FastAPI()
    handler = DefaultRequestHandler(Probe(), InMemoryTaskStore(), a2a_fabric.fabric_agent_card())
    add_a2a_routes_to_fastapi(
        app,
        jsonrpc_routes=create_jsonrpc_routes(handler, DEFAULT_RPC_URL),
        agent_card_routes=create_agent_card_routes(a2a_fabric.fabric_agent_card()))

    def _task_of(evt):
        # The non-streaming client yields StreamResponse protos whose `task` field carries
        # the server-minted task (empirically verified against a2a-sdk 1.1.0).
        t = getattr(evt, "task", None)
        return t if t is not None and getattr(t, "id", "") else None

    async def scenario():
        eve_client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                       base_url="http://adapter.local",
                                       headers={"X-EVE-A2A-Key": "testkey"})
        monkeypatch.setattr(a2a_fabric, "_shared_httpx_client", lambda: eve_client)
        cl = await a2a_fabric._client_factory("http://adapter.local")
        # 1) first send: NO task id — the server mints one; the run pauses INPUT_REQUIRED.
        req = pb.SendMessageRequest(message=a2a_fabric._make_message("post the standup"))
        remote_id = ""
        async for evt in cl.send_message(req):
            t = _task_of(evt)
            if t is not None and t.id:
                remote_id = t.id
        assert remote_id and seen["first"] == "post the standup"
        # 2) resume: SAME task id, the answer as the message.
        req2 = pb.SendMessageRequest(message=pb.Message(
            message_id="m-resume", task_id=remote_id, role=pb.Role.ROLE_USER,
            parts=[pb.Part(text="use standup")]))
        async for _ in cl.send_message(req2):
            pass
        assert seen["resume"] == "use standup"
        assert seen["task_ids"][0] == seen["task_ids"][1] == remote_id
        await eve_client.aclose()
    asyncio.run(scenario())
