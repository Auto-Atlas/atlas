"""RUKA hand actuation bridge (Jetson-only). EVE never imports the DYNAMIXEL
SDK / ruka_hand (own conda env + exclusive USB serial); it shells into that env
via `conda run`. Pose whitelist + serial flock + honest timeout/kill so a hung
actuation reports failure, not success. Import-clean off-Jetson (no heavy deps
at module top)."""
from __future__ import annotations
import os
import subprocess
from contextlib import contextmanager

from loguru import logger

POSES = ("open", "close", "reset")
RUKA_ENV = os.getenv("RUKA_CONDA_ENV", "ruka_hand")
POSE_SCRIPT = os.getenv("RUKA_POSE_SCRIPT", "/mnt/ssd/models/RUKA/eve_pose.py")
LOCK_PATH = os.getenv("RUKA_SERIAL_LOCK", "/tmp/ruka_serial.lock")


@contextmanager
def _serial_lock():
    try:
        import fcntl  # posix-only
    except ImportError:
        fcntl = None
    f = open(LOCK_PATH, "w")
    try:
        if fcntl:
            fcntl.flock(f, fcntl.LOCK_EX)
        yield
    finally:
        if fcntl:
            fcntl.flock(f, fcntl.LOCK_UN)
        f.close()


def _run_cli(argv, timeout):
    """Run argv with a hard timeout; kill + reap on overrun. Returns
    (returncode, stdout, stderr). Never raises on a hung child."""
    p = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        out, err = p.communicate(timeout=timeout)
        return (p.returncode, out.decode(errors="replace"), err.decode(errors="replace"))
    except subprocess.TimeoutExpired:
        p.kill()
        p.communicate()
        return (124, "", f"timeout after {timeout}s")


def actuate(pose: str, hand: str = "right") -> dict:
    if pose not in POSES:
        return {"ok": False, "error": f"unknown pose {pose!r}; allowed: {', '.join(POSES)}"}
    argv = ["conda", "run", "-n", RUKA_ENV, "python", POSE_SCRIPT,
            "--pose", pose, "--hand", hand]
    with _serial_lock():
        code, out, err = _run_cli(argv, timeout=float(os.getenv("RUKA_TIMEOUT_S", "20")))
    if code != 0:
        logger.warning(f"RUKA actuate {pose} failed ({code}): {err}")
        return {"ok": False, "error": f"hand actuation failed ({code}): {err.strip()[:200]}"}
    return {"ok": True, "pose": pose, "hand": hand, "detail": out.strip()[:200]}
