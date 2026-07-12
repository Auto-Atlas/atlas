# visual_tool.py — EVE SHOWS things instead of only saying them.
#
# surface_visual pushes a visual card to every connected surface: the phone app
# (via approval_api's WS hub — same channel approvals/delegations ride) and the
# desktop stage (via the MetricsBridge the voice loop already broadcasts on).
# Three kinds today:
#   desktop_screen — screenshot of the PC's X11 desktop (ImageMagick `import`),
#   image          — an image file on disk (e.g. something a delegation produced),
#   note           — text/log content, no image (e.g. a Claude-run log excerpt).
# Images land in visual_store (TTL'd, non-consuming reads) and the app fetches
# them from the authenticated /v1/visual/{id} endpoint — nothing goes to any
# third-party service. The display shape (title + image-or-text card) is kept
# deliberately minimal and renderer-agnostic so a future glasses HUD is just
# another consumer of the same event.
#
import asyncio
import io
import os
from pathlib import Path

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

import visual_store
from pairing import app_token

SURFACE_VISUAL_SCHEMA = FunctionSchema(
    name="surface_visual",
    description=(
        "SHOW something on the user's screens (a card on the phone app and the "
        "desktop stage) instead of only describing it aloud. kinds: "
        "'desktop_screen' = a live screenshot of this PC's desktop ('show me my "
        "screen', 'what's on the computer'); 'image' = an image file on disk by "
        "path; 'note' = text/log content worth reading rather than hearing "
        "(errors, lists, a delegation's log). Use whenever a visual beats a "
        "spoken description."
    ),
    properties={
        "kind": {"type": "string", "enum": ["desktop_screen", "image", "note"]},
        "title": {"type": "string",
                  "description": "Short card title, e.g. 'Your desktop right now'."},
        "path": {"type": "string",
                 "description": "kind=image only: absolute path of the image file."},
        "text": {"type": "string",
                 "description": "kind=note only: the text/log content to show."},
    },
    required=["kind"],
)

_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
_MAX_IMAGE_BYTES = 12 * 1024 * 1024


def _api_base() -> str:
    return os.getenv("EVE_APPROVAL_API_URL", "http://127.0.0.1:8799").rstrip("/")


def _shot_env() -> dict:
    """X11 coordinates for a screenshot from a systemd service: the unit doesn't
    carry the desktop session's DISPLAY/XAUTHORITY, so default them explicitly."""
    e = dict(os.environ)
    e.setdefault("DISPLAY", os.getenv("EVE_DISPLAY", ":1"))
    e.setdefault("XAUTHORITY", os.getenv("EVE_XAUTHORITY",
                                         str(Path.home() / ".Xauthority")))
    return e


async def _screenshot_desktop() -> bytes:
    """Full-desktop JPEG via ImageMagick `import` (X11). Raises with the tool's
    stderr on failure so EVE can say WHY the screen couldn't be grabbed."""
    proc = await asyncio.create_subprocess_exec(
        "import", "-window", "root", "-silent", "jpeg:-",
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        env=_shot_env(),
    )
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=15)
    except asyncio.TimeoutError:
        proc.kill()
        raise RuntimeError("screenshot timed out")
    if proc.returncode != 0 or not out:
        raise RuntimeError(f"screenshot failed: {err.decode(errors='replace')[:200]}")
    return out


def _shrink(jpeg_or_any: bytes, max_edge: int = 1600) -> bytes:
    """Normalize any input image to a bounded JPEG (phone-friendly payload)."""
    from PIL import Image
    img = Image.open(io.BytesIO(jpeg_or_any))
    img = img.convert("RGB")
    img.thumbnail((max_edge, max_edge))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    return buf.getvalue()


async def _announce(payload: dict) -> None:
    """Tell approval_api to broadcast the surface_visual event to the app(s)."""
    timeout = aiohttp.ClientTimeout(total=8)
    async with aiohttp.ClientSession(timeout=timeout) as s:
        async with s.post(
            f"{_api_base()}/v1/visual/announce", json=payload,
            headers={"Authorization": f"Bearer {app_token()}"},
        ) as r:
            if r.status != 200:
                raise RuntimeError(f"visual announce rejected: HTTP {r.status}")


def make_surface_visual_handler(bridge=None):
    """`bridge` is the MetricsBridge (same one jarvis_agent traces ride) — when
    present the event ALSO broadcasts to the desktop stage/hub surfaces."""

    async def handle_surface_visual(params: FunctionCallParams):
        kind = str(params.arguments.get("kind", "")).strip()
        title = str(params.arguments.get("title", "") or "").strip()[:120]
        text = str(params.arguments.get("text", "") or "").strip()[:4000]
        path = str(params.arguments.get("path", "") or "").strip()
        visual_store.sweep()  # opportunistic TTL cleanup

        visual_id = ""
        try:
            if kind == "desktop_screen":
                raw = await _screenshot_desktop()
                visual_id = visual_store.save(await asyncio.to_thread(_shrink, raw))
                title = title or "Your desktop right now"
            elif kind == "image":
                p = Path(path)
                if not p.is_file():
                    raise RuntimeError(f"no image file at {path!r}")
                if p.suffix.lower() not in _IMAGE_EXTS:
                    raise RuntimeError(f"{p.suffix!r} is not an image file type I can show")
                if p.stat().st_size > _MAX_IMAGE_BYTES:
                    raise RuntimeError("image file too large to surface")
                raw = await asyncio.to_thread(p.read_bytes)
                visual_id = visual_store.save(await asyncio.to_thread(_shrink, raw))
                title = title or p.name
            elif kind == "note":
                if not text:
                    raise RuntimeError("kind=note needs `text`")
                title = title or "From EVE"
            else:
                raise RuntimeError(f"unknown kind {kind!r}")
        except Exception as e:
            logger.warning(f"surface_visual [{kind}] failed: {e}")
            await params.result_callback({
                "ok": False, "error": str(e),
                "instruction": "Say briefly why you couldn't put it on screen."})
            return

        event = {"type": "surface_visual", "kind": kind, "title": title,
                 "visual_id": visual_id,
                 "url": f"/v1/visual/{visual_id}" if visual_id else "",
                 "text": text if kind == "note" else ""}
        delivered = []
        try:
            await _announce(event)
            delivered.append("phone app")
        except Exception as e:
            logger.warning(f"surface_visual: phone announce failed: {e}")
        if bridge is not None:
            try:
                # The desktop stage has no approval-api bearer, so its (loopback WS)
                # copy carries the bounded JPEG inline instead of a fetch URL.
                stage_event = dict(event)
                if visual_id:
                    import base64
                    raw = visual_store.read(visual_id) or b""
                    stage_event["data_uri"] = (
                        "data:image/jpeg;base64," + base64.b64encode(raw).decode())
                await bridge.broadcast(stage_event)
                delivered.append("desktop stage")
            except Exception as e:
                logger.debug(f"surface_visual: stage broadcast skipped: {e}")

        if not delivered:
            await params.result_callback({
                "ok": False, "error": "no surface reachable",
                "instruction": "Say you prepared the visual but no screen is connected right now."})
            return
        logger.info(f"surface_visual [{kind}] -> {', '.join(delivered)} ({visual_id or 'text'})")
        await params.result_callback({
            "ok": True, "shown_on": delivered, "title": title,
            "instruction": ("It's on screen now — reference it in ONE short sentence "
                            "(e.g. 'that's on your phone'), don't read it aloud.")})

    return handle_surface_visual
