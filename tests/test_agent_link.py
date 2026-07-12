# Standing agent link (Hermes -> EVE, unsolicited): auth is ONE long-lived key
# (EVE_AGENT_LINK_KEY, fail-closed), the message mints a real agent_tasks row and
# terminalizes it immediately, so the delegation delivery contract (notify/broadcast/replay)
# applies verbatim. Inbound content is relayed UNTRUSTED — nothing executes.
import asyncio
import importlib
import os
import tempfile

import pytest

import a2a_fabric
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


@pytest.fixture
def linked(monkeypatch):
    # _link_key() is file-first (instant rotation); point it away from the repo's real .env
    # so the test key in the env fallback is authoritative.
    monkeypatch.setattr(a2a_fabric, "_ENV_FILE", "/nonexistent/.env")
    monkeypatch.setenv("EVE_AGENT_LINK_KEY", "STANDING-KEY")
    monkeypatch.setattr(a2a_fabric, "_last_link", {})
    monkeypatch.setattr(a2a_fabric, "_link_budget", {})
    return "STANDING-KEY"


def _recorder():
    calls = []

    async def deliver(row, kind=None, text=None):
        calls.append({"row": row, "kind": kind, "text": text})
    return deliver, calls


def _send(payload):
    deliver, calls = _recorder()
    res = asyncio.run(a2a_fabric.handle_link(payload, deliver=deliver))
    return res, calls


def test_fail_closed_when_no_key_configured(store, monkeypatch):
    monkeypatch.setattr(a2a_fabric, "_ENV_FILE", "/nonexistent/.env")
    monkeypatch.delenv("EVE_AGENT_LINK_KEY", raising=False)
    res, calls = _send({"link_key": "", "agent": "hermes", "text": "hi"})
    assert res == {"ok": False, "error": "forbidden"} and calls == []


def test_env_file_wins_over_process_env_for_instant_rotation(store, linked, tmp_path,
                                                             monkeypatch):
    # link_pair.py rewrites .env; the handler must honor the FILE now, not the startup env.
    envf = tmp_path / ".env"
    envf.write_text("OTHER=x\nEVE_AGENT_LINK_KEY=ROTATED-KEY\n")
    monkeypatch.setattr(a2a_fabric, "_ENV_FILE", str(envf))
    res, _ = _send({"link_key": "STANDING-KEY", "agent": "hermes", "text": "old key"})
    assert res == {"ok": False, "error": "forbidden"}          # stale key revoked instantly
    res, _ = _send({"link_key": "ROTATED-KEY", "agent": "hermes", "text": "new key"})
    assert res["ok"] is True


def test_daily_budget_caps_runaway_agents(store, linked, monkeypatch):
    monkeypatch.setattr(a2a_fabric, "_LINK_COOLDOWN_S", 0.0)
    monkeypatch.setattr(a2a_fabric, "_LINK_DAILY_MAX", 2)
    assert _send({"link_key": linked, "agent": "hermes", "text": "one"})[0]["ok"] is True
    assert _send({"link_key": linked, "agent": "hermes", "text": "two"})[0]["ok"] is True
    res, calls = _send({"link_key": linked, "agent": "hermes", "text": "three"})
    assert res == {"ok": False, "error": "budget"} and calls == []


def test_session_id_stored_for_same_chat_reply(store, linked):
    res, _ = _send({"link_key": linked, "agent": "hermes", "text": "news",
                    "session_id": "20260704_1536; rm -rf /"})
    row = store.get(res["cid"])
    # sanitized to a code-safe handle and stored where last_session_for() looks
    assert (row["result"] or {}).get("session_id") == "20260704_1536rm-rf"
    import delegate_registry
    assert delegate_registry.last_session_for("hermes") == "20260704_1536rm-rf"


def test_wrong_key_forbidden(store, linked):
    res, calls = _send({"link_key": "WRONG", "agent": "hermes", "text": "hi"})
    assert res == {"ok": False, "error": "forbidden"} and calls == []


def test_unknown_agent_rejected(store, linked):
    res, calls = _send({"link_key": linked, "agent": "mallory", "text": "hi"})
    assert res == {"ok": False, "error": "unknown"} and calls == []


def test_bad_kind_and_empty_text_rejected(store, linked):
    res, _ = _send({"link_key": linked, "agent": "hermes", "text": "hi", "kind": "execute"})
    assert res["error"] == "bad kind"
    res, _ = _send({"link_key": linked, "agent": "hermes", "text": "   "})
    assert res["error"] == "empty"


def test_message_mints_resolved_row_and_delivers_result(store, linked):
    res, calls = _send({"link_key": linked, "agent": "hermes",
                        "text": "store made its first sale"})
    assert res["ok"] is True
    row = store.get(res["cid"])
    assert row["status"] == "resolved"
    assert row["delivery"] == "push"                  # poller can never claim/run it
    assert row["requester"] == "link:hermes"
    assert (row["result"] or {}).get("unsolicited") is True
    assert calls[0]["kind"] == agent_delivery.AGENT_RESULT
    assert calls[0]["text"] == "store made its first sale"


def test_blocker_kind_fails_row_and_delivers_blocker(store, linked):
    res, calls = _send({"link_key": linked, "agent": "hermes",
                        "kind": "blocker", "text": "shopify token expired"})
    assert res["ok"] is True
    row = store.get(res["cid"])
    assert row["status"] == "failed"
    assert calls[0]["kind"] == agent_delivery.AGENT_BLOCKER


def test_cooldown_rate_limits_per_agent(store, linked):
    ok, _ = _send({"link_key": linked, "agent": "hermes", "text": "one"})
    assert ok["ok"] is True
    res, calls = _send({"link_key": linked, "agent": "hermes", "text": "two"})
    assert res == {"ok": False, "error": "cooldown"} and calls == []


def test_link_key_scrubbed_from_delivered_text(store, linked):
    a2a_fabric._last_link.clear()
    res, calls = _send({"link_key": linked, "agent": "hermes",
                        "text": f"my key is {linked} whoops"})
    assert res["ok"] is True
    assert linked not in calls[0]["text"]
    assert linked not in (store.get(res["cid"])["result"] or {}).get("text", "")


def test_unsolicited_framing_never_claims_a_handoff(store, linked):
    # EVE must say "Hermes reached out with a message", not lie that "a task you handed off
    # finished" — nothing was handed off. Both the live and the replay (morning resurface)
    # framings branch on the link:<agent> requester stamp.
    import try_announce
    res, _ = _send({"link_key": linked, "agent": "hermes", "text": "store made its first sale"})
    row = store.get(res["cid"])
    assert try_announce.unsolicited(row) is True
    for inst in (try_announce.live_instruction(row), try_announce.replay_instruction(row)):
        assert "reached out" in inst and "MESSAGE:" in inst
        assert "finished a task you handed off" not in inst
        assert "just finished" not in inst
        assert "store made its first sale" in inst


def test_unsolicited_blocker_framing(store, linked):
    # Blocker rows have NO result.unsolicited flag (fail() overwrites the result) — the
    # requester stamp alone must carry the framing.
    import try_announce
    res, _ = _send({"link_key": linked, "agent": "hermes",
                    "kind": "blocker", "text": "shopify token expired"})
    row = store.get(res["cid"])
    assert try_announce.unsolicited(row) is True
    inst = try_announce.blocker_instruction(row)
    assert "reached out" in inst and "shopify token expired" in inst
    assert "could NOT be completed" not in inst


def test_delegation_rows_keep_handoff_framing(store):
    # The unsolicited branch must not leak into real delegation results.
    import try_announce
    cid, _tok = store.create("hermes", "post standup", summary="post standup", delivery="push",
                             requester="W", requester_tier="owner", ttl_s=3600)
    store.resolve(cid)
    store.finish(cid, {"ok": True, "text": "posted"})
    row = store.get(cid)
    assert try_announce.unsolicited(row) is False
    assert "handed off" in try_announce.live_instruction(row)
    assert "handed off" in try_announce.replay_instruction(row)


def test_undelivered_message_replays_at_session_start(store, linked):
    # deliver lands nowhere (dead session) — the row must surface in claim_replays.
    res, _ = _send({"link_key": linked, "agent": "hermes", "text": "missed news"})
    assert res["cid"] in [r["id"] for r in store.claim_replays()]


def test_route_branches_on_link_key_shape(store, linked):
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer

    deliver, calls = _recorder()
    app = web.Application()
    a2a_fabric.add_inbound_route(app, "doortoken", deliver=deliver)

    async def scenario():
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            ok = await client.post("/agent/a2a/doortoken", json={
                "link_key": linked, "agent": "hermes", "text": "over the wire"})
            bad = await client.post("/agent/a2a/doortoken", json={
                "link_key": "WRONG", "agent": "hermes", "text": "nope"})
            limited = await client.post("/agent/a2a/doortoken", json={
                "link_key": linked, "agent": "hermes", "text": "too fast"})
            return ok.status, bad.status, limited.status
        finally:
            await client.close()

    ok_status, bad_status, limited_status = asyncio.run(scenario())
    assert (ok_status, bad_status, limited_status) == (200, 403, 429)
    assert calls[0]["kind"] == agent_delivery.AGENT_RESULT


def test_push_path_untouched_by_link_branch(store, linked):
    # A normal delegation callback (no link_key field) still flows handle_push.
    cid, tok = store.create("hermes", "task", summary="task", delivery="push",
                            requester="W", requester_tier="owner", ttl_s=3600)
    deliver, calls = _recorder()
    res = asyncio.run(a2a_fabric.handle_push(
        {"correlation_id": cid, "callback_token": tok, "state": "completed",
         "result": {"text": "done"}}, deliver=deliver))
    assert res["ok"] is True and store.get(cid)["status"] == "resolved"
