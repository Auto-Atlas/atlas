"""Tests for openjarvis_client.OpenJarvisClient.

The aiohttp network is fully mocked — no daemon is contacted. A fake
session/response (async context managers) is monkeypatched in place of
aiohttp.ClientSession so we exercise only the client's own logic:
parsing the useful part out of 2xx responses, and raising an honest
RuntimeError (with path + status) on any non-2xx.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import openjarvis_client as ojc  # noqa: E402


class _FakeResponse:
    def __init__(self, status, json_body=None, text_body=""):
        self.status = status
        self._json = json_body if json_body is not None else {}
        self._text = text_body

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def json(self):
        return self._json

    async def text(self):
        return self._text


class _FakeSession:
    """Captures the request and returns a preset _FakeResponse.

    Mirrors aiohttp.ClientSession's async-context-manager + request methods.
    """

    last_request = None

    def __init__(self, response, **kwargs):
        self._response = response
        self.init_kwargs = kwargs

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def request(self, method, url, **kwargs):
        _FakeSession.last_request = {"method": method, "url": url, "kwargs": kwargs}
        return self._response


def _patch_session(monkeypatch, response):
    def factory(*args, **kwargs):
        return _FakeSession(response, **kwargs)

    monkeypatch.setattr(ojc.aiohttp, "ClientSession", factory)


async def test_memory_search_returns_results_list(monkeypatch):
    results = [{"content": "hi", "score": 0.9, "metadata": {}}]
    _patch_session(monkeypatch, _FakeResponse(200, {"results": results}))

    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000")
    out = await client.memory_search("greeting", top_k=3)

    assert out == results
    req = _FakeSession.last_request
    assert req["url"].endswith("/v1/memory/search")
    assert req["kwargs"]["json"] == {"query": "greeting", "top_k": 3}


async def test_channel_send_returns_dict(monkeypatch):
    body = {"status": "sent", "channel": "sms"}
    _patch_session(monkeypatch, _FakeResponse(200, body))

    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000")
    out = await client.channel_send("sms", "hello", conversation_id="c1")

    assert out == body
    req = _FakeSession.last_request
    assert req["url"].endswith("/v1/channels/send")
    assert req["kwargs"]["json"] == {
        "channel": "sms",
        "content": "hello",
        "conversation_id": "c1",
    }


async def test_non_2xx_raises_runtimeerror_with_path_and_status(monkeypatch):
    _patch_session(monkeypatch, _FakeResponse(502, text_body="upstream send failed"))

    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000")
    with pytest.raises(RuntimeError) as excinfo:
        await client.channel_send("sms", "hello")

    msg = str(excinfo.value)
    assert "/v1/channels/send" in msg
    assert "502" in msg
    assert "upstream send failed" in msg


async def test_memory_store_returns_parsed_dict(monkeypatch):
    body = {"status": "stored"}
    _patch_session(monkeypatch, _FakeResponse(200, body))

    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000")
    out = await client.memory_store("remember this", metadata={"tag": "x"})

    assert out == body
    req = _FakeSession.last_request
    assert req["kwargs"]["json"] == {
        "content": "remember this",
        "metadata": {"tag": "x"},
    }


async def test_list_channels_returns_channels_list(monkeypatch):
    channels = [{"id": "sms"}, {"id": "email"}]
    _patch_session(monkeypatch, _FakeResponse(200, {"channels": channels, "status": "ok"}))

    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000")
    out = await client.list_channels()

    assert out == channels
    req = _FakeSession.last_request
    assert req["method"] == "GET"
    assert req["url"].endswith("/v1/channels")


async def test_bearer_header_only_when_token_set(monkeypatch):
    _patch_session(monkeypatch, _FakeResponse(200, {"results": []}))

    # token set -> Authorization present
    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000", token="secret")
    assert client._headers()["Authorization"] == "Bearer secret"

    # no token -> no Authorization header
    client2 = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000", token="")
    assert "Authorization" not in client2._headers()


def test_base_url_trailing_slash_stripped():
    client = ojc.OpenJarvisClient(base_url="http://127.0.0.1:8000/")
    assert client.base_url == "http://127.0.0.1:8000"


async def test_connector_detail_returns_status_dict(monkeypatch):
    body = {"connector_id": "gcalendar", "connected": True, "auth_url": "https://x"}
    _patch_session(monkeypatch, _FakeResponse(200, body))
    got = await ojc.OpenJarvisClient().connector_detail("gcalendar")
    assert got["connected"] is True
    req = _FakeSession.last_request
    assert req["method"] == "GET"
    assert req["url"].endswith("/v1/connectors/gcalendar")


async def test_gcalendar_create_event_posts_and_returns_event(monkeypatch):
    event = {"id": "evt-9", "htmlLink": "https://cal/evt-9", "summary": "Fireworks"}
    _patch_session(monkeypatch, _FakeResponse(200, {"event": event}))
    got = await ojc.OpenJarvisClient().gcalendar_create_event(
        "Fireworks", "2026-07-03 21:00", duration_min=90
    )
    assert got == event
    req = _FakeSession.last_request
    assert req["method"] == "POST"
    assert req["url"].endswith("/v1/connectors/gcalendar/events")
    sent = req["kwargs"]["json"]
    assert sent["title"] == "Fireworks"
    assert sent["start"] == "2026-07-03 21:00"
    assert sent["duration_min"] == 90
    assert sent["all_day"] is False
    assert sent["calendar_id"] == "primary"


async def test_gcalendar_create_event_non_2xx_raises(monkeypatch):
    _patch_session(monkeypatch, _FakeResponse(400, text_body="not connected"))
    with pytest.raises(RuntimeError) as exc:
        await ojc.OpenJarvisClient().gcalendar_create_event("X", "2026-07-03 21:00")
    assert "/v1/connectors/gcalendar/events" in str(exc.value)
    assert "400" in str(exc.value)
