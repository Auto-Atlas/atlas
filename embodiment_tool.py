# embodiment_tool.py
#
# EVE's thin client to the embodiment platform (the separate ~/eve-embodiment repo —
# universal robot sim now, dedicated GPU hardware later) over its stdio MCP server. EVE is the
# brain CALLING the body; nothing robotic runs in this process.
#
# Safety model:
#   - Flag-gated: EVE_EMBODIMENT=1 registers the tool at all (default OFF).
#   - Owner-gated: "embodiment" is in tool_policy.OWNER_ONLY — guests never move robots.
#   - Motion actions (move/grasp/reset_estop) are confirm-gated IN-HANDLER (single-tool
#     confirmed=true pattern, same as create_invoice): first call returns a spoken draft.
#   - estop is NEVER gated — a stop command must act instantly.
#   - Every completed action is appended to an events file the initiative engine
#     surfaces visually in the app feed (the owner's visual-first rule).
#
# Import invariant: no tool_policy/jarvis_core/bot imports (body-agnostic seam).
#
import json
import os
import shlex
import subprocess
import threading
import time
from pathlib import Path

from loguru import logger
from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

from persona import ASSISTANT_NAME  # configured self-name; EVE is a product


_EVENTS = Path(os.getenv("EVE_EMBODIMENT_EVENTS",
                         str(Path(__file__).parent / "embodiment_events.jsonl")))
_DEFAULT_CMD = f"uv --directory {Path.home() / 'eve-embodiment'} run embody mcp"

MOTION_ACTIONS = {"move", "grasp", "reset_estop"}   # confirm-gated; estop NEVER is


def enabled() -> bool:
    return os.getenv("EVE_EMBODIMENT", "0") == "1"


class _Client:
    """Persistent stdio JSON-RPC client to the embody MCP server. One subprocess per
    EVE process, restarted on death; every call is answered or raises with a reason."""

    def __init__(self, cmd: str | None = None):
        self.cmd = cmd or os.getenv("EVE_EMBODIMENT_CMD", _DEFAULT_CMD)
        self._p = None
        self._id = 0
        self._lock = threading.Lock()

    def _ensure(self):
        if self._p is not None and self._p.poll() is None:
            return
        self._p = subprocess.Popen(
            shlex.split(self.cmd), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True,
            env=dict(os.environ, MUJOCO_GL=os.getenv("MUJOCO_GL", "egl")))
        self._rpc("initialize", {"protocolVersion": "2024-11-05"})

    def _rpc(self, method: str, params: dict) -> dict:
        self._id += 1
        self._p.stdin.write(json.dumps(
            {"jsonrpc": "2.0", "id": self._id, "method": method,
             "params": params}) + "\n")
        self._p.stdin.flush()
        line = self._p.stdout.readline()
        if not line:
            raise RuntimeError("embodiment server exited mid-call")
        return json.loads(line)

    def tool(self, name: str, timeout_note: str = "", **arguments) -> dict:
        """Call one embody tool. Returns the parsed payload; raises with the server's
        own honest error text on isError."""
        with self._lock:
            self._ensure()
            res = self._rpc("tools/call", {"name": name, "arguments": arguments})
        content = ((res.get("result") or {}).get("content") or [{}])[0].get("text", "")
        if (res.get("result") or {}).get("isError"):
            raise RuntimeError(content or "embodiment tool failed")
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            return {"text": content}

    def close(self):
        with self._lock:
            if self._p is not None and self._p.poll() is None:
                self._p.stdin.close()
                try:
                    self._p.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self._p.kill()
            self._p = None


_client: _Client | None = None
_last_session: str | None = None


def _get_client() -> _Client:
    global _client
    if _client is None:
        _client = _Client()
    return _client


def _emit_event(action: str, data: dict):
    """Append to the events file the initiative engine watches — every embodiment act
    becomes a VISIBLE card in the app feed (snapshot paths ride along)."""
    try:
        _EVENTS.parent.mkdir(parents=True, exist_ok=True)
        with _EVENTS.open("a") as f:
            f.write(json.dumps({"ts": time.time(), "action": action,
                                "data": data}) + "\n")
    except Exception as e:
        logger.debug(f"embodiment event write failed: {e!r}")


EMBODIMENT_SCHEMA = FunctionSchema(
    name="embodiment",
    description=(
        f"{ASSISTANT_NAME}'s body: physics-sim robots now (asimov-1 humanoid, ruka-hand), real "
        "hardware later — same actions. look = save a camera frame; describe = say "
        "what the camera sees (report only what is visible; never name specific "
        "robot models/brands); sim_start = boot a robot sim; move/grasp = motion "
        "(DRAFT first — call again with confirmed=true after the user agrees); "
        "estop = IMMEDIATE halt, no confirmation ever; reset_estop; stop = end sim."),
    properties={
        "action": {"type": "string",
                   "enum": ["look", "describe", "sim_start", "move", "grasp",
                            "estop", "reset_estop", "stop"]},
        "robot": {"type": "string", "description": "sim_start: which robot (default asimov-1)"},
        "scene": {"type": "string", "description": "sim_start: flat | tabletop | omit"},
        "targets": {"type": "object", "description": "move: {actuator_name: position}"},
        "close": {"type": "number", "description": "grasp: 1.0 closed .. 0.0 open"},
        "prompt": {"type": "string", "description": "describe: what to look for"},
        "confirmed": {"type": "boolean",
                      "description": "true ONLY after the user approved a motion draft"},
    },
    required=["action"],
)


async def handle_embodiment(params: FunctionCallParams):
    global _last_session
    a = params.arguments
    action = str(a.get("action") or "").strip()
    if not enabled():
        await params.result_callback({"ok": False, "error": "embodiment is disabled "
                                      "(EVE_EMBODIMENT=0)"})
        return
    # Motion is confirm-gated in-handler; a spoken draft comes back first. estop never.
    if action in MOTION_ACTIONS and not a.get("confirmed"):
        what = {"move": f"move actuators to {a.get('targets')}",
                "grasp": f"close the hand (close={a.get('close', 1.0)})",
                "reset_estop": "clear the e-stop and re-enable motion"}[action]
        await params.result_callback({
            "ok": True, "draft": True,
            "instruction": (f"You are about to {what} on the robot. Read this back "
                            "in one short sentence and ask for a clear yes. Only "
                            "after the user agrees, call embodiment again with the "
                            "SAME arguments plus confirmed=true.")})
        return
    import asyncio

    def _do():
        global _last_session
        c = _get_client()
        if action == "sim_start":
            out = c.tool("sim_start", robot=str(a.get("robot") or "asimov-1"),
                         scene=a.get("scene"))
            _last_session = out.get("session")
            return out
        if action == "look":
            kw = {"provider": "sim", "session": _last_session} if _last_session \
                else {"provider": "webcam"}
            return c.tool("look", **kw)
        if action == "describe":
            kw = {"provider": "sim", "session": _last_session} if _last_session \
                else {"provider": "webcam"}
            if a.get("prompt"):
                kw["prompt"] = str(a["prompt"])
            return c.tool("describe_scene", **kw)
        if not _last_session:
            raise RuntimeError("no sim is running — say 'start the robot sim' first")
        if action == "move":
            return c.tool("motion_move", session=_last_session,
                          targets=a.get("targets") or {})
        if action == "grasp":
            return c.tool("motion_grasp", session=_last_session,
                          close=float(a.get("close", 1.0)))
        if action == "estop":
            return c.tool("motion_estop", session=_last_session)
        if action == "reset_estop":
            return c.tool("motion_reset_estop", session=_last_session)
        if action == "stop":
            out = c.tool("sim_stop", session=_last_session)
            _last_session = None
            return out
        raise RuntimeError(f"unknown embodiment action {action!r}")

    try:
        out = await asyncio.to_thread(_do)
    except Exception as e:
        await params.result_callback({
            "ok": False, "error": str(e),
            "instruction": "Tell the user plainly what failed — never pretend the "
                           "body did something it didn't."})
        return
    _emit_event(action, out)
    await params.result_callback({
        "ok": True, **out,
        "instruction": ("Report the result in ONE short sentence (this really "
                        "happened in the SIM, not on real hardware — say so if "
                        "relevant). If a snapshot path came back, the app shows it.")})
