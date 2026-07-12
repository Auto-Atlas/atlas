# Subprocess protocol tests for eve_talkback_mcp.py — the stdio MCP server Hermes spawns.
# The stub of EVE's inbound routes runs on an EPHEMERAL port (never :8787 — the live sidecar
# owns it). CI-safe: loopback only, no live services.
import asyncio
import json
import os
import pathlib
import socket
import subprocess
import sys
import threading
import time

import pytest
from aiohttp import web

MCP_PATH = pathlib.Path(__file__).resolve().parent.parent / "eve_talkback_mcp.py"


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Srv:
    """Scriptable stub of EVE's inbound routes."""

    def __init__(self):
        self.pushes = []
        self.answer = None
        self.fail_next = 0        # next N pushes 500 (transport-ish failure)
        self.push_403 = False     # reject pushes (bad token)
        self.not_open = False     # reply ok-but-not-staged (task closed)
        self.cancelled = False    # owner cancelled: check-ins carry the stop directive
        self.qid = "q-test"

    _DIRECTIVE = ("STOP: the owner cancelled this task. Stop all work on it immediately "
                  "and exit — do not take further actions and do not send more updates.")

    async def push(self, request):
        if self.push_403:
            raise web.HTTPForbidden()
        if self.fail_next > 0:
            self.fail_next -= 1
            raise web.HTTPInternalServerError()
        body = await request.json()
        self.pushes.append(body)
        if self.cancelled:
            return web.json_response({"ok": True, "cancelled": True,
                                      "directive": self._DIRECTIVE})
        if body.get("state") == "input_required":
            if self.not_open:
                return web.json_response({"ok": True, "note": "not open for questions"})
            return web.json_response({"ok": True, "staged": True, "question_id": self.qid})
        return web.json_response({"ok": True})

    async def answer_ep(self, request):
        if self.cancelled:
            return web.json_response({"ok": True, "answered": False, "cancelled": True,
                                      "directive": self._DIRECTIVE})
        if self.answer is None:
            return web.json_response({"ok": True, "answered": False})
        return web.json_response({"ok": True, "answered": True, "answer": self.answer})


@pytest.fixture
def mcp():
    srv = Srv()
    port = _free_port()
    app = web.Application()
    app.router.add_post("/agent/a2a/T", srv.push)
    app.router.add_post("/agent/a2a/T/answer", srv.answer_ep)
    loop = asyncio.new_event_loop()
    runner = web.AppRunner(app)

    def run():
        asyncio.set_event_loop(loop)
        loop.run_until_complete(runner.setup())
        loop.run_until_complete(web.TCPSite(runner, "127.0.0.1", port).start())
        loop.run_forever()

    t = threading.Thread(target=run, daemon=True)
    t.start()
    time.sleep(0.3)
    env = {**os.environ,
           "EVE_TALKBACK_INBOUND_URL": f"http://127.0.0.1:{port}/agent/a2a/T",
           "EVE_TALKBACK_ASK_WAIT_S": "6",
           "EVE_TALKBACK_POLL_S": "0.2",
           "EVE_TALKBACK_HTTP_TIMEOUT_S": "2",
           "EVE_AGENT_LINK_KEY": "LK-test"}
    proc = subprocess.Popen([sys.executable, str(MCP_PATH)], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, text=True)

    def rpc(method, params=None, id_=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if id_ is not None:
            msg["id"] = id_
        proc.stdin.write(json.dumps(msg) + "\n")
        proc.stdin.flush()
        if id_ is None:
            return None
        line = proc.stdout.readline()
        return json.loads(line)

    yield srv, rpc, proc
    proc.kill()
    loop.call_soon_threadsafe(loop.stop)


def test_protocol_handshake_and_tools(mcp):
    srv, rpc, proc = mcp
    r = rpc("initialize", {"protocolVersion": "2025-06-18"}, id_=1)
    assert r["result"]["serverInfo"]["name"] == "eve-talkback"
    rpc("notifications/initialized")                 # NOTIFICATION: must get no reply
    r = rpc("ping", id_=2)
    assert r["id"] == 2                              # would mismatch if the notif was answered
    r = rpc("tools/list", id_=3)
    assert {t["name"] for t in r["result"]["tools"]} == {"notify_eve", "ask_eve",
                                                         "message_eve"}


def test_message_eve_posts_standing_link_shape(mcp):
    srv, rpc, _ = mcp
    rpc("initialize", {}, id_=1)
    r = rpc("tools/call", {"name": "message_eve", "arguments": {
        "text": "first sale on the store"}}, id_=2)
    assert r["result"]["isError"] is False
    p = srv.pushes[-1]
    assert p["link_key"] == "LK-test" and p["agent"] == "hermes"
    assert p["kind"] == "message" and p["text"] == "first sale on the store"
    # no per-task credentials on the standing link
    assert "correlation_id" not in p and "callback_token" not in p


def test_notify_posts_nonterminal_shape(mcp):
    srv, rpc, _ = mcp
    rpc("initialize", {}, id_=1)
    r = rpc("tools/call", {"name": "notify_eve", "arguments": {
        "correlation_id": "c1", "callback_token": "t1", "kind": "blocker",
        "text": "stuck on creds"}}, id_=2)
    assert r["result"]["isError"] is False
    p = srv.pushes[-1]
    assert p["state"] == "working" and p["kind"] == "blocker"
    assert p["correlation_id"] == "c1" and p["callback_token"] == "t1"


def test_ask_blocks_then_returns_answer(mcp):
    srv, rpc, _ = mcp
    rpc("initialize", {}, id_=1)

    def answer_later():
        time.sleep(1.0)
        srv.answer = "use standup"
    threading.Thread(target=answer_later, daemon=True).start()
    r = rpc("tools/call", {"name": "ask_eve", "arguments": {
        "correlation_id": "c1", "callback_token": "t1", "question": "which?"}}, id_=2)
    assert "use standup" in r["result"]["content"][0]["text"]
    assert srv.pushes and srv.pushes[0]["state"] == "input_required"


def test_ping_answered_while_ask_blocks(mcp):
    # The reader thread answers pings inline; a blocked ask on the worker pool can't starve it.
    srv, rpc, proc = mcp
    rpc("initialize", {}, id_=1)
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 10, "method": "tools/call",
                                 "params": {"name": "ask_eve", "arguments": {
                                     "correlation_id": "c1", "callback_token": "t1",
                                     "question": "?"}}}) + "\n")
    proc.stdin.flush()
    time.sleep(0.3)                                   # ask is now blocking on the pool
    r = rpc("ping", id_=11)
    assert r["id"] == 11                              # ping answered before the ask returns
    srv.answer = "yes"
    line = proc.stdout.readline()                     # now the ask completes
    assert json.loads(line)["id"] == 10


def test_ask_survives_transient_outage(mcp):
    srv, rpc, _ = mcp
    srv.fail_next = 2                                 # first submits 500 -> retried
    rpc("initialize", {}, id_=1)

    def answer_later():
        time.sleep(1.5)
        srv.answer = "yes"
    threading.Thread(target=answer_later, daemon=True).start()
    r = rpc("tools/call", {"name": "ask_eve", "arguments": {
        "correlation_id": "c1", "callback_token": "t1", "question": "?"}}, id_=2)
    assert "yes" in r["result"]["content"][0]["text"]


def test_ask_deadline_graceful(mcp):
    srv, rpc, _ = mcp
    rpc("initialize", {}, id_=1)
    r = rpc("tools/call", {"name": "ask_eve", "arguments": {
        "correlation_id": "c1", "callback_token": "t1", "question": "?"}}, id_=2)
    assert "No answer yet" in r["result"]["content"][0]["text"]
    assert r["result"]["isError"] is False            # graceful, hermes keeps control


def test_ask_not_open_exits_fast(mcp):
    # W11: a definitive "not open for questions" must NOT stall until the deadline.
    srv, rpc, _ = mcp
    srv.not_open = True
    rpc("initialize", {}, id_=1)
    t0 = time.monotonic()
    r = rpc("tools/call", {"name": "ask_eve", "arguments": {
        "correlation_id": "c1", "callback_token": "t1", "question": "?"}}, id_=2)
    assert time.monotonic() - t0 < 3.0                # well under the 6s test deadline
    assert "not open" in r["result"]["content"][0]["text"]


def test_ask_rejected_token_fails_loud(mcp):
    srv, rpc, _ = mcp
    srv.push_403 = True
    rpc("initialize", {}, id_=1)
    r = rpc("tools/call", {"name": "ask_eve", "arguments": {
        "correlation_id": "cX", "callback_token": "BAD", "question": "?"}}, id_=2)
    assert r["result"]["isError"] is True


def test_notify_surfaces_cancel_directive(mcp):
    # When the owner cancelled the task, the check-in RESPONSE carries the stop order —
    # notify_eve must hand that directive to the agent as the tool result (not an error:
    # it is an instruction the agent must read and obey).
    srv, rpc, _ = mcp
    srv.cancelled = True
    rpc("initialize", {"protocolVersion": "2024-11-05"}, id_=1)
    r = rpc("tools/call", {"name": "notify_eve", "arguments": {
        "correlation_id": "C1", "callback_token": "TK", "kind": "progress",
        "text": "step 2"}}, id_=2)
    text = r["result"]["content"][0]["text"]
    assert "stop" in text.lower() and "cancel" in text.lower()
    assert r["result"].get("isError") is not True


def test_ask_exits_immediately_on_cancel(mcp):
    # An agent blocked in ask_eve must not keep polling a cancelled task for 14 minutes —
    # the answer poll relays the stop order the moment EVE reports the cancel.
    srv, rpc, _ = mcp
    srv.cancelled = True
    rpc("initialize", {"protocolVersion": "2024-11-05"}, id_=1)
    t0 = time.monotonic()
    r = rpc("tools/call", {"name": "ask_eve", "arguments": {
        "correlation_id": "C1", "callback_token": "TK", "question": "env?"}}, id_=2)
    text = r["result"]["content"][0]["text"]
    assert "stop" in text.lower() and "cancel" in text.lower()
    assert time.monotonic() - t0 < 4.0        # well before the 6s test deadline
