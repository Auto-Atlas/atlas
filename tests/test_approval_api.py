# Tests for approval_api — the FastAPI REST surface (auth, approvals, the approve/deny
# security spine, settings, per-speaker memory, activity).
# Real components: the real FastAPI app via Starlette TestClient + a real temp SQLite DB +
# the real release path. Sync `def` tests (TestClient owns its own loop) under
# asyncio_mode=auto — never nested in an `async def`.
import importlib

from fastapi.testclient import TestClient

TOKEN = "secret-token-1234567890123456"


def make_client(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "a.db"))
    monkeypatch.setenv("EVE_APP_TOKEN", TOKEN)
    monkeypatch.setenv("JARVIS_MEMORY_PAGE", str(tmp_path / "jarvis-memory.md"))
    monkeypatch.setenv("JARVIS_LOG_DIR", str(tmp_path / "transcripts"))
    import approval_store
    importlib.reload(approval_store)
    import memory_tool
    importlib.reload(memory_tool)
    import transcript_review
    importlib.reload(transcript_review)
    import approval_api
    importlib.reload(approval_api)
    return TestClient(approval_api.app), approval_store


def auth():
    return {"Authorization": f"Bearer {TOKEN}"}


# ---- B6: auth + health + list/get -------------------------------------------

def test_blank_token_refuses_to_start(tmp_path, monkeypatch):
    # Fail-closed is enforced at SERVER STARTUP (lifespan), not at import — importing the
    # module for tests/lint/reload must not require the secret (M3). With no token
    # configured, entering the app's lifespan raises so a misconfigured server dies at boot.
    import sys
    import pytest
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "a.db"))
    monkeypatch.setenv("EVE_APP_TOKEN", "")
    monkeypatch.setenv("EVE_APP_TOKEN_FILE", str(tmp_path / "no-such-token.txt"))
    sys.modules.pop("approval_api", None)               # force a fresh module body run
    approval_api = importlib.import_module("approval_api")  # import alone must NOT raise
    with pytest.raises(RuntimeError):
        with TestClient(approval_api.app):              # entering lifespan resolves -> fails closed
            pass


def test_health_requires_auth(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/health").status_code == 401
    assert c.get("/v1/health", headers={"Authorization": "Bearer wrong"}).status_code == 401
    r = c.get("/v1/health", headers=auth())
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert "releasing_orphans" in r.json()


def test_list_pending_returns_staged(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    store.stage("send_to_channel", {"channel": "telegram", "message": "hi"},
                requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    r = c.get("/v1/approvals?status=pending", headers=auth())
    assert r.status_code == 200
    body = r.json()
    assert len(body["approvals"]) == 1
    assert body["approvals"][0]["requester"] == "Alex"


def test_get_single_approval(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "hi"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    assert c.get(f"/v1/approvals/{aid}", headers=auth()).json()["id"] == aid
    assert c.get("/v1/approvals/nope", headers=auth()).status_code == 404


# ---- B7a: approve/deny (the security spine) ---------------------------------

def _register_capturing_handler():
    import release
    seen = []

    async def writer(params):
        seen.append(params.arguments["message"])
        await params.result_callback({"ok": True, "sent": True})

    release.register_releasable("send_to_channel", writer)
    return seen


def test_approve_releases_once_and_double_is_409(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    seen = _register_capturing_handler()
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "go"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    r1 = c.post(f"/v1/approvals/{aid}/approve", headers=auth())
    assert r1.status_code == 200 and r1.json()["ok"] is True
    assert r1.json()["released_tool"] == "send_to_channel"
    assert seen == ["go"]                                  # fired exactly once
    r2 = c.post(f"/v1/approvals/{aid}/approve", headers=auth())
    assert r2.status_code == 409 and seen == ["go"]        # no double-fire


def test_approve_rejects_non_known_tier_row(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    _register_capturing_handler()
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                      requester="Z", tier="unknown", risk="high", summary="s", ttl_s=600)
    assert c.post(f"/v1/approvals/{aid}/approve", headers=auth()).status_code == 409


def test_approve_surfaces_send_failure_honestly(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    import release

    async def failing(params):
        await params.result_callback({"ok": False, "error": "could not reach service"})

    release.register_releasable("send_to_channel", failing)
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    r = c.post(f"/v1/approvals/{aid}/approve", headers=auth())
    assert r.status_code == 200
    assert r.json()["ok"] is False                         # honest: NOT a false success
    assert "error" in r.json()["result"]


def test_deny_drops_draft(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    seen = _register_capturing_handler()
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    assert c.post(f"/v1/approvals/{aid}/deny", headers=auth()).status_code == 200
    assert store.get(aid)["status"] == "denied"
    assert c.post(f"/v1/approvals/{aid}/approve", headers=auth()).status_code == 409
    assert seen == []                                      # nothing fired


def test_releasing_orphan_is_surfaced_and_never_refires(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    _register_capturing_handler()
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    store.consume(aid)                                     # 'releasing', finish() never called (crash)
    assert len(store.list_releasing()) == 1
    assert c.get("/v1/health", headers=auth()).json()["releasing_orphans"] == 1   # surfaced
    assert c.post(f"/v1/approvals/{aid}/approve", headers=auth()).status_code == 409  # never re-fires


# ---- B7b: settings + per-speaker memory + activity --------------------------

def test_settings_toggle_roundtrip(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/settings", headers=auth()).json()["remote_approval_enabled"] is False
    c.post("/v1/settings", json={"remote_approval_enabled": True}, headers=auth())
    assert c.get("/v1/settings", headers=auth()).json()["remote_approval_enabled"] is True


def test_thinking_toggle_roundtrip(tmp_path, monkeypatch):
    # Epic T: the thinking toggle round-trips through the same /v1/settings front door,
    # independently of remote_approval_enabled (apply-if-present).
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/settings", headers=auth()).json()["thinking_enabled"] is False
    r = c.post("/v1/settings", json={"thinking_enabled": True}, headers=auth())
    assert r.json()["thinking_enabled"] is True
    assert c.get("/v1/settings", headers=auth()).json()["thinking_enabled"] is True
    # remote_approval untouched by a thinking-only POST:
    assert c.get("/v1/settings", headers=auth()).json()["remote_approval_enabled"] is False


def test_voice_brain_switch_roundtrip(tmp_path, monkeypatch):
    # The active LLM brain is switchable at runtime through /v1/settings (dynamic,
    # no .env edit) so voice can fail over between endpoints. The stored value
    # always wins over any env default — assert the round-trip, not the default.
    # Uses neutral builtin brains (gpu-box etc. are now per-box config, not builtin).
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.post("/v1/settings", json={"voice_brain": "ollama"}, headers=auth()).json()["voice_brain"] == "ollama"
    assert c.get("/v1/settings", headers=auth()).json()["voice_brain"] == "ollama"
    assert c.post("/v1/settings", json={"voice_brain": "zai"}, headers=auth()).json()["voice_brain"] == "zai"
    assert c.get("/v1/settings", headers=auth()).json()["voice_brain"] == "zai"


def test_voice_brain_rejects_unknown(tmp_path, monkeypatch):
    # A typo'd/unknown brain name is rejected with 422 instead of being stored — else
    # GET /v1/settings would report the typo as active while voice silently ran the
    # legacy 'env' brain (a control plane that lies). The prior value is preserved.
    c, _ = make_client(tmp_path, monkeypatch)
    c.post("/v1/settings", json={"voice_brain": "ollama"}, headers=auth())
    r = c.post("/v1/settings", json={"voice_brain": "gpu-box-typo"}, headers=auth())
    assert r.status_code == 422
    assert c.get("/v1/settings", headers=auth()).json()["voice_brain"] == "ollama"


def test_barge_in_toggle_roundtrip(tmp_path, monkeypatch):
    # "Let me interrupt EVE" round-trips through /v1/settings, default OFF
    # (speakerphone-safe), independently of the other toggles (apply-if-present).
    monkeypatch.delenv("JARVIS_PHONE_ALLOW_INTERRUPTIONS", raising=False)
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/settings", headers=auth()).json()["barge_in_enabled"] is False
    r = c.post("/v1/settings", json={"barge_in_enabled": True}, headers=auth())
    assert r.json()["barge_in_enabled"] is True
    assert c.get("/v1/settings", headers=auth()).json()["barge_in_enabled"] is True
    # other toggles untouched by a barge-in-only POST:
    assert c.get("/v1/settings", headers=auth()).json()["thinking_enabled"] is False
    # surfaced on /v1/health too (the app reads it from there):
    assert c.get("/v1/health", headers=auth()).json()["barge_in_enabled"] is True


def test_silence_mode_toggle_roundtrip(tmp_path, monkeypatch):
    # "Quiet unless I say the wake word" round-trips through /v1/settings, default OFF,
    # independently of the other toggles (apply-if-present) — so the phone app can grow
    # a switch later, exactly like barge_in.
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/settings", headers=auth()).json()["silence_mode_enabled"] is False
    r = c.post("/v1/settings", json={"silence_mode_enabled": True}, headers=auth())
    assert r.json()["silence_mode_enabled"] is True
    assert c.get("/v1/settings", headers=auth()).json()["silence_mode_enabled"] is True
    # other toggles untouched by a silence-only POST:
    assert c.get("/v1/settings", headers=auth()).json()["thinking_enabled"] is False
    # surfaced on /v1/health too (the app reads it from there):
    assert c.get("/v1/health", headers=auth()).json()["silence_mode_enabled"] is True


def test_memory_post_writes_explicit_speaker_bucket_not_owner(tmp_path, monkeypatch):
    # §1.8: REST has no voice turn -> route by EXPLICIT speaker, never the owner default.
    c, _ = make_client(tmp_path, monkeypatch)
    r = c.post("/v1/memory", json={"speaker": "Alex", "fact": "likes early invoices"},
               headers=auth())
    assert r.status_code == 200
    import memory_tool
    known_page = memory_tool._page_for("Alex", "known")     # two-arg (name, tier)
    assert known_page.exists() and "likes early invoices" in known_page.read_text()
    assert not (tmp_path / "jarvis-memory.md").exists()        # owner page NOT touched
    got = c.get("/v1/memory?speaker=Alex", headers=auth()).json()
    assert any("likes early invoices" in f for f in got["facts"])


def test_ws_stream_auth_via_subprotocol_not_url(tmp_path, monkeypatch):
    import pytest
    from starlette.websockets import WebSocketDisconnect
    c, store = make_client(tmp_path, monkeypatch)
    # No/!wrong token -> rejected before accept (token is NOT a URL query param).
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect("/v1/stream"):
            pass
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect("/v1/stream", subprotocols=["bearer", "wrong-token"]):
            pass
    # Correct token via Sec-WebSocket-Protocol "bearer, <token>" -> accepted; a resolved
    # event is pushed when an approval is decided.
    with c.websocket_connect("/v1/stream", subprotocols=["bearer", TOKEN]) as ws:
        aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                          requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
        c.post(f"/v1/approvals/{aid}/deny", headers=auth())
        evt = ws.receive_json()
        assert evt["type"] == "approval_resolved" and evt["id"] == aid


def test_activity_digest_returns_for_a_day(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    r = c.get("/v1/activity?day=today", headers=auth())
    assert r.status_code == 200
    assert "date" in r.json()                              # real digest shape (empty day ok)


async def test_live_forwarder_does_not_lose_partial_line_events(tmp_path, monkeypatch):
    # H1 regression: a JSONL event written in two syscalls (partial line, then the rest)
    # must NOT be dropped. The old tailer readline()'d past the newline-less tail and lost
    # it; the fix rewinds and waits for the writer to finish the line.
    import asyncio
    import importlib
    import time

    monkeypatch.setenv("EVE_APP_TOKEN", TOKEN)
    monkeypatch.setenv("JARVIS_LOG_DIR", str(tmp_path))
    monkeypatch.setenv("EVE_FORWARD_SRC", "phone")
    import approval_api
    importlib.reload(approval_api)

    today = time.strftime("%Y-%m-%d")
    path = tmp_path / f"{today}.jsonl"
    path.write_text("", encoding="utf-8")  # exists + empty -> forwarder attaches at EOF

    received: list[dict] = []

    class _Capture:
        async def broadcast(self, event):
            received.append(event)

    task = asyncio.create_task(approval_api._forward_live_events(_Capture(), poll_interval=0.02))
    try:
        await asyncio.sleep(0.1)  # let it attach at EOF
        # Write the event in two pieces, with a partial (newline-less) tail in between.
        with open(path, "a", encoding="utf-8") as fh:
            fh.write('{"type":"tool_call","src":"phone","name":"check_email"')
            fh.flush()
            await asyncio.sleep(0.1)  # forwarder sees a partial line here — must rewind, not drop
            fh.write("}\n")
            fh.flush()
        # Give the forwarder time to pick up the now-complete line.
        for _ in range(50):
            if received:
                break
            await asyncio.sleep(0.02)
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    assert received == [{"type": "tool_call", "src": "phone", "name": "check_email"}]


def test_import_boundary_no_voice_runtime_leak(tmp_path):
    # The security-relevant invariant: the approval surface must not drag in the voice
    # runtime — no jarvis_core (load_skills + the world), no bot/phone_bot (the phone
    # single-instance socket lock binds at import). Checked in a FRESH subprocess so other
    # test modules' imports can't pollute the result. (speaker_state arrives transitively
    # via memory_tool's helpers but approval_api never reads/writes it.)
    import os
    import subprocess
    import sys
    env = dict(os.environ)
    env["EVE_APP_TOKEN"] = "x" * 32
    env["EVE_APPROVAL_DB"] = str(tmp_path / "boundary.db")   # never touch the repo root
    code = (
        "import release, approval_store, approval_push, approval_api, agent_tasks, agent_callback\n"
        "import sys\n"
        "leaked=[m for m in ('jarvis_core','bot','phone_bot','tool_policy') if m in sys.modules]\n"
        "assert not leaked, leaked\n"
        "print('OK')\n"
    )
    r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=env)
    assert r.returncode == 0, f"import boundary violated: {r.stdout}\n{r.stderr}"


# ---- /v1/skills (app "Skills" surface) --------------------------------------

def test_skills_requires_auth(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/skills").status_code == 401
    assert c.get("/v1/skills", headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_skills_returns_catalog(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    r = c.get("/v1/skills", headers=auth())
    assert r.status_code == 200
    skills = r.json()["skills"]
    assert len(skills) > 0
    # Every entry has the app-facing shape and a non-empty human one-liner.
    for s in skills:
        assert set(s.keys()) == {"tool", "catalog", "risk", "requires_confirmation"}
        assert s["tool"] and s["catalog"]
        assert isinstance(s["requires_confirmation"], bool)
    # A known skill is present (get_weather has a catalog line).
    assert any(s["tool"] == "get_weather" for s in skills)


# ---- /v1/skills feed (live catalog + feed-to-EVE) ---------------------------

def test_feed_unknown_tool_404(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    r = c.post("/v1/skills/not_a_tool/feed", json={"mode": "next"}, headers=auth())
    assert r.status_code == 404


def test_feed_bad_mode_400(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    r = c.post("/v1/skills/get_weather/feed", json={"mode": "sideways"}, headers=auth())
    assert r.status_code == 400


def test_feed_enqueues_and_lists_then_clears(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    r = c.post("/v1/skills/get_weather/feed", json={"mode": "next"}, headers=auth())
    assert r.status_code == 200 and r.json()["ok"] is True
    pend = c.get("/v1/skills/feed", headers=auth()).json()["pending"]
    assert any(p["tool"] == "get_weather" and p["mode"] == "next" for p in pend)
    d = c.delete("/v1/skills/feed/get_weather", headers=auth())
    assert d.status_code == 200 and d.json()["cleared"] == 1
    pend2 = c.get("/v1/skills/feed", headers=auth()).json()["pending"]
    assert all(p["tool"] != "get_weather" for p in pend2)


def test_feed_endpoints_require_auth(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.post("/v1/skills/get_weather/feed", json={"mode": "next"}).status_code == 401
    assert c.get("/v1/skills/feed").status_code == 401
    assert c.delete("/v1/skills/feed/get_weather").status_code == 401


def test_skills_catalog_is_live_per_request(tmp_path, monkeypatch):
    # Proves decision (1): a skill added on the sidecar shows up without a restart. We swap the
    # loader the endpoint calls between the two GETs; the second response must reflect the change.
    import skill_loader
    from skill_loader import Skill
    c, _ = make_client(tmp_path, monkeypatch)

    weather = Skill("get_weather", "low", False, "call", "Weather.", "body")
    invoice = Skill("create_invoice", "high", True, "call", "Invoice.", "body")
    calls = {"n": 0}

    def fake_load(*_a, **_k):
        calls["n"] += 1
        first = {weather.tool: weather}
        both = {weather.tool: weather, invoice.tool: invoice}
        return first if calls["n"] == 1 else both

    monkeypatch.setattr(skill_loader, "load_skills", fake_load)
    first = {s["tool"] for s in c.get("/v1/skills", headers=auth()).json()["skills"]}
    second = {s["tool"] for s in c.get("/v1/skills", headers=auth()).json()["skills"]}
    assert "create_invoice" not in first
    assert "create_invoice" in second  # appeared without any restart → loaded live per request


def test_memory_no_speaker_reads_and_writes_owner_page(tmp_path, monkeypatch):
    # The app's Memory tab: no speaker -> the OWNER page (jarvis-memory.md), the real memory.
    c, _ = make_client(tmp_path, monkeypatch)
    # Empty owner memory to start.
    assert c.get("/v1/memory", headers=auth()).json()["facts"] == []
    # Add a fact with NO speaker -> lands on the owner page.
    r = c.post("/v1/memory", json={"fact": "Q3 goal: 10 automation clients"}, headers=auth())
    assert r.status_code == 200 and r.json()["ok"] is True
    assert (tmp_path / "jarvis-memory.md").exists()  # owner page written
    got = c.get("/v1/memory", headers=auth()).json()
    assert any("Q3 goal" in f for f in got["facts"])
    # An explicit speaker is still isolated from the owner page.
    c.post("/v1/memory", json={"speaker": "Alex", "fact": "ash fact"}, headers=auth())
    owner = c.get("/v1/memory", headers=auth()).json()["facts"]
    assert all("ash fact" not in f for f in owner)  # owner view never shows Alex's bucket


async def test_live_forwarder_carries_agent_talkback_events_regardless_of_src(tmp_path, monkeypatch):
    # Agent talk-back lifecycle events (Hermes/Claude/Codex doing real delegated work) are
    # owner-global, not surface chatter: they arrive in the DESKTOP voice loop (src
    # "local"), so the phone-only src filter must NOT apply to them — while still applying
    # to everything else (the desktop tool-leak regression the filter exists for).
    import asyncio
    import importlib
    import time

    monkeypatch.setenv("EVE_APP_TOKEN", TOKEN)
    monkeypatch.setenv("JARVIS_LOG_DIR", str(tmp_path))
    monkeypatch.setenv("EVE_FORWARD_SRC", "phone")
    import approval_api
    importlib.reload(approval_api)

    today = time.strftime("%Y-%m-%d")
    path = tmp_path / f"{today}.jsonl"
    path.write_text("", encoding="utf-8")

    received: list[dict] = []

    class _Capture:
        async def broadcast(self, event):
            received.append(event)

    task = asyncio.create_task(approval_api._forward_live_events(_Capture(), poll_interval=0.02))
    try:
        await asyncio.sleep(0.1)  # attach at EOF
        with open(path, "a", encoding="utf-8") as fh:
            fh.write('{"type":"agent_progress","src":"local","agent":"hermes",'
                     '"task_id":"t1","text":"cloning repo"}\n')
            fh.write('{"type":"tool_call","src":"local","name":"check_email"}\n')
            fh.write('{"type":"agent_task_cancelled","src":"local","agent":"claude",'
                     '"task_id":"t2"}\n')
            fh.flush()
        for _ in range(50):
            if len(received) >= 2:
                break
            await asyncio.sleep(0.02)
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    types = [e["type"] for e in received]
    assert types == ["agent_progress", "agent_task_cancelled"], (
        f"expected both agent events and NOT the desktop tool_call; got {types}")


# ---- /v1/agent-tasks (live delegation activity + cancel) ---------------------

def _mint_task(delivery="push", agent="hermes"):
    import agent_tasks
    return agent_tasks.create(agent, "long research job", summary="long research job",
                              delivery=delivery, requester="the-owner",
                              requester_tier="owner", ttl_s=3600)


def test_agent_tasks_list_requires_auth(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    assert c.get("/v1/agent-tasks").status_code == 401


def test_agent_tasks_list_returns_active_without_secrets(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    cid, _tok = _mint_task()
    r = c.get("/v1/agent-tasks", headers=auth())
    assert r.status_code == 200
    active = r.json()["active"]
    assert [t["id"] for t in active] == [cid]
    t = active[0]
    assert t["agent"] == "hermes" and t["status"] == "pending"
    # The callback capability and claim fencing token must NEVER leave the server.
    flat = str(r.json())
    assert "callback_token" not in flat and "claim_token" not in flat


def test_cancel_running_push_task_is_honest_cooperative(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    import agent_tasks
    cid, _tok = _mint_task(delivery="push")
    r = c.post(f"/v1/agent-tasks/{cid}/cancel", headers=auth())
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "cancel_requested"        # honest: stop not yet observed
    assert "check-in" in body["detail"].lower() or "stopping" in body["detail"].lower()
    assert agent_tasks.get(cid)["status"] == agent_tasks.CANCEL_REQUESTED


def test_cancel_unstarted_poll_task_is_immediate(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    import agent_tasks
    cid, _tok = _mint_task(delivery="poll")
    r = c.post(f"/v1/agent-tasks/{cid}/cancel", headers=auth())
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    assert agent_tasks.get(cid)["status"] == agent_tasks.CANCELLED


def test_cancel_unknown_task_404_and_terminal_409(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    import agent_tasks
    assert c.post("/v1/agent-tasks/nope/cancel", headers=auth()).status_code == 404
    cid, _tok = _mint_task()
    agent_tasks.resolve(cid)
    agent_tasks.finish(cid, {"ok": True, "text": "done"})
    r = c.post(f"/v1/agent-tasks/{cid}/cancel", headers=auth())
    assert r.status_code == 409                          # already finished — nothing to cancel


def test_cancel_broadcasts_event_and_closes_question_card(tmp_path, monkeypatch):
    c, store = make_client(tmp_path, monkeypatch)
    import agent_tasks
    cid, _tok = _mint_task()
    # Simulate an outstanding question with its owner-pinned card.
    aid = store.stage("resume_hermes", {"cid": cid, "qid": "q1", "question": "env?"},
                      requester="the-owner", tier="owner", risk="high",
                      summary="hermes is asking", ttl_s=600)
    agent_tasks.set_awaiting_user_cas(cid, {"qid": "q1", "question": "env?",
                                            "approval_id": aid})
    with c.websocket_connect("/v1/stream", subprotocols=["bearer", TOKEN]) as ws:
        r = c.post(f"/v1/agent-tasks/{cid}/cancel", headers=auth())
        assert r.status_code == 200
        evt = ws.receive_json()
        assert evt["type"] == "agent_task_cancelled" and evt["task_id"] == cid
        assert evt["status"] == "cancel_requested"
    assert store.get(aid)["status"] != "pending"         # moot card closed


def test_redirect_running_task_stages_steer_and_broadcasts(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    import agent_tasks
    cid, _tok = _mint_task(delivery="push")
    with c.websocket_connect("/v1/stream", subprotocols=["bearer", TOKEN]) as ws:
        r = c.post(f"/v1/agent-tasks/{cid}/redirect", headers=auth(),
                   json={"instructions": "narrow it to the pricing page"})
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "redirect_pending"     # honest: lands at next check-in
        assert "check-in" in body["detail"].lower()
        evt = ws.receive_json()
        assert evt["type"] == "agent_task_redirected" and evt["task_id"] == cid
        assert evt["status"] == "redirect_pending"
    assert agent_tasks.get(cid)["redirect"] == "narrow it to the pricing page"


def test_redirect_validation_404_terminal_409_empty_400(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    import agent_tasks
    assert c.post("/v1/agent-tasks/nope/redirect", headers=auth(),
                  json={"instructions": "x"}).status_code == 404
    cid, _tok = _mint_task()
    assert c.post(f"/v1/agent-tasks/{cid}/redirect", headers=auth(),
                  json={"instructions": "  "}).status_code == 400
    agent_tasks.request_cancel(cid)
    r = c.post(f"/v1/agent-tasks/{cid}/redirect", headers=auth(),
               json={"instructions": "x"})
    assert r.status_code == 409                          # cancel outranks steer


def test_agent_tasks_list_carries_honest_capabilities(tmp_path, monkeypatch):
    c, _ = make_client(tmp_path, monkeypatch)
    cid, _tok = _mint_task(agent="hermes")
    r = c.get("/v1/agent-tasks", headers=auth())
    t = r.json()["active"][0]
    caps = t["capabilities"]
    assert caps["cancel"] is True
    assert caps["redirect"] is True and caps.get("redirect_reason") in (None, "")


async def test_live_forwarder_carries_brain_delegations_from_any_src(tmp_path, monkeypatch):
    # "See what claude code / codex is doing": jarvis_agent brain delegations run in the
    # DESKTOP voice loop (src local) — delegation activity is owner-global exactly like
    # agent talk-back, so it crosses src-independently; tool_call/thinking keep the phone
    # gate (the desktop tool-leak regression stays fixed).
    import asyncio
    import importlib
    import time

    monkeypatch.setenv("EVE_APP_TOKEN", TOKEN)
    monkeypatch.setenv("JARVIS_LOG_DIR", str(tmp_path))
    monkeypatch.setenv("EVE_FORWARD_SRC", "phone")
    import approval_api
    importlib.reload(approval_api)

    today = time.strftime("%Y-%m-%d")
    path = tmp_path / f"{today}.jsonl"
    path.write_text("", encoding="utf-8")

    received: list[dict] = []

    class _Capture:
        async def broadcast(self, event):
            received.append(event)

    task = asyncio.create_task(approval_api._forward_live_events(_Capture(), poll_interval=0.02))
    try:
        await asyncio.sleep(0.1)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write('{"type":"delegation_start","src":"local","deleg_id":"d1",'
                     '"task":"fix the bug","brains":["acp"]}\n')
            fh.write('{"type":"thinking","src":"local","active":true}\n')
            fh.write('{"type":"delegation_step","src":"local","deleg_id":"d1",'
                     '"brain":"acp","phase":"working","detail":"25s"}\n')
            fh.write('{"type":"delegation_end","src":"local","deleg_id":"d1",'
                     '"brain":"acp","ok":true,"result":"done"}\n')
            fh.flush()
        for _ in range(50):
            if len(received) >= 3:
                break
            await asyncio.sleep(0.02)
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    types = [e["type"] for e in received]
    assert types == ["delegation_start", "delegation_step", "delegation_end"], (
        f"expected the full desktop brain-delegation trace and NOT thinking; got {types}")
