#!/usr/bin/env python3
"""One-origin gateway for the phone JARVIS page.

Serves the built page AND fronts the two channels it needs, so everything is
reachable from a single Tailscale HTTPS origin (no CORS, no mixed content, no
admin-only file serving):

    GET  /                      -> app/frontend/dist-phone (the page)
    GET  /assets/*, /*.png ...  -> static files
    POST /start                 -> http://127.0.0.1:8788/start        (WebRTC signaling)
    *    /sessions/{rest}       -> http://127.0.0.1:8788/sessions/... (SDP offer/answer)
    GET  /ws  (websocket)       -> ws://127.0.0.1:8766                (metrics bridge -> avatar)

Only signaling and metrics pass through here; the actual voice media (RTP) flows
peer-to-peer over ICE between the phone and this PC (Tailscale / LAN), never
through this process. This is a pure proxy + static server — it does NOT touch
phone_bot.py or the voice pipeline.

Expose it with one Tailscale mapping (no admin):
    tailscale serve --bg --https=8445 http://127.0.0.1:8795

Run:
    .venv\\Scripts\\python phone_gateway.py        # 127.0.0.1:8795
    JARVIS_GATEWAY_PORT=9000 python phone_gateway.py
"""
from __future__ import annotations

import os
from pathlib import Path

import aiohttp
from aiohttp import WSMsgType, web

DIST = Path(__file__).parent / "app" / "frontend" / "dist-phone"
HOST = os.getenv("JARVIS_GATEWAY_HOST", "127.0.0.1")
PORT = int(os.getenv("JARVIS_GATEWAY_PORT", "8795"))
RTC_UPSTREAM = os.getenv("JARVIS_RTC_UPSTREAM", "http://127.0.0.1:8788")
METRICS_WS = os.getenv("JARVIS_METRICS_WS", "ws://127.0.0.1:8766")

# Force correct types: a stale Windows registry can hand back text/plain for
# .js, which browsers refuse to execute as an ES module.
_MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript",
    ".mjs": "application/javascript",
    ".css": "text/css",
    ".json": "application/json",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".webmanifest": "application/manifest+json",
    ".woff2": "font/woff2",
}
# Hop-by-hop headers must not be forwarded across the proxy boundary.
_HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer",
        "upgrade", "proxy-authorization", "proxy-authenticate", "content-encoding",
        "content-length", "host"}


def _client(request: web.Request) -> aiohttp.ClientSession:
    return request.app["client"]


async def proxy_rtc(request: web.Request) -> web.Response:
    """Forward a signaling request to the pipecat WebRTC server verbatim."""
    upstream = RTC_UPSTREAM + request.rel_url.raw_path_qs
    body = await request.read()
    headers = {k: v for k, v in request.headers.items() if k.lower() not in _HOP}
    try:
        async with _client(request).request(
            request.method, upstream, data=body, headers=headers,
            timeout=aiohttp.ClientTimeout(total=60),
        ) as r:
            data = await r.read()
            out = {k: v for k, v in r.headers.items() if k.lower() not in _HOP}
            return web.Response(status=r.status, body=data, headers=out)
    except aiohttp.ClientError as e:
        return web.json_response({"error": f"signaling upstream unreachable: {e}"}, status=502)


async def proxy_ws(request: web.Request) -> web.WebSocketResponse:
    """Bridge the page's /ws to the metrics bridge so the avatar gets events."""
    client_ws = web.WebSocketResponse()
    await client_ws.prepare(request)
    try:
        async with _client(request).ws_connect(METRICS_WS) as upstream:
            async def pump_up() -> None:
                async for msg in client_ws:
                    if msg.type == WSMsgType.TEXT:
                        await upstream.send_str(msg.data)
                    elif msg.type == WSMsgType.BINARY:
                        await upstream.send_bytes(msg.data)
                    elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING):
                        break

            async def pump_down() -> None:
                async for msg in upstream:
                    if msg.type == WSMsgType.TEXT:
                        await client_ws.send_str(msg.data)
                    elif msg.type == WSMsgType.BINARY:
                        await client_ws.send_bytes(msg.data)
                    elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.ERROR):
                        break

            import asyncio
            up = asyncio.ensure_future(pump_up())
            down = asyncio.ensure_future(pump_down())
            await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
            for task in (up, down):
                task.cancel()
    except aiohttp.ClientError:
        # Bridge not up yet (no phone session since boot) — the page retries.
        pass
    finally:
        if not client_ws.closed:
            await client_ws.close()
    return client_ws


async def static_handler(request: web.Request) -> web.StreamResponse:
    """Serve the built page; anything that isn't a real file falls back to the
    single-page index (the app has no server-side routes)."""
    rel = request.match_info.get("tail", "") or "index.html"
    candidate = (DIST / rel).resolve()
    # Stay inside DIST and only serve real files; otherwise the SPA entry.
    if DIST.resolve() not in candidate.parents and candidate != DIST.resolve():
        candidate = DIST / "index.html"
    if not candidate.is_file():
        candidate = DIST / "index.html"
    ct = _MIME.get(candidate.suffix.lower())
    return web.FileResponse(candidate, headers={"Content-Type": ct} if ct else None)


async def _on_startup(app: web.Application) -> None:
    app["client"] = aiohttp.ClientSession()


async def _on_cleanup(app: web.Application) -> None:
    await app["client"].close()


def build_app() -> web.Application:
    if not (DIST / "index.html").is_file():
        raise SystemExit(
            f"Build missing: {DIST / 'index.html'} not found.\n"
            "Build it first:  cd app/frontend && npm run build:phone"
        )
    app = web.Application()
    app.on_startup.append(_on_startup)
    app.on_cleanup.append(_on_cleanup)
    app.router.add_route("*", "/start", proxy_rtc)
    app.router.add_route("*", "/sessions/{rest:.*}", proxy_rtc)
    app.router.add_get("/ws", proxy_ws)
    app.router.add_get("/{tail:.*}", static_handler)
    return app


if __name__ == "__main__":
    print(f"Phone gateway: serving {DIST}")
    print(f"  /start, /sessions/* -> {RTC_UPSTREAM}")
    print(f"  /ws                 -> {METRICS_WS}")
    print(f"Listening on http://{HOST}:{PORT}")
    web.run_app(build_app(), host=HOST, port=PORT, print=None)
