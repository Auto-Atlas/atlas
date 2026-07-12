# Tests for agent_callback — the inbound connector-back (EVE Agent Hub).
# pytest-aiohttp isn't installed, so each test drives aiohttp's TestServer/TestClient
# inside asyncio.run (the repo's "sync test owns its own loop" style).
import asyncio
import importlib
import os
import tempfile

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer


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


def _run(scenario):
    asyncio.run(scenario())


def _make_app(spoke):
    import agent_callback
    import try_announce
    app = web.Application()

    async def announce(text):
        spoke.append(text)

    async def tafn(instruction, cid):
        return await try_announce.deliver(announce, instruction, cid=cid)

    agent_callback.add_routes(app, "TOK", try_announce_fn=tafn, broadcast=lambda d: None)
    return app


def test_valid_callback_resolves_and_speaks(store):
    async def scenario():
        cid, tok = store.create("hermes", "t", summary="t", delivery="push",
                                requester="W", requester_tier="owner", ttl_s=3600)
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": cid, "callback_token": tok,
                "status": "ok", "result": {"text": "Marco says 9am"}})
            assert r.status == 200
        assert store.get(cid)["status"] == "resolved"
        assert spoke and "Marco says 9am" in spoke[0]
        assert store.get(cid)["delivered_at"] is not None
    _run(scenario)


def test_bad_path_token_404(store):
    async def scenario():
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/WRONG", json={})
            assert r.status == 404
    _run(scenario)


def test_forged_callback_token_403(store):
    async def scenario():
        cid, _ = store.create("hermes", "t", summary="t", delivery="push",
                              requester="W", requester_tier="owner", ttl_s=3600)
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": cid, "callback_token": "forged", "status": "ok"})
            assert r.status == 403
        assert not spoke
        assert store.get(cid)["status"] == "pending"      # untouched
    _run(scenario)


def test_unknown_correlation_404(store):
    async def scenario():
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": "nope", "callback_token": "x", "status": "ok"})
            assert r.status == 404
    _run(scenario)


def test_duplicate_callback_idempotent(store):
    async def scenario():
        cid, tok = store.create("hermes", "t", summary="t", delivery="push",
                                requester="W", requester_tier="owner", ttl_s=3600)
        spoke = []
        body = {"correlation_id": cid, "callback_token": tok, "status": "ok",
                "result": {"text": "x"}}
        async with TestClient(TestServer(_make_app(spoke))) as c:
            assert (await c.post("/agent/callback/TOK", json=body)).status == 200
            r2 = await c.post("/agent/callback/TOK", json=body)
            assert r2.status == 200                        # no-op, not an error
        assert len(spoke) == 1                              # spoken exactly once
    _run(scenario)


def test_proposal_stages_not_executes(store):
    async def scenario():
        cid, tok = store.create("hermes", "send $", summary="send", delivery="push",
                                requester="W", requester_tier="owner", ttl_s=3600)
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": cid, "callback_token": tok, "status": "ok",
                "proposal": {"action": "send_message", "to": "Marco", "text": "ok?"}})
            assert r.status == 200
        import approval_store
        staged = approval_store.list_pending()
        assert any(a["tool"] == "delegate_hermes_proposal" for a in staged)
    _run(scenario)


def test_short_result_inline_no_file(store, monkeypatch, tmp_path):
    # Under the cap: delivered inline, exactly as before, and NO file is written.
    monkeypatch.setenv("JARVIS_WORKSPACE", str(tmp_path))
    monkeypatch.setenv("EVE_AGENT_RESULT_INLINE_MAX", "1500")

    async def scenario():
        cid, tok = store.create("hermes", "t", summary="t", delivery="push",
                                requester="W", requester_tier="owner", ttl_s=3600)
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": cid, "callback_token": tok,
                "status": "ok", "result": {"text": "Marco says 9am"}})
            assert r.status == 200
        assert spoke and "Marco says 9am" in spoke[0]
        assert "saved to" not in spoke[0]
        # No file written, and no path recorded in the audit row.
        assert not (tmp_path / "agent-results").exists()
        assert store.get(cid)["result"].get("result_path") is None
    _run(scenario)


def test_long_result_saved_to_file_and_summary_delivered(store, monkeypatch, tmp_path):
    # Over the cap: the FULL text is persisted to a file, the spoken text is a summary + path
    # (NOT the raw clipped cut), and the full text is recoverable from disk.
    monkeypatch.setenv("JARVIS_WORKSPACE", str(tmp_path))
    monkeypatch.setenv("EVE_AGENT_RESULT_INLINE_MAX", "120")
    long_text = ("Here is the executive summary. " + ("DETAIL " * 400)).strip()
    assert len(long_text) > 120

    async def scenario():
        cid, tok = store.create("hermes", "research", summary="r", delivery="push",
                                requester="W", requester_tier="owner", ttl_s=3600)
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": cid, "callback_token": tok,
                "status": "ok", "result": {"text": long_text}})
            assert r.status == 200
        # Spoken text references the saved path and is NOT the full 1200-byte wall of data.
        assert spoke
        assert "saved to" in spoke[0].lower()
        assert long_text not in spoke[0]
        # Exactly one result file, named by agent + correlation id, containing the FULL text.
        results = list((tmp_path / "agent-results").glob("*.md"))
        assert len(results) == 1
        assert cid in results[0].name
        assert results[0].read_text(encoding="utf-8") == long_text
        # Audit row records the path so check_delegations / replay can find the full work.
        assert store.get(cid)["result"]["result_path"] == str(results[0])
    _run(scenario)


def test_proposal_stage_failure_reports_honest_error(store, monkeypatch):
    # If staging raises, the proposal never reached the owner's gate — the callback must report
    # an HONEST failure (not {ok:true, staged:true}, which would silently drop the proposal).
    async def scenario():
        cid, tok = store.create("hermes", "send $", summary="send", delivery="push",
                                requester="W", requester_tier="owner", ttl_s=3600)
        import approval_store
        monkeypatch.setattr(
            approval_store, "stage",
            lambda *a, **k: (_ for _ in ()).throw(RuntimeError("db down")),
        )
        spoke = []
        async with TestClient(TestServer(_make_app(spoke))) as c:
            r = await c.post("/agent/callback/TOK", json={
                "correlation_id": cid, "callback_token": tok, "status": "ok",
                "proposal": {"action": "send_message", "to": "Marco", "text": "ok?"}})
            assert r.status == 500
            assert (await r.json())["ok"] is False
        # Nothing staged, and no false "queued for approval" announce.
        assert all(a["tool"] != "delegate_hermes_proposal" for a in approval_store.list_pending())
        assert not spoke
    _run(scenario)
