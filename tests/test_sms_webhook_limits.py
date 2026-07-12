# Tests for the SMS webhook abuse guards (Codex audit: tailnet DoS surface).
# pytest-aiohttp isn't installed, so each test drives aiohttp's TestServer/TestClient
# inside asyncio.run (the repo's "sync test owns its own loop" style; mirrors
# tests/test_agent_callback.py).
import asyncio
import importlib

from aiohttp.test_utils import TestClient, TestServer


def _run(scenario):
    asyncio.run(scenario())


def _fresh_module(monkeypatch, **env):
    """Reload sms_webhook with limits pinned via env, a stub send_sms (so no
    real SMS ever goes out), and a clean rate-limit table."""
    for k, v in env.items():
        monkeypatch.setenv(k, str(v))
    import sms_webhook
    importlib.reload(sms_webhook)

    async def _no_send(*a, **k):
        return None
    monkeypatch.setattr(sms_webhook, "send_sms", _no_send)
    sms_webhook._rate_hits.clear()
    return sms_webhook


def _make_client(mod):
    spoke, bcast = [], []

    async def announce(text):
        spoke.append(text)

    async def broadcast(d):
        bcast.append(d)

    app = mod.build_app(announce, broadcast)
    return app, spoke, bcast


def _token(mod):
    return mod.webhook_token()


def test_legit_small_authed_request_still_works(monkeypatch):
    mod = _fresh_module(monkeypatch, EVE_SMS_MAX_BYTES=16384, EVE_SMS_RATE_MAX=30)

    async def scenario():
        app, spoke, bcast = _make_client(mod)
        async with TestClient(TestServer(app)) as c:
            r = await c.post(
                f"/hook/{_token(mod)}/sms",
                json={"payload": {"message": "hello there", "sender": "5551234567"}},
            )
            assert r.status == 200
            assert (await r.json())["ok"] is True
        # A normal inbound text from a non-owner is announced -> handler ran.
        assert spoke and bcast
    _run(scenario)


def test_oversized_body_413_not_processed(monkeypatch):
    mod = _fresh_module(monkeypatch, EVE_SMS_MAX_BYTES=64, EVE_SMS_RATE_MAX=30)

    async def scenario():
        app, spoke, bcast = _make_client(mod)
        big = "x" * 5000
        async with TestClient(TestServer(app)) as c:
            r = await c.post(
                f"/hook/{_token(mod)}/sms",
                json={"payload": {"message": big, "sender": "5551234567"}},
            )
            assert r.status == 413
            assert (await r.json()) == {"ok": False, "error": "too large"}
        # Rejected before any downstream work.
        assert not spoke and not bcast
    _run(scenario)


def test_flood_over_limit_gets_429(monkeypatch):
    mod = _fresh_module(monkeypatch, EVE_SMS_MAX_BYTES=16384, EVE_SMS_RATE_MAX=2)

    async def scenario():
        app, spoke, bcast = _make_client(mod)
        url = f"/hook/{_token(mod)}/sms"
        body = {"payload": {"message": "hi", "sender": "5551234567"}}
        async with TestClient(TestServer(app)) as c:
            assert (await c.post(url, json=body)).status == 200
            assert (await c.post(url, json=body)).status == 200
            r3 = await c.post(url, json=body)  # N+1
            assert r3.status == 429
            assert (await r3.json()) == {"ok": False, "error": "rate limited"}
    _run(scenario)


def test_rate_window_resets_with_clock(monkeypatch):
    # Deterministic window-reset check via the injectable clock on _rate_ok
    # (no real sleeps).
    mod = _fresh_module(monkeypatch, EVE_SMS_RATE_MAX=2, EVE_SMS_RATE_WINDOW_S=60)
    src = "1.2.3.4"
    assert mod._rate_ok(src, now=1000.0) is True
    assert mod._rate_ok(src, now=1000.0) is True
    assert mod._rate_ok(src, now=1000.0) is False  # over limit in-window
    # After the window passes, old hits age out and requests are allowed again.
    assert mod._rate_ok(src, now=1100.0) is True


def test_rate_table_does_not_leak_aged_ips(monkeypatch):
    mod = _fresh_module(monkeypatch, EVE_SMS_RATE_MAX=5, EVE_SMS_RATE_WINDOW_S=60)
    mod._rate_ok("old.ip", now=0.0)
    # A later request from a different IP should prune the long-expired one.
    mod._rate_ok("new.ip", now=10_000.0)
    assert "old.ip" not in mod._rate_hits
