#
# acp_claude_code.py — ACP (Agent Client Protocol) server for Claude Code,
# WITHOUT headless `claude -p` and WITHOUT the Anthropic API.
#
# Why this exists: EVE needs to drive real Claude Code sessions programmatically.
# The two existing ACP adapters both violate the owner's constraints:
#   - @agentclientprotocol/claude-agent-acp → Claude Agent SDK → requires
#     ANTHROPIC_API_KEY (rejects Max/Pro OAuth; openclaw/openclaw#53456).
#   - harukitosa/claude-code-acp → shells out to `claude -p` one-shots — the
#     metered headless path (metered against the credit pool since 2026-06-15).
#
# This adapter instead drives INTERACTIVE-CLASS background sessions, which bill
# to the CLI's logged-in subscription and expose three structured surfaces:
#   spawn     `claude --bg "<prompt>"`          → prints `backgrounded · <id>`
#   status    `claude agents --json --all`      → state machine (… → "done")
#   result    ~/.claude/projects/*/<sessionId>.jsonl  (exact message content)
#   cancel    `claude stop <id>`
# Multi-turn: `claude --bg --resume <sessionId>` forks a NEW sessionId carrying
# full history, so one ACP session maps to a chain of Claude session ids and we
# track the head. All of this was probed live on Claude Code 2.1.199.
#
# Protocol: JSON-RPC 2.0, newline-delimited, over stdio (stdout is protocol —
# ALL logging goes to stderr). Speaks ACP protocolVersion 1: initialize,
# session/new, session/prompt (streams session/update notifications, responds
# {stopReason}), session/cancel. Clients: acpx, Zed, or anything ACP.
#
# Run:   python3 acp_claude_code.py        (stdlib only — no venv required)
# Wire:  acpx --agent "python3 /path/to/acp_claude_code.py" "do the thing"
#
# Config (env — nothing hardcoded, per the product rule):
#   EVE_ACP_CLAUDE_BIN       claude binary                     (default "claude")
#   EVE_ACP_PERMISSION_MODE  --permission-mode for sessions    (default "acceptEdits")
#   EVE_ACP_MODEL            --model override                  (default: CLI default)
#   EVE_ACP_EFFORT           --effort override                 (default: CLI default)
#   EVE_ACP_CLAUDE_ARGS      extra CLI args, shlex-split       (default "--strict-mcp-config")
#   EVE_ACP_WORKSPACE        fallback cwd for sessions         (default ~/jarvis-workspace)
#   EVE_ACP_PROJECTS_DIR     transcript root                   (default ~/.claude/projects)
#   EVE_ACP_SESSION_PREFIX   display name for `claude agents`  (default "eve-acp")
#   EVE_ACP_POLL_S           status poll interval seconds      (default 1.5)
#   EVE_ACP_TURN_TIMEOUT_S   per-turn budget seconds           (default 600)
#   EVE_ACP_ALLOW_API_KEY    "1" keeps ANTHROPIC_API_KEY in the child env.
#                            Default strips it so a session can NEVER silently
#                            flip from subscription OAuth to API billing.
#

import asyncio
import json
import os
import re
import shlex
import sys
import uuid
from pathlib import Path

PROTOCOL_VERSION = 1

CLAUDE_BIN = os.getenv("EVE_ACP_CLAUDE_BIN", "claude")
PERMISSION_MODE = os.getenv("EVE_ACP_PERMISSION_MODE", "acceptEdits")
MODEL = os.getenv("EVE_ACP_MODEL", "")
EFFORT = os.getenv("EVE_ACP_EFFORT", "")
CLAUDE_ARGS = shlex.split(os.getenv("EVE_ACP_CLAUDE_ARGS", "--strict-mcp-config"))
WORKSPACE = Path(os.getenv("EVE_ACP_WORKSPACE", str(Path.home() / "jarvis-workspace")))
PROJECTS_DIR = Path(os.getenv("EVE_ACP_PROJECTS_DIR", str(Path.home() / ".claude" / "projects")))
SESSION_PREFIX = os.getenv("EVE_ACP_SESSION_PREFIX", "eve-acp")
POLL_S = float(os.getenv("EVE_ACP_POLL_S", "1.5"))
TURN_TIMEOUT_S = float(os.getenv("EVE_ACP_TURN_TIMEOUT_S", "600"))
ALLOW_API_KEY = os.getenv("EVE_ACP_ALLOW_API_KEY", "") == "1"

# `claude --bg` prints "backgrounded · <shortid> · <name>". The separator is
# U+00B7; match loosely so cosmetic CLI changes don't break the wire.
_BACKGROUNDED_RE = re.compile(r"backgrounded\s*[^0-9a-fA-F]*([0-9a-fA-F]{4,})")
# Terminal states observed/expected in `claude agents --json` entries.
_TERMINAL_STATES = {"done", "failed", "error", "stopped", "killed"}


def _log(msg: str) -> None:
    print(f"[acp-claude-code] {msg}", file=sys.stderr, flush=True)


def _child_env() -> dict:
    """Subscription-only billing guard: the spawned claude must authenticate via
    its OAuth login, never an ambient API key."""
    env = os.environ.copy()
    if not ALLOW_API_KEY:
        env.pop("ANTHROPIC_API_KEY", None)
    return env


async def _run(argv: list[str], cwd: Path, timeout: float = 60.0) -> tuple[int, str, str]:
    proc = await asyncio.create_subprocess_exec(
        *argv,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=str(cwd),
        env=_child_env(),
    )
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        try:
            proc.kill()
        except ProcessLookupError:
            pass
        raise
    return proc.returncode or 0, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


def _prompt_text(blocks) -> str:
    """Flatten an ACP prompt (list of content blocks, or plain string) to text."""
    if isinstance(blocks, str):
        return blocks
    parts = []
    for b in blocks or []:
        if isinstance(b, dict):
            if b.get("type") == "text" and b.get("text"):
                parts.append(str(b["text"]))
            elif b.get("type") == "resource":  # embedded context
                res = b.get("resource") or {}
                if res.get("text"):
                    parts.append(f"[{res.get('uri', 'resource')}]\n{res['text']}")
    return "\n".join(parts)


def _extract_turn_reply(transcript: Path) -> str:
    """The assistant text for the LAST user turn in a session transcript.

    A 'real' user message has string content or a text block (tool_result-only
    user entries are the tool loop, not the human). Sidechain (subagent) entries
    are skipped. Everything assistant-said after the last real user message is
    the turn's reply."""
    entries = []
    with open(transcript, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if e.get("isSidechain"):
                continue
            if e.get("type") in ("user", "assistant"):
                entries.append(e)

    def _texts(entry) -> list[str]:
        content = (entry.get("message") or {}).get("content")
        if isinstance(content, str):
            return [content] if content.strip() else []
        out = []
        for c in content or []:
            if isinstance(c, dict) and c.get("type") == "text" and str(c.get("text", "")).strip():
                out.append(str(c["text"]))
        return out

    last_user = -1
    for i, e in enumerate(entries):
        if e.get("type") == "user" and _texts(e):
            last_user = i
    reply = []
    for e in entries[last_user + 1 :]:
        if e.get("type") == "assistant":
            reply.extend(_texts(e))
    return "\n".join(reply).strip()


class AcpSession:
    """One ACP session = a chain of Claude Code background sessions. `head` is
    the Claude sessionId to --resume for the next turn."""

    def __init__(self, cwd: Path):
        self.id = f"acp-{uuid.uuid4()}"
        self.cwd = cwd
        self.head: str | None = None
        self.busy = False
        self.cancel = asyncio.Event()
        self.active_short_id: str | None = None


class Server:
    def __init__(self):
        self.sessions: dict[str, AcpSession] = {}
        self._write_lock = asyncio.Lock()

    # ---- JSON-RPC plumbing -------------------------------------------------

    async def _send(self, obj: dict) -> None:
        async with self._write_lock:
            sys.stdout.write(json.dumps(obj) + "\n")
            sys.stdout.flush()

    async def _respond(self, req_id, result=None, error=None) -> None:
        msg = {"jsonrpc": "2.0", "id": req_id}
        if error is not None:
            msg["error"] = error
        else:
            msg["result"] = result if result is not None else {}
        await self._send(msg)

    async def _notify_chunk(self, session_id: str, text: str) -> None:
        await self._send(
            {
                "jsonrpc": "2.0",
                "method": "session/update",
                "params": {
                    "sessionId": session_id,
                    "update": {
                        "sessionUpdate": "agent_message_chunk",
                        "content": {"type": "text", "text": text},
                    },
                },
            }
        )

    # ---- claude --bg turn machinery ----------------------------------------

    def _spawn_args(self, sess: AcpSession, prompt: str) -> list[str]:
        # -n on every turn: EVE's sessions stay identifiable in `claude agents`
        # even after --resume forks a new session id.
        args = [CLAUDE_BIN, "--bg", "-n", SESSION_PREFIX, "--permission-mode", PERMISSION_MODE]
        if sess.head:
            args += ["--resume", sess.head]
        if MODEL:
            args += ["--model", MODEL]
        if EFFORT:
            args += ["--effort", EFFORT]
        # Prompt BEFORE the extra args: variadic flags like --allowedTools eat
        # every following positional, so a trailing prompt would be swallowed
        # (live-debugged: the session started with an empty prompt box).
        args.append(prompt)
        args += CLAUDE_ARGS
        return args

    async def _agents_entry(self, short_id: str, cwd: Path) -> dict | None:
        code, out, _err = await _run([CLAUDE_BIN, "agents", "--json", "--all"], cwd)
        if code != 0:
            return None
        try:
            for entry in json.loads(out):
                if entry.get("id") == short_id:
                    return entry
        except (json.JSONDecodeError, TypeError):
            pass
        return None

    async def _find_transcript(self, session_id: str) -> Path | None:
        hits = list(PROJECTS_DIR.glob(f"*/{session_id}.jsonl"))
        return hits[0] if hits else None

    async def _run_turn(self, sess: AcpSession, prompt: str) -> tuple[str, str]:
        """Run one background-session turn. Returns (stop_reason, reply_text)."""
        sess.cancel.clear()
        code, out, err = await _run(self._spawn_args(sess, prompt), sess.cwd)
        if code != 0:
            raise RuntimeError(f"claude --bg exited {code}: {(err or out)[:300]}")
        m = _BACKGROUNDED_RE.search(out) or _BACKGROUNDED_RE.search(err)
        if not m:
            raise RuntimeError(f"could not parse background id from: {(out or err)[:300]}")
        short_id = m.group(1)
        sess.active_short_id = short_id
        _log(f"session {sess.id}: turn backgrounded as {short_id}")

        claude_session_id: str | None = None
        deadline = asyncio.get_event_loop().time() + TURN_TIMEOUT_S
        try:
            while True:
                if sess.cancel.is_set():
                    await self._stop_background(short_id, sess.cwd)
                    return "cancelled", ""
                if asyncio.get_event_loop().time() > deadline:
                    await self._stop_background(short_id, sess.cwd)
                    # A server-side timeout is an ERROR, not a result — the
                    # client (and EVE's tier chain) must see failure, not a
                    # "[timed out]" string masquerading as an answer.
                    raise RuntimeError(
                        f"turn {short_id} timed out after {int(TURN_TIMEOUT_S)}s and was stopped"
                    )
                entry = await self._agents_entry(short_id, sess.cwd)
                if entry is not None:
                    claude_session_id = entry.get("sessionId") or claude_session_id
                    if str(entry.get("state", "")).lower() in _TERMINAL_STATES:
                        break
                elif claude_session_id:
                    break  # was seen, now deregistered → process finished
                await asyncio.sleep(POLL_S)
        finally:
            sess.active_short_id = None

        if not claude_session_id:
            raise RuntimeError(f"background turn {short_id} never appeared in `{CLAUDE_BIN} agents --json`")
        sess.head = claude_session_id  # resume target for the next turn
        transcript = await self._find_transcript(claude_session_id)
        if transcript is None:
            raise RuntimeError(f"no transcript found for session {claude_session_id} under {PROJECTS_DIR}")
        reply = await asyncio.to_thread(_extract_turn_reply, transcript)
        if not reply:
            raise RuntimeError(f"turn {short_id} finished but produced no assistant text")
        return "end_turn", reply

    async def _stop_background(self, short_id: str, cwd: Path) -> None:
        try:
            await _run([CLAUDE_BIN, "stop", short_id], cwd)
        except Exception as e:  # best-effort: never mask the real outcome
            _log(f"claude stop {short_id} failed: {e}")

    # ---- ACP methods --------------------------------------------------------

    async def on_initialize(self, req_id, params: dict) -> None:
        client_ver = params.get("protocolVersion")
        ver = min(PROTOCOL_VERSION, client_ver) if isinstance(client_ver, int) else PROTOCOL_VERSION
        await self._respond(
            req_id,
            {
                "protocolVersion": ver,
                "agentCapabilities": {
                    "loadSession": False,
                    "promptCapabilities": {"image": False, "audio": False, "embeddedContext": True},
                },
                "authMethods": [],
                "agentInfo": {"name": "eve-claude-code-acp", "version": "0.1.0"},
            },
        )

    async def on_session_new(self, req_id, params: dict) -> None:
        raw_cwd = params.get("cwd")
        cwd = Path(raw_cwd) if raw_cwd else WORKSPACE
        if not cwd.is_absolute():
            await self._respond(req_id, error={"code": -32602, "message": "cwd must be absolute"})
            return
        cwd.mkdir(parents=True, exist_ok=True)
        sess = AcpSession(cwd)
        self.sessions[sess.id] = sess
        _log(f"new session {sess.id} (cwd={cwd})")
        await self._respond(req_id, {"sessionId": sess.id})

    async def on_session_prompt(self, req_id, params: dict) -> None:
        sess = self.sessions.get(params.get("sessionId", ""))
        if sess is None:
            await self._respond(req_id, error={"code": -32602, "message": "unknown sessionId"})
            return
        if sess.busy:
            await self._respond(req_id, error={"code": -32000, "message": "session is busy with another prompt"})
            return
        prompt = _prompt_text(params.get("prompt"))
        if not prompt.strip():
            await self._respond(req_id, error={"code": -32602, "message": "empty prompt"})
            return
        sess.busy = True
        try:
            stop_reason, reply = await self._run_turn(sess, prompt)
            if reply:
                await self._notify_chunk(sess.id, reply)
            await self._respond(req_id, {"stopReason": stop_reason})
        except Exception as e:
            _log(f"session {sess.id}: turn failed: {e}")
            await self._respond(req_id, error={"code": -32000, "message": str(e)[:500]})
        finally:
            sess.busy = False

    async def on_session_cancel(self, params: dict) -> None:
        sess = self.sessions.get(params.get("sessionId", ""))
        if sess is not None:
            sess.cancel.set()

    # ---- dispatch loop -------------------------------------------------------

    async def handle(self, msg: dict) -> None:
        method = msg.get("method", "")
        params = msg.get("params") or {}
        req_id = msg.get("id")
        is_request = "id" in msg and method

        if method == "initialize":
            await self.on_initialize(req_id, params)
        elif method == "session/new":
            await self.on_session_new(req_id, params)
        elif method == "session/prompt":
            # Run as a task so session/cancel notifications are processed
            # while the turn is in flight.
            asyncio.ensure_future(self.on_session_prompt(req_id, params))
        elif method == "session/cancel":
            await self.on_session_cancel(params)
        elif is_request:
            await self._respond(req_id, error={"code": -32601, "message": f"method not supported: {method}"})
        # unknown notifications are ignored per JSON-RPC

    async def serve(self) -> None:
        loop = asyncio.get_event_loop()
        reader = asyncio.StreamReader()
        await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
        _log(f"serving ACP v{PROTOCOL_VERSION} (claude={CLAUDE_BIN}, workspace={WORKSPACE})")
        while True:
            line = await reader.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                _log(f"ignoring non-JSON line: {line[:120]!r}")
                continue
            if isinstance(msg, dict):
                await self.handle(msg)


def main() -> None:
    try:
        asyncio.run(Server().serve())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
