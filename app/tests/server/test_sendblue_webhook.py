"""Integration tests for the SendBlue webhook endpoint.

Tests the /webhooks/sendblue route, health check endpoint, and the
full flow from incoming webhook -> bridge -> agent -> send response.
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

pytest.importorskip("fastapi", reason="openjarvis[server] not installed")

from fastapi import FastAPI  # noqa: E402
from starlette.testclient import TestClient  # noqa: E402

from openjarvis.core.registry import ChannelRegistry  # noqa: E402


@pytest.fixture(autouse=True)
def _register_sendblue():
    if not ChannelRegistry.contains("sendblue"):
        from openjarvis.channels.sendblue import SendBlueChannel

        ChannelRegistry.register_value("sendblue", SendBlueChannel)


@pytest.fixture
def mock_bridge():
    bridge = MagicMock()
    bridge.handle_incoming.return_value = "Here are your results..."
    return bridge


WEBHOOK_SECRET = "whsec_test"
SIGNED_HEADERS = {"x-sendblue-secret": WEBHOOK_SECRET}


@pytest.fixture
def sendblue_channel():
    """A channel WITH a webhook secret — the production-correct shape.

    Verification is only possible when a secret is configured, so the
    happy-path tests below carry the matching ``x-sendblue-secret`` header.
    Unsigned traffic to a bridge fails closed (503) — see TestSendBlueFailClosed.
    """
    from openjarvis.channels.sendblue import SendBlueChannel

    ch = SendBlueChannel(
        api_key_id="test_key",
        api_secret_key="test_secret",
        from_number="+15551234567",
        webhook_secret=WEBHOOK_SECRET,
    )
    ch.connect()
    return ch


@pytest.fixture
def webhook_app(mock_bridge, sendblue_channel):
    from openjarvis.server.webhook_routes import create_webhook_router

    app = FastAPI()
    router = create_webhook_router(
        bridge=mock_bridge,
        sendblue_channel=sendblue_channel,
    )
    app.include_router(router)
    return app


@pytest.fixture
def client(webhook_app):
    return TestClient(webhook_app)


# ---------------------------------------------------------------------------
# Webhook endpoint — happy paths (signed requests pass verification)
# ---------------------------------------------------------------------------


class TestSendBlueWebhook:
    def test_incoming_message_returns_200(self, client):
        resp = client.post(
            "/webhooks/sendblue",
            json={
                "from_number": "+19127130720",
                "to_number": "+15551234567",
                "content": "Hello Jarvis",
                "message_handle": "msg-001",
                "is_outbound": False,
                "status": "RECEIVED",
                "service": "iMessage",
            },
            headers=SIGNED_HEADERS,
        )
        assert resp.status_code == 200

    def test_outbound_status_callback_ignored(self, client, mock_bridge):
        resp = client.post(
            "/webhooks/sendblue",
            json={
                "from_number": "+15551234567",
                "content": "Sent message",
                "is_outbound": True,
            },
            headers=SIGNED_HEADERS,
        )
        assert resp.status_code == 200
        mock_bridge.handle_incoming.assert_not_called()

    def test_empty_content_ignored(self, client, mock_bridge):
        resp = client.post(
            "/webhooks/sendblue",
            json={
                "from_number": "+19127130720",
                "content": "",
                "is_outbound": False,
            },
            headers=SIGNED_HEADERS,
        )
        assert resp.status_code == 200
        mock_bridge.handle_incoming.assert_not_called()

    def test_missing_from_number_ignored(self, client, mock_bridge):
        resp = client.post(
            "/webhooks/sendblue",
            json={
                "content": "Hello",
                "is_outbound": False,
            },
            headers=SIGNED_HEADERS,
        )
        assert resp.status_code == 200
        mock_bridge.handle_incoming.assert_not_called()


# ---------------------------------------------------------------------------
# Fail-closed contract — unsigned traffic that would reach a bridge is rejected
# ---------------------------------------------------------------------------


class TestSendBlueFailClosed:
    def _bridge(self):
        bridge = MagicMock()
        bridge.handle_incoming.return_value = "..."
        return bridge

    def test_unsigned_with_secret_configured_rejected_403(self, mock_bridge):
        """Secret configured + missing/forged signature -> 403 (forged)."""
        from openjarvis.channels.sendblue import SendBlueChannel
        from openjarvis.server.webhook_routes import create_webhook_router

        ch = SendBlueChannel(
            api_key_id="k",
            api_secret_key="s",
            from_number="+1555",
            webhook_secret="mysecret",
        )
        ch.connect()

        app = FastAPI()
        app.include_router(
            create_webhook_router(bridge=mock_bridge, sendblue_channel=ch)
        )
        c = TestClient(app)

        # No secret header -> rejected as forged
        resp = c.post(
            "/webhooks/sendblue",
            json={"from_number": "+19127130720", "content": "Hi", "is_outbound": False},
        )
        assert resp.status_code == 403
        mock_bridge.handle_incoming.assert_not_called()

        # Correct secret header -> accepted
        resp = c.post(
            "/webhooks/sendblue",
            json={
                "from_number": "+19127130720",
                "content": "Hi",
                "is_outbound": False,
                "message_handle": "msg-002",
            },
            headers={"x-sendblue-secret": "mysecret"},
        )
        assert resp.status_code == 200

    def test_channel_without_secret_but_bridge_rejected_503(self, mock_bridge):
        """Channel present, NO webhook_secret, bridge present -> 503 fail-closed."""
        from openjarvis.channels.sendblue import SendBlueChannel
        from openjarvis.server.webhook_routes import create_webhook_router

        ch = SendBlueChannel(
            api_key_id="k", api_secret_key="s", from_number="+1555"
        )  # no webhook_secret
        ch.connect()

        app = FastAPI()
        app.include_router(
            create_webhook_router(bridge=mock_bridge, sendblue_channel=ch)
        )
        c = TestClient(app)

        resp = c.post(
            "/webhooks/sendblue",
            json={"from_number": "+19127130720", "content": "Hi", "is_outbound": False},
        )
        assert resp.status_code == 503
        mock_bridge.handle_incoming.assert_not_called()

    def test_no_channel_but_dynamic_bridge_rejected_503(self, mock_bridge):
        """THE bug: no sendblue_channel, but a dynamic bridge on app.state.

        Without the fix an unsigned webhook here is silently processed. The
        fail-closed contract requires a 503 because the message would reach the
        bridge but cannot be signature-verified.
        """
        from openjarvis.server.webhook_routes import create_webhook_router

        app = FastAPI()
        # No sendblue_channel passed; bridge wired dynamically onto app.state.
        app.state.channel_bridge = mock_bridge
        app.include_router(
            create_webhook_router(bridge=None, sendblue_channel=None)
        )
        c = TestClient(app)

        resp = c.post(
            "/webhooks/sendblue",
            json={"from_number": "+19127130720", "content": "Hi", "is_outbound": False},
        )
        assert resp.status_code == 503
        mock_bridge.handle_incoming.assert_not_called()

    def test_no_channel_no_bridge_returns_200(self, mock_bridge):
        """No channel and no bridge: nothing is processed, nothing to fail closed."""
        from openjarvis.server.webhook_routes import create_webhook_router

        app = FastAPI()
        app.include_router(
            create_webhook_router(bridge=None, sendblue_channel=None)
        )
        c = TestClient(app)

        resp = c.post(
            "/webhooks/sendblue",
            json={"from_number": "+19127130720", "content": "Hi", "is_outbound": False},
        )
        assert resp.status_code == 200
        mock_bridge.handle_incoming.assert_not_called()

    def test_no_bridge_returns_200(self, sendblue_channel):
        """Channel present (with secret) but no bridge -> not processed, 200."""
        from openjarvis.server.webhook_routes import create_webhook_router

        app = FastAPI()
        app.include_router(
            create_webhook_router(bridge=None, sendblue_channel=sendblue_channel)
        )
        c = TestClient(app)

        resp = c.post(
            "/webhooks/sendblue",
            json={"from_number": "+19127130720", "content": "Hi", "is_outbound": False},
            headers=SIGNED_HEADERS,
        )
        assert resp.status_code == 200

    def test_override_flag_allows_unsigned(self, monkeypatch, mock_bridge):
        """OPENJARVIS_ALLOW_UNSIGNED_WEBHOOKS=1 is an explicit operator opt-in."""
        monkeypatch.setenv("OPENJARVIS_ALLOW_UNSIGNED_WEBHOOKS", "1")
        from openjarvis.server.webhook_routes import create_webhook_router

        app = FastAPI()
        app.state.channel_bridge = mock_bridge
        app.include_router(
            create_webhook_router(bridge=None, sendblue_channel=None)
        )
        c = TestClient(app)

        resp = c.post(
            "/webhooks/sendblue",
            json={"from_number": "+19127130720", "content": "Hi", "is_outbound": False},
        )
        assert resp.status_code == 200


# ---------------------------------------------------------------------------
# Health endpoint (requires agent_manager_routes)
# ---------------------------------------------------------------------------


class TestSendBlueHealth:
    @pytest.fixture
    def health_app(self, sendblue_channel):
        app = FastAPI()
        app.state.sendblue_channel = sendblue_channel
        app.state.channel_bridge = MagicMock()
        app.state.channel_bridge._channels = {"sendblue": sendblue_channel}

        from openjarvis.server.agent_manager_routes import (
            create_agent_manager_router,
        )

        mgr = MagicMock()
        mgr.list_agents.return_value = []
        routers = create_agent_manager_router(mgr)
        sendblue_router = routers[4]  # 5th element is sendblue_router
        app.include_router(sendblue_router)
        return app

    def test_health_ready(self, health_app):
        c = TestClient(health_app)
        resp = c.get("/v1/channels/sendblue/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["channel_connected"] is True
        assert data["bridge_wired"] is True
        assert data["ready"] is True

    def test_health_not_ready(self):
        app = FastAPI()
        # No sendblue_channel or bridge on state

        from openjarvis.server.agent_manager_routes import (
            create_agent_manager_router,
        )

        mgr = MagicMock()
        mgr.list_agents.return_value = []
        routers = create_agent_manager_router(mgr)
        sendblue_router = routers[4]
        app.include_router(sendblue_router)

        c = TestClient(app)
        resp = c.get("/v1/channels/sendblue/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["ready"] is False
