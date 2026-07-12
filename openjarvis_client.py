#
# openjarvis_client — thin async HTTP client to the local OpenJarvis daemon.
#
# The sidecar otherwise reaches OpenJarvis by shelling out to the `jarvis` CLI
# (slow subprocess). This client talks straight to the daemon's REST API on
# :8000, which is fast and in-process-async. Single-user, localhost: the daemon
# trusts localhost, so no auth is required — but a Bearer token is sent if
# JARVIS_AGENT_API_KEY is set.
#
# Routes were verified against the live daemon + server source. These are the
# only routes the daemon exposes that we use; do not invent others:
#   POST /v1/memory/search   {"query", "top_k"}        -> {"results": [...]}
#   POST /v1/memory/store    {"content", "metadata"}   -> {"status": "stored"}
#   POST /v1/channels/send   {"channel","content",...} -> {"status","channel"}
#   GET  /v1/channels                                  -> {"channels": [...]}
#   GET  /v1/connectors/{id}                           -> {"connected", "auth_url", ...}
#   POST /v1/connectors/{id}/events {"title","start",...} -> {"event": {...}}
#
# Honest errors only: any non-2xx raises RuntimeError with path + status + a
# body excerpt. We never fabricate success (mirrors sms_tool's discipline).
#
# .env: JARVIS_AGENT_URL (default http://127.0.0.1:8000)
#       JARVIS_AGENT_API_KEY (optional Bearer token; falls back to
#         OPENJARVIS_API_KEY — the same key the daemon enforces on non-loopback)
#       JARVIS_OJ_HTTP_TIMEOUT (seconds, default 15)
#

import os

import aiohttp


class OpenJarvisClient:
    """Async client for the local OpenJarvis daemon's memory + channels API."""

    def __init__(self, base_url=None, token=None, timeout=None):
        if base_url is None:
            base_url = os.getenv("JARVIS_AGENT_URL", "http://127.0.0.1:8000")
        self.base_url = base_url.rstrip("/")

        if token is None:
            # Prefer the sidecar-specific key, but fall back to the OpenJarvis
            # server key (OPENJARVIS_API_KEY) the daemon enforces when bound to a
            # non-loopback host — so one key configures both without duplication.
            token = os.getenv("JARVIS_AGENT_API_KEY") or os.getenv("OPENJARVIS_API_KEY", "")
        self.token = token or ""

        if timeout is None:
            timeout = float(os.getenv("JARVIS_OJ_HTTP_TIMEOUT", "15"))
        self.timeout = aiohttp.ClientTimeout(total=timeout)

    def _headers(self):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    async def _request(self, method, path, json_body=None):
        """Open a per-call session, make the request, and return parsed JSON.

        Raises RuntimeError on any non-2xx — callers must surface failures
        honestly rather than assume the daemon did the work.
        """
        url = f"{self.base_url}{path}"
        async with aiohttp.ClientSession(timeout=self.timeout) as session:
            async with session.request(
                method, url, json=json_body, headers=self._headers()
            ) as resp:
                if resp.status not in (200, 201):
                    body = await resp.text()
                    raise RuntimeError(
                        f"OpenJarvis {path} HTTP {resp.status}: {body[:160]}"
                    )
                return await resp.json()

    async def memory_search(self, query, top_k=5):
        data = await self._request(
            "POST", "/v1/memory/search", {"query": query, "top_k": top_k}
        )
        return data.get("results", [])

    async def memory_store(self, content, metadata=None):
        return await self._request(
            "POST", "/v1/memory/store", {"content": content, "metadata": metadata}
        )

    async def channel_send(self, channel, content, conversation_id=None):
        return await self._request(
            "POST",
            "/v1/channels/send",
            {
                "channel": channel,
                "content": content,
                "conversation_id": conversation_id,
            },
        )

    async def list_channels(self):
        data = await self._request("GET", "/v1/channels")
        return data.get("channels", [])

    async def connector_detail(self, connector_id):
        """Return the daemon's status dict for one connector.

        Includes "connected" (bool) and, for OAuth connectors, "auth_url" —
        everything EVE needs to decide between the connector write path and
        the legacy webhook fallback, or to hand the user a consent link.
        """
        return await self._request("GET", f"/v1/connectors/{connector_id}")

    async def gcalendar_create_event(
        self,
        title,
        start,
        duration_min=60,
        all_day=False,
        calendar_id="primary",
    ):
        """Create a Google Calendar event through the gcalendar connector.

        start is 'YYYY-MM-DD HH:MM' (24h, local) or 'YYYY-MM-DD' (all-day).
        Returns the created event summary dict; raises RuntimeError on any
        non-2xx (not connected, validation, or Google-side failure).
        """
        data = await self._request(
            "POST",
            "/v1/connectors/gcalendar/events",
            {
                "title": title,
                "start": start,
                "duration_min": duration_min,
                "all_day": all_day,
                "calendar_id": calendar_id,
            },
        )
        return data.get("event", {})
