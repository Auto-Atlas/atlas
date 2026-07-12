# Tests for add_calendar_event — gated calendar WRITE via the owner's Apps Script webhook.
# CI-safe: aiohttp is mocked; no live calendar is ever touched.
import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import calendar_tool


def _params(args):
    got = {}

    async def cb(result, **k):
        got.update(result)
    return SimpleNamespace(arguments=args, result_callback=cb), got


def _mock_session(status=200, body=None):
    """An aiohttp.ClientSession mock whose post() context yields a canned response."""
    resp = MagicMock()
    resp.status = status
    resp.text = AsyncMock(return_value=json.dumps(body if body is not None else {"ok": True}))
    post_cm = MagicMock()
    post_cm.__aenter__ = AsyncMock(return_value=resp)
    post_cm.__aexit__ = AsyncMock(return_value=False)
    session = MagicMock()
    session.post = MagicMock(return_value=post_cm)
    session_cm = MagicMock()
    session_cm.__aenter__ = AsyncMock(return_value=session)
    session_cm.__aexit__ = AsyncMock(return_value=False)
    return session_cm, session


def test_add_event_not_configured_is_honest(monkeypatch):
    monkeypatch.delenv("EVE_CAL_WRITE_URL", raising=False)
    params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00"})
    asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False and "not set up" in got["error"]


def test_add_event_posts_payload_and_confirms(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    monkeypatch.setenv("EVE_CAL_WRITE_TOKEN", "sekrit")
    session_cm, session = _mock_session(body={"ok": True, "id": "ev123"})
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00",
                               "duration_min": 30, "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    payload = session.post.call_args.kwargs["json"]
    assert payload["title"] == "Dentist"
    assert payload["start"].startswith("2026-07-10T14:00")
    assert payload["duration_min"] == 30
    assert payload["token"] == "sekrit"
    assert "instruction" in got


def test_add_event_all_day(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://x/exec")
    monkeypatch.setenv("EVE_CAL_WRITE_TOKEN", "sekrit")
    session_cm, session = _mock_session()
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Farm day", "start": "2026-07-12", "all_day": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    assert session.post.call_args.kwargs["json"]["all_day"] is True


def test_add_event_bad_date_is_honest(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://x/exec")
    monkeypatch.setenv("EVE_CAL_WRITE_TOKEN", "sekrit")
    params, got = _params({"title": "Dentist", "start": "sometime thursday-ish"})
    asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False and "date" in got["error"].lower()


def test_add_event_upstream_failure_never_fakes_success(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://x/exec")
    monkeypatch.setenv("EVE_CAL_WRITE_TOKEN", "sekrit")
    session_cm, _ = _mock_session(status=500, body={"ok": False, "error": "boom"})
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00"})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False


def test_add_event_requires_title(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://x/exec")
    params, got = _params({"start": "2026-07-10 14:00"})
    asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False


# ---------------------------------------------------------------------------
# Connector-first write path (OpenJarvis gcalendar OAuth) + fallback ordering
# ---------------------------------------------------------------------------


def _enable_connector(monkeypatch, available=True, connected=True, create_error=None):
    """Turn the connector path on and stub both probe and create."""
    monkeypatch.setenv("EVE_CAL_CONNECTOR", "1")
    calls = {"created": []}

    async def fake_status():
        return available, connected

    async def fake_create(title, start_str, duration, all_day):
        if create_error:
            raise create_error
        calls["created"].append((title, start_str, duration, all_day))
        return {"id": "evt-1"}

    monkeypatch.setattr(calendar_tool, "_connector_write_status", fake_status)
    monkeypatch.setattr(calendar_tool, "_connector_create_event", fake_create)
    return calls


def test_connector_connected_writes_without_webhook(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    calls = _enable_connector(monkeypatch)
    session_cm, session = _mock_session()
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Fireworks", "start": "2026-07-03 21:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    assert got["created"]["title"] == "Fireworks"
    assert calls["created"] == [("Fireworks", "2026-07-03 21:00", 60, False)]
    session.post.assert_not_called()  # webhook untouched


def test_connector_all_day_sends_bare_date(monkeypatch):
    calls = _enable_connector(monkeypatch)
    params, got = _params({"title": "Founders Day", "start": "2026-07-03",
                           "confirmed": True})
    asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    assert calls["created"] == [("Founders Day", "2026-07-03", 60, True)]


def test_connector_not_connected_falls_back_to_webhook(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    monkeypatch.setenv("EVE_CAL_WRITE_TOKEN", "sekrit")
    _enable_connector(monkeypatch, connected=False)
    session_cm, session = _mock_session(body={"ok": True})
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    session.post.assert_called_once()  # webhook did the write


def test_connector_daemon_down_falls_back_to_webhook(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    _enable_connector(monkeypatch, available=False, connected=False)
    session_cm, session = _mock_session(body={"ok": True})
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    session.post.assert_called_once()


def test_connector_write_failure_is_honest_no_webhook_fallback(monkeypatch):
    """Connected-but-failed must NOT retry via webhook (duplicate-event risk)."""
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    _enable_connector(monkeypatch, create_error=RuntimeError("Google said no"))
    session_cm, session = _mock_session()
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Fireworks", "start": "2026-07-03 21:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False
    assert "NOT created" in got["error"]
    session.post.assert_not_called()


def test_flag_off_never_probes_connector(monkeypatch):
    """EVE_CAL_CONNECTOR=0 (the conftest default) skips the probe entirely."""
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")

    async def boom():
        raise AssertionError("connector probe must not run when flag is off")

    monkeypatch.setattr(calendar_tool, "_connector_write_status", boom)
    session_cm, session = _mock_session(body={"ok": True})
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    session.post.assert_called_once()


def test_no_connector_no_webhook_mentions_connect_tool(monkeypatch):
    monkeypatch.delenv("EVE_CAL_WRITE_URL", raising=False)
    _enable_connector(monkeypatch, available=True, connected=False)
    params, got = _params({"title": "Dentist", "start": "2026-07-10 14:00"})
    asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False
    assert "connect_google_calendar" in got["error"]


# ---------------------------------------------------------------------------
# connect_google_calendar — status/consent-link tool
# ---------------------------------------------------------------------------


class _FakeOJClient:
    base_url = "http://127.0.0.1:8000"
    detail = {}
    error = None

    def __init__(self, *a, **k):
        pass

    async def connector_detail(self, connector_id):
        assert connector_id == "gcalendar"
        if _FakeOJClient.error:
            raise _FakeOJClient.error
        return _FakeOJClient.detail


def _patch_oj(monkeypatch, detail=None, error=None):
    import openjarvis_client
    _FakeOJClient.detail = detail or {}
    _FakeOJClient.error = error
    monkeypatch.setattr(openjarvis_client, "OpenJarvisClient", _FakeOJClient)


def test_connect_tool_already_connected(monkeypatch):
    _patch_oj(monkeypatch, {"connected": True})
    params, got = _params({})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is True and got["connected"] is True


def test_connect_tool_gives_consent_link_when_creds_exist(monkeypatch):
    _patch_oj(monkeypatch, {"connected": False,
                            "oauth_setup": {"has_credentials": True}})
    params, got = _params({})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is True and got["connected"] is False
    assert got["connect_url"].endswith("/v1/connectors/gcalendar/oauth/start")


def test_connect_tool_points_to_setup_doc_without_creds(monkeypatch):
    _patch_oj(monkeypatch, {"connected": False,
                            "oauth_setup": {"has_credentials": False}})
    params, got = _params({})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["setup_needed"] == "google_oauth_client"
    assert got["setup_doc"] == "docs/google-calendar.md"


def test_connect_tool_daemon_down_is_honest(monkeypatch):
    _patch_oj(monkeypatch, error=RuntimeError("connection refused"))
    params, got = _params({})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is False and "isn't reachable" in got["error"]


# ---------------------------------------------------------------------------
# Native (self-contained) Google path — highest precedence
# ---------------------------------------------------------------------------


def _native_connected(monkeypatch, fail=None):
    import google_calendar_native as gnative
    gnative.save_tokens({"access_token": "at", "refresh_token": "rt"})
    calls = []

    async def fake_create(title, start, duration_min=60, all_day=False, **k):
        if fail:
            raise fail
        calls.append((title, start, duration_min, all_day))
        return {"id": "e1"}

    monkeypatch.setattr(gnative, "create_event", fake_create)
    return calls


def test_native_connected_wins_over_connector_and_webhook(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    calls = _native_connected(monkeypatch)

    async def probe_must_not_run():
        raise AssertionError("connector probe must not run when native is connected")

    monkeypatch.setenv("EVE_CAL_CONNECTOR", "1")
    monkeypatch.setattr(calendar_tool, "_connector_write_status", probe_must_not_run)
    session_cm, session = _mock_session()
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Fireworks", "start": "2026-07-03 21:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is True
    assert len(calls) == 1 and calls[0][0] == "Fireworks"
    assert calls[0][3] is False
    session.post.assert_not_called()


def test_native_failure_is_honest_no_fallback(monkeypatch):
    monkeypatch.setenv("EVE_CAL_WRITE_URL", "https://script.google.com/macros/s/X/exec")
    _native_connected(monkeypatch, fail=RuntimeError("token revoked"))
    session_cm, session = _mock_session()
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"title": "Fireworks", "start": "2026-07-03 21:00",
                               "confirmed": True})
        asyncio.run(calendar_tool.handle_add_calendar_event(params))
    assert got["ok"] is False and "NOT created" in got["error"]
    session.post.assert_not_called()


def test_connect_tool_native_configured_starts_flow(monkeypatch):
    import google_calendar_native as gnative
    monkeypatch.setenv("EVE_GOOGLE_CLIENT_ID", "cid.apps.googleusercontent.com")
    monkeypatch.setenv("EVE_GOOGLE_CLIENT_SECRET", "s")
    started = []
    monkeypatch.setattr(gnative, "start_connect_flow",
                        lambda: started.append(1) or "https://consent.example/x")
    params, got = _params({})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is True and got["connected"] is False
    assert got["via"] == "native"
    assert got["connect_url"] == "https://consent.example/x"
    assert started == [1]


def test_connect_tool_native_connected_reports_it(monkeypatch):
    import google_calendar_native as gnative
    gnative.save_tokens({"access_token": "at", "refresh_token": "rt"})
    params, got = _params({})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is True and got["connected"] is True and got["via"] == "native"


# ---------------------------------------------------------------------------
# get_calendar — native READ preference, ICS fallback
# ---------------------------------------------------------------------------


def test_get_calendar_prefers_native_when_connected(monkeypatch):
    import google_calendar_native as gnative
    gnative.save_tokens({"access_token": "at", "refresh_token": "rt"})

    async def fake_list(days, **k):
        assert days == 3
        return [{"what": "Fireworks", "when": "Fri Jul 03 09:00 PM",
                 "starts_at": "2026-07-03T21:00:00", "all_day": False}]

    monkeypatch.setattr(gnative, "list_events", fake_list)
    session_cm, session = _mock_session()
    with patch("calendar_tool.aiohttp.ClientSession", return_value=session_cm):
        params, got = _params({"days": 3})
        asyncio.run(calendar_tool.handle_get_calendar(params))
    assert got["ok"] is True and got["window_days"] == 3
    assert got["events"][0]["what"] == "Fireworks"
    session.get = getattr(session, "get", None)  # ICS fetch never set up/used


def test_get_calendar_native_failure_is_honest(monkeypatch):
    import google_calendar_native as gnative
    gnative.save_tokens({"access_token": "at", "refresh_token": "rt"})

    async def fake_list(days, **k):
        raise RuntimeError("token revoked")

    monkeypatch.setattr(gnative, "list_events", fake_list)
    params, got = _params({})
    asyncio.run(calendar_tool.handle_get_calendar(params))
    assert got["ok"] is False and "calendar lookup failed" in got["error"]


def test_get_calendar_not_connected_reports_missing_ics(monkeypatch):
    monkeypatch.setattr(calendar_tool, "ICS_URL", "")
    params, got = _params({})
    asyncio.run(calendar_tool.handle_get_calendar(params))
    assert got["ok"] is False and "not connected" in got["error"]


def test_disconnect_is_gated_then_revokes(monkeypatch):
    import google_calendar_native as gnative
    gnative.save_tokens({"access_token": "at", "refresh_token": "rt"})
    revoked = []

    async def fake_disconnect():
        revoked.append(1)

    monkeypatch.setattr(gnative, "disconnect", fake_disconnect)
    # First call: draft only, nothing revoked
    params, got = _params({"disconnect": True})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got.get("draft") and revoked == []
    # Confirmed call: revokes
    params, got = _params({"disconnect": True, "confirmed": True})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is True and got["connected"] is False and revoked == [1]


def test_disconnect_when_not_connected_is_a_noop():
    params, got = _params({"disconnect": True, "confirmed": True})
    asyncio.run(calendar_tool.handle_connect_google_calendar(params))
    assert got["ok"] is True and got["connected"] is False
