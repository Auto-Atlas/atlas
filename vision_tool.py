# vision_tool.py — EVE sees through whichever camera the user has: phone OR glasses.
#
# Flow (three processes, two artifacts):
#   voice loop (this tool) --loopback HTTP--> approval_api /v1/vision/request
#     --WS hub event {type:"capture_frame", source:...}--> EVE app (surface=phone) OR
#       a glasses bridge (surface=glasses) with the Talk/capture surface live
#     --CameraX / glasses snapshot--> POST /v1/vision/frame --> vision_frames spool (disk)
#   this tool polls the spool, hands the JPEG to the LOCAL vision model
#   (eve-vlm.service — llama-server qwen3-vl on :8093, OpenAI-compatible, via
#   vlm_client), and returns the description for EVE to speak. The frame never leaves
#   the box and is deleted on read (vision_frames privacy contract).
#
# Source routing: the capture request carries a `source` (any | phone | glasses). The
# approval hub broadcasts to every client, but a named source targets ONE camera and
# the returned listener count reflects only that camera — so failure honesty can name
# the RIGHT leg (phone app closed vs glasses not linked vs no camera at all).
#
# Failure honesty: every miss states WHICH leg failed (no camera connected / no
# frame arrived / vision model down) so EVE tells the user something actionable
# instead of a generic "couldn't see".
#
import asyncio
import os
import time
import uuid

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

import vision_frames
import vlm_client
from pairing import app_token

LOOK_SCHEMA = FunctionSchema(
    name="look",
    description=(
        "SEE through whichever camera the user has — their phone OR their smart "
        "glasses — and describe what's in front of them. Use when the user says "
        "'look at this', 'what am I looking at', 'can you see this', or wants "
        "anything visual checked. Leave source on auto unless the user NAMES a "
        "device ('look through my glasses', 'use the phone camera'). If the result "
        "says nothing is connected, tell them what to open, then try again."
    ),
    properties={
        "prompt": {
            "type": "string",
            "description": "What to look for or the user's actual question about the scene, "
                           "e.g. 'what plant is this' or 'read the error on this screen'.",
        },
        "source": {
            "type": "string",
            "enum": ["auto", "phone", "glasses"],
            "description": "Which camera to use. 'auto' (default) asks any connected "
                           "camera. Only set 'phone' or 'glasses' when the user names one.",
        },
    },
    required=[],
)

LOOK_VIA_PHONE_SCHEMA = FunctionSchema(
    name="look_via_phone",
    description=(
        "SEE through the phone's camera: asks the EVE app to snap a photo and "
        "describes what's in it. Use when the user says 'look at this', 'what am "
        "I looking at', 'can you see this', or wants anything visual checked. "
        "The app must be open on the phone; if the result says it isn't, tell "
        "the user to open EVE and point the camera, then try again."
    ),
    properties={
        "prompt": {
            "type": "string",
            "description": "What to look for or the user's actual question about the scene, "
                           "e.g. 'what plant is this' or 'read the error on this screen'.",
        },
    },
    required=[],
)

# Per-source "nobody's listening" honesty: name the exact leg the user must fix.
_NO_LISTENER_INSTRUCTION = {
    "phone": ("The EVE app isn't open on the phone. Ask the user to open EVE, point "
              "the camera at it, and ask again."),
    "glasses": ("The glasses aren't connected right now. Ask the user to check that "
                "their glasses are linked and awake, then ask again."),
    "any": ("No camera device is connected — the phone app is closed and the glasses "
            "aren't linked. Ask the user to open EVE on the phone or wake their "
            "glasses, then ask again."),
}


def _api_base() -> str:
    return os.getenv("EVE_APPROVAL_API_URL", "http://127.0.0.1:8799").rstrip("/")


def _wait_s() -> float:
    return float(os.getenv("EVE_VISION_WAIT_S", "25"))


async def _request_capture(request_id: str, prompt: str, source: str = "any") -> int:
    """Ask approval_api to broadcast the capture_frame event to connected cameras.
    `source` (any|phone|glasses) narrows WHICH camera; the returned listener count
    reflects only the matching surface (0 => nobody on that camera will answer)."""
    timeout = aiohttp.ClientTimeout(total=8)
    async with aiohttp.ClientSession(timeout=timeout) as s:
        async with s.post(
            f"{_api_base()}/v1/vision/request",
            json={"request_id": request_id, "prompt": prompt, "source": source},
            headers={"Authorization": f"Bearer {app_token()}"},
        ) as r:
            data = await r.json(content_type=None)
            if r.status != 200:
                raise RuntimeError(f"vision request rejected: HTTP {r.status}")
            return int(data.get("listeners", 0))


async def _describe(jpeg: bytes, prompt: str) -> str:
    """One chat completion against the local VLM with the frame inlined (delegated to
    vlm_client, shared with glasses_stream so the request shape never drifts)."""
    return await vlm_client.describe(jpeg, prompt)


async def _await_frame(request_id: str, wait_s: float) -> bytes | None:
    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        data = vision_frames.take(request_id)
        if data:
            return data
        await asyncio.sleep(0.5)
    return None


def _source_arg(params) -> str:
    """Map the tool's 'auto' (any camera) onto the capture source, validating input.
    Anything unexpected falls back to 'any' — never fail the look over a bad enum."""
    raw = str(params.arguments.get("source", "auto") or "auto").strip().lower()
    if raw in ("phone", "glasses"):
        return raw
    return "any"          # "auto" and any garbage -> broadcast to every camera


async def _look(params: FunctionCallParams, source: str):
    """The shared look flow for every entry point (look / look_via_phone). `source`
    is the capture target: any | phone | glasses. Each miss names its own leg."""
    prompt = str(params.arguments.get("prompt", "") or "").strip()
    request_id = uuid.uuid4().hex[:16]
    vision_frames.sweep()  # opportunistic: never let stale frames accumulate

    try:
        listeners = await _request_capture(request_id, prompt, source)
    except Exception as e:
        logger.warning(f"look: capture request failed: {e}")
        await params.result_callback({
            "ok": False, "error": f"could not reach the app service: {e}",
            "instruction": "Say you couldn't reach the camera service right now."})
        return
    if listeners == 0:
        await params.result_callback({
            "ok": False, "error": f"no camera connected for source={source}",
            "instruction": _NO_LISTENER_INSTRUCTION.get(source, _NO_LISTENER_INSTRUCTION["any"])})
        return

    jpeg = await _await_frame(request_id, _wait_s())
    if jpeg is None:
        await params.result_callback({
            "ok": False, "error": "no frame arrived from the camera",
            "instruction": ("The camera didn't send a picture — the app may be in the "
                            "background or a permission was denied. Ask the user to bring "
                            "the camera to the foreground and try again.")})
        return

    try:
        description = await _describe(jpeg, prompt)
    except Exception as e:
        logger.warning(f"look: VLM describe failed: {e}")
        await params.result_callback({
            "ok": False, "error": str(e),
            "instruction": "Say you got the picture but the vision model failed to read it."})
        return

    logger.info(f"look[{source}]: described {len(jpeg)} bytes -> {len(description)} chars")
    await params.result_callback({
        "ok": True, "description": description,
        "instruction": ("You just SAW this through the user's camera. Answer their "
                        "question from the description in your own words — short, concrete, "
                        "no preamble about tools or models.")})


async def handle_look(params: FunctionCallParams):
    """Source-agnostic look: honors an explicit source, else 'auto' => any camera."""
    await _look(params, _source_arg(params))


async def handle_look_via_phone(params: FunctionCallParams):
    """Backward-compat thin wrapper: the original phone-only entry point, now just the
    shared flow pinned to source='phone'."""
    await _look(params, "phone")
