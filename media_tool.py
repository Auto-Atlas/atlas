#
# Media control — hands-free play/pause/skip/volume.
# Windows: synthesized media keys (ctypes). Linux: playerctl (MPRIS — controls
# Spotify, browsers, anything) for transport, wpctl (PipeWire) for volume/mute.
# Controls whatever is actually playing, exactly like the keys on a keyboard —
# zero accounts, zero setup. Picking a SPECIFIC song needs the Spotify API
# later; transport + volume work today.
#

import ctypes
import shutil
import subprocess
import sys

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

_VK = {
    "play_pause": 0xB3,
    "next": 0xB0,
    "previous": 0xB1,
    "volume_up": 0xAF,
    "volume_down": 0xAE,
    "mute": 0xAD,
}

# Linux command table. Volume steps are 5% per "press" to match the feel of
# a keyboard volume key; -l 1.5 caps wpctl at 150% so repeated presses can't
# blow out the speakers.
_LINUX_CMDS = {
    "play_pause": ["playerctl", "play-pause"],
    "next": ["playerctl", "next"],
    "previous": ["playerctl", "previous"],
    "volume_up": ["wpctl", "set-volume", "-l", "1.5", "@DEFAULT_AUDIO_SINK@", "5%+"],
    "volume_down": ["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"],
    "mute": ["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"],
}


def _run_linux(action: str, times: int) -> tuple[bool, str]:
    cmd = _LINUX_CMDS[action]
    if shutil.which(cmd[0]) is None:
        return False, f"{cmd[0]} is not installed on this machine"
    for _ in range(times):
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        if r.returncode != 0:
            detail = (r.stderr or r.stdout).strip()[:120]
            return False, detail or f"{cmd[0]} exited {r.returncode}"
    return True, ""

MEDIA_CONTROL_SCHEMA = FunctionSchema(
    name="media_control",
    description=(
        "Control whatever music/video is playing on the PC, like pressing keyboard media "
        "keys: play_pause, next, previous, volume_up, volume_down, mute. Use for 'pause "
        "the music', 'skip this song', 'turn it up/down', 'mute'. Volume actions can "
        "repeat: pass times=5 for a big change."
    ),
    properties={
        "action": {
            "type": "string",
            "description": "One of: play_pause, next, previous, volume_up, volume_down, mute.",
        },
        "times": {"type": "number", "description": "How many times to press (volume only). Default 1, max 20."},
    },
    required=["action"],
)


def _press(vk: int, times: int) -> None:
    for _ in range(times):
        ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
        ctypes.windll.user32.keybd_event(vk, 0, 2, 0)  # KEYEVENTF_KEYUP


async def handle_media_control(params: FunctionCallParams):
    action = str(params.arguments.get("action", "")).strip().lower()
    vk = _VK.get(action)
    if vk is None:
        await params.result_callback(
            {"ok": False, "error": f"unknown action {action!r} — valid: {', '.join(_VK)}"}
        )
        return
    try:
        times = max(1, min(20, int(params.arguments.get("times") or 1)))
    except Exception:
        times = 1
    if action not in ("volume_up", "volume_down"):
        times = 1
    if sys.platform == "win32":
        _press(vk, times)
    else:
        ok, detail = _run_linux(action, times)
        if not ok:
            await params.result_callback({"ok": False, "error": detail})
            return
    logger.info(f"media_control: {action} x{times}")
    await params.result_callback({"ok": True, "did": action, "times": times})
