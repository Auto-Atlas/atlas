# Tests for google_calendar_native — EVE's self-contained Google Calendar OAuth
# connection. CI-safe: aiohttp is mocked; no live Google is ever touched.
import asyncio
import os
import stat
from datetime import datetime
from pathlib import Path

import pytest

import google_calendar_native as g


def _connect(tokens=None):
    g.save_tokens(tokens or {"access_token": "at", "refresh_token": "rt"})


def _configure(monkeypatch):
    monkeypatch.setenv("EVE_GOOGLE_CLIENT_ID", "cid.apps.googleusercontent.com")
    monkeypatch.setenv("EVE_GOOGLE_CLIENT_SECRET", "csecret")


class _FakeResp:
    def __init__(self, status, body):
        self.status = status
        self._body = body

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def json(self, content_type=None):
        return self._body


class _FakeSession:
    """Sequential canned responses for session.post; records every call."""

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def post(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        return self._responses.pop(0)

    def request(self, method, url, **kwargs):
        self.calls.append({"method": method, "url": url, **kwargs})
        return self._responses.pop(0)


def _patch_session(monkeypatch, responses):
    session = _FakeSession(responses)
    monkeypatch.setattr(g.aiohttp, "ClientSession", lambda **k: session)
    return session


# ---------------------------------------------------------------------------
# Configuration / connection state
# ---------------------------------------------------------------------------


def test_not_configured_not_connected():
    assert g.is_configured() is False
    assert g.is_connected() is False


def test_configured_when_env_set(monkeypatch):
    _configure(monkeypatch)
    assert g.is_configured() is True
    assert g.is_connected() is False  # creds alone aren't a connection


def test_connected_requires_refresh_token():
    _connect({"access_token": "at"})  # no refresh_token
    assert g.is_connected() is False
    _connect()
    assert g.is_connected() is True


def test_token_file_is_owner_only():
    _connect()
    mode = stat.S_IMODE(os.stat(g._token_path()).st_mode)
    assert mode == 0o600


def test_auth_url_narrow_scope_offline(monkeypatch):
    _configure(monkeypatch)
    url = g.auth_url()
    assert "calendar.events" in url
    assert "access_type=offline" in url
    assert "cid.apps.googleusercontent.com" in url


# ---------------------------------------------------------------------------
# create_event
# ---------------------------------------------------------------------------


def test_create_event_timed_posts_offset_aware_body(monkeypatch):
    _connect()
    session = _patch_session(monkeypatch, [
        _FakeResp(200, {"id": "e1", "htmlLink": "https://cal/e1", "summary": "Fireworks"}),
    ])
    got = asyncio.run(g.create_event("Fireworks", datetime(2026, 7, 3, 21, 0),
                                     duration_min=90))
    assert got["id"] == "e1"
    call = session.calls[0]
    assert call["url"].endswith("/calendars/primary/events")
    assert call["headers"]["Authorization"] == "Bearer at"
    body = call["json"]
    assert body["summary"] == "Fireworks"
    assert body["start"]["dateTime"].startswith("2026-07-03T21:00:00")
    assert body["end"]["dateTime"].startswith("2026-07-03T22:30:00")


def test_create_event_all_day_exclusive_end(monkeypatch):
    _connect()
    session = _patch_session(monkeypatch, [_FakeResp(200, {"id": "e2"})])
    asyncio.run(g.create_event("Founders Day", datetime(2026, 7, 3), all_day=True))
    body = session.calls[0]["json"]
    assert body["start"] == {"date": "2026-07-03"}
    assert body["end"] == {"date": "2026-07-04"}


def test_create_event_401_refreshes_and_retries(monkeypatch):
    _configure(monkeypatch)
    _connect()
    session = _patch_session(monkeypatch, [
        _FakeResp(401, {}),                                  # stale access token
        _FakeResp(200, {"access_token": "at2"}),             # refresh grant
        _FakeResp(200, {"id": "e3", "summary": "X"}),        # retried insert
    ])
    got = asyncio.run(g.create_event("X", datetime(2026, 7, 3, 21, 0)))
    assert got["id"] == "e3"
    assert session.calls[1]["data"]["grant_type"] == "refresh_token"
    assert session.calls[2]["headers"]["Authorization"] == "Bearer at2"
    assert g.load_tokens()["access_token"] == "at2"  # persisted


def test_create_event_google_rejection_raises(monkeypatch):
    _connect()
    _patch_session(monkeypatch, [
        _FakeResp(403, {"error": {"message": "Calendar API disabled"}}),
    ])
    with pytest.raises(RuntimeError) as exc:
        asyncio.run(g.create_event("X", datetime(2026, 7, 3, 21, 0)))
    assert "Calendar API disabled" in str(exc.value)


def test_refresh_without_connection_raises(monkeypatch):
    _connect({"access_token": "at"})  # nothing durable to refresh with
    _patch_session(monkeypatch, [_FakeResp(401, {})])
    with pytest.raises(RuntimeError) as exc:
        asyncio.run(g.create_event("X", datetime(2026, 7, 3, 21, 0)))
    assert "not connected" in str(exc.value)


# ---------------------------------------------------------------------------
# disconnect
# ---------------------------------------------------------------------------


def test_disconnect_revokes_and_deletes(monkeypatch):
    _connect()
    session = _patch_session(monkeypatch, [_FakeResp(200, {})])
    asyncio.run(g.disconnect())
    assert session.calls[0]["url"].endswith("/revoke")
    assert session.calls[0]["data"]["token"] == "rt"
    assert not Path(g._token_path()).exists()
    assert g.is_connected() is False


def test_disconnect_deletes_even_if_revoke_fails(monkeypatch):
    _connect()

    class _Boom:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        def post(self, *a, **k):
            raise OSError("network down")

    monkeypatch.setattr(g.aiohttp, "ClientSession", lambda **k: _Boom())
    asyncio.run(g.disconnect())
    assert not Path(g._token_path()).exists()


# ---------------------------------------------------------------------------
# list_events — calendar READ via the same consent
# ---------------------------------------------------------------------------


def test_list_events_maps_to_eve_event_shape(monkeypatch):
    _connect()
    session = _patch_session(monkeypatch, [
        _FakeResp(200, {"items": [
            {"summary": "Fireworks",
             "start": {"dateTime": "2026-07-03T21:00:00-05:00"}},
            {"summary": "Founders Day", "start": {"date": "2026-07-03"}},
            {"start": {}},  # unparseable — dropped, not crashed
        ]}),
    ])
    events = asyncio.run(g.list_events(2))
    call = session.calls[0]
    assert call["method"] == "GET"
    assert call["params"]["singleEvents"] == "true"
    assert "timeMin" in call["params"] and "timeMax" in call["params"]
    assert len(events) == 2
    all_day = [e for e in events if e["all_day"]][0]
    timed = [e for e in events if not e["all_day"]][0]
    assert all_day["what"] == "Founders Day"
    assert all_day["starts_at"].startswith("2026-07-03")
    assert timed["what"] == "Fireworks"
    assert "_sort" not in timed  # internal key stripped, like parse_ics_events


def test_list_events_401_refresh_retry(monkeypatch):
    _configure(monkeypatch)
    _connect()
    session = _patch_session(monkeypatch, [
        _FakeResp(401, {}),
        _FakeResp(200, {"access_token": "at2"}),
        _FakeResp(200, {"items": []}),
    ])
    events = asyncio.run(g.list_events(2))
    assert events == []
    assert session.calls[2]["headers"]["Authorization"] == "Bearer at2"


# ---------------------------------------------------------------------------
# OAuth flow security — state (login-CSRF guard) + PKCE
# ---------------------------------------------------------------------------


def test_auth_url_carries_state_and_pkce(monkeypatch):
    _configure(monkeypatch)
    url = g.auth_url(state="st4te", code_challenge="ch4llenge")
    assert "state=st4te" in url
    assert "code_challenge=ch4llenge" in url
    assert "code_challenge_method=S256" in url


def test_make_pkce_s256_relation():
    import base64
    import hashlib
    verifier, challenge = g._make_pkce()
    expect = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    assert challenge == expect
    assert 43 <= len(verifier) <= 128  # RFC 7636 bounds


def test_parse_callback_accepts_only_matching_state():
    code, err = g._parse_callback("/oauth/callback?code=abc&state=good", "good")
    assert code == "abc" and err is None
    code, err = g._parse_callback("/oauth/callback?code=abc&state=evil", "good")
    assert code is None and "CSRF" in err
    code, err = g._parse_callback("/oauth/callback?code=abc", "good")
    assert code is None and "CSRF" in err


def test_parse_callback_provider_error_and_missing_code():
    code, err = g._parse_callback(
        "/oauth/callback?error=access_denied&state=good", "good")
    assert code is None and err == "access_denied"
    code, err = g._parse_callback("/oauth/callback?state=good", "good")
    assert code is None and "missing authorization code" in err
