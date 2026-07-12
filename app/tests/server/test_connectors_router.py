"""Tests for the /v1/connectors API router."""

from __future__ import annotations

from pathlib import Path

import pytest


@pytest.fixture
def app():
    try:
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
    except ImportError:
        pytest.skip("fastapi not installed")

    from openjarvis.server.connectors_router import create_connectors_router

    _app = FastAPI()
    router = create_connectors_router()
    # The router already carries ``prefix="/v1/connectors"``. The real
    # ``server/app.py`` mounts it with ``include_router(router)`` — adding
    # a second ``prefix="/v1"`` here would produce ``/v1/v1/connectors``
    # and every request would 404.
    _app.include_router(router)
    return TestClient(_app)


def test_list_connectors(app):
    """GET /v1/connectors returns a list that includes the obsidian connector."""
    resp = app.get("/v1/connectors")
    assert resp.status_code == 200
    data = resp.json()
    assert "connectors" in data
    ids = [c["connector_id"] for c in data["connectors"]]
    assert "obsidian" in ids


def test_connector_detail(app):
    """GET /v1/connectors/obsidian returns the expected fields."""
    resp = app.get("/v1/connectors/obsidian")
    assert resp.status_code == 200
    data = resp.json()
    assert data["connector_id"] == "obsidian"
    assert "display_name" in data
    assert "auth_type" in data
    assert "connected" in data
    assert "mcp_tools" in data


def test_connector_not_found(app):
    """GET /v1/connectors/nonexistent returns 404."""
    resp = app.get("/v1/connectors/nonexistent")
    assert resp.status_code == 404


def test_connect_obsidian(app, tmp_path):
    """POST /v1/connectors/obsidian/connect with a valid path marks it connected."""
    # Create a minimal vault directory so is_connected() returns True.
    vault = tmp_path / "vault"
    vault.mkdir()

    resp = app.post("/v1/connectors/obsidian/connect", json={"path": str(vault)})
    assert resp.status_code == 200
    data = resp.json()
    assert data["connector_id"] == "obsidian"
    assert data["connected"] is True


def test_disconnect(app):
    """POST /v1/connectors/obsidian/disconnect returns 200 with connected=False."""
    resp = app.post("/v1/connectors/obsidian/disconnect")
    assert resp.status_code == 200
    data = resp.json()
    assert data["connector_id"] == "obsidian"
    assert data["connected"] is False


def test_sync_status(app):
    """GET /v1/connectors/obsidian/sync returns a response with a state field."""
    resp = app.get("/v1/connectors/obsidian/sync")
    assert resp.status_code == 200
    data = resp.json()
    assert "state" in data
    assert data["connector_id"] == "obsidian"


def test_trigger_sync(app, tmp_path: Path) -> None:
    """POST /v1/connectors/obsidian/sync triggers an incremental sync.

    The endpoint is intentionally fire-and-forget — it starts the sync in
    a background thread and returns immediately with ``status=started``.
    Sync progress is observable via the separate ``GET .../sync`` endpoint.
    """
    vault = tmp_path / "vault"
    vault.mkdir()
    (vault / "note.md").write_text("# Test note\n\nContent here.")
    app.post("/v1/connectors/obsidian/connect", json={"path": str(vault)})
    resp = app.post("/v1/connectors/obsidian/sync")
    assert resp.status_code == 200
    data = resp.json()
    assert data["connector_id"] == "obsidian"
    assert data["status"] in {"started", "already_syncing"}


# ---------------------------------------------------------------------------
# Connect-time credential validation (GH #409): the /connect endpoint must
# reject invalid credentials with HTTP 400 and never persist (or overwrite)
# anything on disk when validation fails.
# ---------------------------------------------------------------------------


def test_connect_slack_bot_token_returns_400(app, tmp_path: Path) -> None:
    """POST connect with an xoxb- token is rejected 400 and writes nothing."""
    from openjarvis.connectors.slack_connector import SlackConnector
    from openjarvis.server.connectors_router import _instances

    creds = tmp_path / "slack.json"
    _instances["slack"] = SlackConnector(credentials_path=str(creds))
    try:
        resp = app.post(
            "/v1/connectors/slack/connect", json={"token": "xoxb-fake-token"}
        )
        assert resp.status_code == 400
        assert "xoxb" in resp.json()["detail"].lower()
        assert not creds.exists()
    finally:
        _instances.pop("slack", None)


def test_connect_granola_invalid_key_returns_400_keeps_existing(
    app, tmp_path: Path
) -> None:
    """A bad Granola key is rejected 400 and the existing credential survives."""
    import json
    from unittest.mock import patch

    from openjarvis.connectors.granola import GranolaConnector, GranolaKeyError
    from openjarvis.server.connectors_router import _instances

    creds = tmp_path / "granola.json"
    creds.write_text(json.dumps({"token": "grl_real_existing_key"}))
    _instances["granola"] = GranolaConnector(credentials_path=str(creds))
    try:
        with patch(
            "openjarvis.connectors.granola._granola_api_validate_key",
            side_effect=GranolaKeyError(
                "Invalid API key. Check your key in Granola Settings → API."
            ),
        ):
            resp = app.post(
                "/v1/connectors/granola/connect",
                json={"code": "fake-key-12345"},
            )
        assert resp.status_code == 400
        assert "Invalid API key" in resp.json()["detail"]
        # The previously-working credential must be untouched.
        assert json.loads(creds.read_text())["token"] == "grl_real_existing_key"
    finally:
        _instances.pop("granola", None)


# ---------------------------------------------------------------------------
# POST /{connector_id}/events — calendar event creation
# ---------------------------------------------------------------------------


class _FakeCalendarConnector:
    """Minimal connector exposing create_event, injected into the router cache."""

    display_name = "Fake Calendar"
    auth_type = "oauth"

    def __init__(self, connected=True, raises=None):
        self._connected = connected
        self._raises = raises
        self.calls = []

    def is_connected(self):
        return self._connected

    def create_event(self, title, start, **kwargs):
        if self._raises:
            raise self._raises
        self.calls.append((title, start, kwargs))
        return {"id": "evt-1", "htmlLink": "https://cal/evt-1", "summary": title}


@pytest.fixture
def gcal_events_app(app):
    """TestClient plus a fake gcalendar instance planted in the router cache."""
    from openjarvis.server import connectors_router as cr

    fake = _FakeCalendarConnector()
    cr._instances["gcalendar"] = fake
    yield app, fake
    cr._instances.pop("gcalendar", None)


def test_create_event_success(gcal_events_app):
    """POST /v1/connectors/gcalendar/events forwards args and returns the event."""
    client, fake = gcal_events_app
    resp = client.post(
        "/v1/connectors/gcalendar/events",
        json={"title": "Fireworks", "start": "2026-07-03 21:00", "duration_min": 90},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["event"]["id"] == "evt-1"
    title, start, kwargs = fake.calls[0]
    assert (title, start) == ("Fireworks", "2026-07-03 21:00")
    assert kwargs["duration_min"] == 90
    assert kwargs["calendar_id"] == "primary"


def test_create_event_not_connected(gcal_events_app):
    """A disconnected connector yields 400, not a Google error."""
    client, fake = gcal_events_app
    fake._connected = False
    resp = client.post(
        "/v1/connectors/gcalendar/events",
        json={"title": "X", "start": "2026-07-03 21:00"},
    )
    assert resp.status_code == 400
    assert "not connected" in resp.json()["detail"]


def test_create_event_unsupported_connector(app):
    """Connectors without create_event (obsidian) return 400."""
    resp = app.post(
        "/v1/connectors/obsidian/events",
        json={"title": "X", "start": "2026-07-03 21:00"},
    )
    assert resp.status_code == 400
    assert "does not support" in resp.json()["detail"]


def test_create_event_unknown_connector(app):
    resp = app.post(
        "/v1/connectors/nonexistent/events",
        json={"title": "X", "start": "2026-07-03 21:00"},
    )
    assert resp.status_code == 404


def test_create_event_validation_error_is_422(gcal_events_app):
    """Connector ValueError (bad start format) maps to HTTP 422."""
    client, fake = gcal_events_app
    fake._raises = ValueError("Unparseable start")
    resp = client.post(
        "/v1/connectors/gcalendar/events",
        json={"title": "X", "start": "whenever"},
    )
    assert resp.status_code == 422
    assert "Unparseable" in resp.json()["detail"]


def test_create_event_upstream_failure_is_502(gcal_events_app):
    """Google-side failures surface as 502 with a translated message."""
    client, fake = gcal_events_app
    fake._raises = RuntimeError("401 Unauthorized")
    resp = client.post(
        "/v1/connectors/gcalendar/events",
        json={"title": "X", "start": "2026-07-03 21:00"},
    )
    assert resp.status_code == 502
    assert "Authentication failed" in resp.json()["detail"]
