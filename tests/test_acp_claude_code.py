# tests/test_acp_claude_code.py
#
# The ACP adapter (acp_claude_code.py) wires Agent Client Protocol clients
# (acpx, Zed, …) to Claude Code WITHOUT `claude -p` and WITHOUT the API: it
# drives `claude --bg` background sessions and reads results from transcript
# JSONL. These tests run the real adapter process over real stdio pipes against
# a fake `claude` shim — hermetic: no network, no real Claude Code, no billing.
import asyncio
import json
import os
import sys
from pathlib import Path

import pytest

ADAPTER = str(Path(__file__).resolve().parents[1] / "acp_claude_code.py")

# A stand-in for the `claude` CLI covering the exact surfaces the adapter uses:
# `--bg` spawn (prints `backgrounded · <id>`, writes transcript), `agents
# --json` (status), and `stop`. It also records its argv and whether
# ANTHROPIC_API_KEY leaked into its environment.
FAKE_CLAUDE = """#!/usr/bin/env python3
import json, os, sys, uuid
from pathlib import Path

STATE = Path(os.environ["FAKE_CLAUDE_STATE"])
PROJECTS = Path(os.environ["FAKE_CLAUDE_PROJECTS"])
args = sys.argv[1:]

if os.environ.get("FAKE_CLAUDE_HANG") == "1" and args and args[0] not in ("agents", "stop"):
    # Spawn a turn that never reaches a terminal state (state stays "working").
    sid = str(uuid.uuid4())
    short = sid[:8]
    (STATE / ("bg-" + short + ".json")).write_text(json.dumps({
        "id": short, "sessionId": sid, "state": "working", "kind": "background",
        "resume": None, "argv": args, "api_key_present": False,
    }))
    print("backgrounded \\u00b7 " + short)
    sys.exit(0)

if args and args[0] == "agents":
    entries = []
    for f in sorted(STATE.glob("bg-*.json")):
        d = json.loads(f.read_text())
        entries.append({k: d[k] for k in ("id", "sessionId", "state", "kind")})
    print(json.dumps(entries))
    sys.exit(0)

if args and args[0] == "stop":
    for f in STATE.glob("bg-*.json"):
        d = json.loads(f.read_text())
        if d["id"] == args[1]:
            d["state"] = "stopped"
            f.write_text(json.dumps(d))
    print(f"stopped {args[1]}")
    sys.exit(0)

# Anything else is a `--bg` spawn; the prompt is the last POSITIONAL arg
# (the adapter puts extra flags like --strict-mcp-config after it).
VALUE_FLAGS = {"-n", "--permission-mode", "--resume", "--model", "--effort",
               "--mcp-config", "--allowedTools"}
positionals, i = [], 0
while i < len(args):
    if args[i] in VALUE_FLAGS:
        i += 2
    elif args[i].startswith("-"):
        i += 1
    else:
        positionals.append(args[i])
        i += 1
prompt = positionals[-1]
resume = args[args.index("--resume") + 1] if "--resume" in args else None
sid = str(uuid.uuid4())
short = sid[:8]
proj = PROJECTS / "fake-project"
proj.mkdir(parents=True, exist_ok=True)

def entry(kind, text):
    return json.dumps(
        {"type": kind, "message": {"role": kind, "content": [{"type": "text", "text": text}]}}
    )

lines = []
if resume:  # forked resume carries full history, like the real CLI
    old = proj / (resume + ".jsonl")
    if old.exists():
        lines = [l for l in old.read_text().splitlines() if l.strip()]
lines.append(entry("user", prompt))
lines.append(entry("assistant", "ECHO: " + prompt))
(proj / (sid + ".jsonl")).write_text("\\n".join(lines) + "\\n")

(STATE / ("bg-" + short + ".json")).write_text(json.dumps({
    "id": short, "sessionId": sid, "state": "done", "kind": "background",
    "resume": resume, "argv": args,
    "api_key_present": "ANTHROPIC_API_KEY" in os.environ,
}))
print("backgrounded \\u00b7 " + short)
"""


class AdapterProc:
    """The adapter as a child process, spoken to over real stdio pipes."""

    def __init__(self, proc):
        self.proc = proc
        self._id = 0

    async def send(self, method, params=None, notify=False):
        msg = {"jsonrpc": "2.0", "method": method, "params": params or {}}
        if not notify:
            self._id += 1
            msg["id"] = self._id
        self.proc.stdin.write((json.dumps(msg) + "\n").encode())
        await self.proc.stdin.drain()
        return None if notify else self._id

    async def recv(self):
        line = await asyncio.wait_for(self.proc.stdout.readline(), timeout=15)
        assert line, "adapter closed stdout unexpectedly"
        return json.loads(line)

    async def request(self, method, params=None):
        """Send a request; collect session/update notifications until the
        matching response arrives. Returns (response, updates)."""
        req_id = await self.send(method, params)
        updates = []
        while True:
            msg = await self.recv()
            if msg.get("id") == req_id:
                return msg, updates
            if msg.get("method") == "session/update":
                updates.append(msg["params"])

    async def close(self):
        try:
            self.proc.stdin.close()
            await asyncio.wait_for(self.proc.wait(), timeout=5)
        except Exception:
            self.proc.kill()


@pytest.fixture
def acp_env(tmp_path):
    shim = tmp_path / "fake-claude"
    shim.write_text(FAKE_CLAUDE)
    shim.chmod(0o755)
    state = tmp_path / "state"
    projects = tmp_path / "projects"
    workspace = tmp_path / "workspace"
    for d in (state, projects, workspace):
        d.mkdir()
    env = os.environ.copy()
    env.update(
        {
            "EVE_ACP_CLAUDE_BIN": str(shim),
            "EVE_ACP_PROJECTS_DIR": str(projects),
            "EVE_ACP_WORKSPACE": str(workspace),
            "EVE_ACP_POLL_S": "0.05",
            "EVE_ACP_TURN_TIMEOUT_S": "10",
            "FAKE_CLAUDE_STATE": str(state),
            "FAKE_CLAUDE_PROJECTS": str(projects),
        }
    )
    return env, state, workspace


async def _start(env):
    proc = await asyncio.create_subprocess_exec(
        sys.executable,
        ADAPTER,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
        env=env,
    )
    return AdapterProc(proc)


def _bg_records(state: Path) -> list[dict]:
    return [json.loads(f.read_text()) for f in sorted(state.glob("bg-*.json"))]


async def test_initialize_handshake(acp_env):
    env, _state, _ws = acp_env
    a = await _start(env)
    try:
        resp, _ = await a.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}})
        result = resp["result"]
        assert result["protocolVersion"] == 1
        assert result["agentCapabilities"]["loadSession"] is False
        assert result["authMethods"] == []  # no API-key auth: subscription only
    finally:
        await a.close()


async def test_prompt_roundtrip_and_multiturn_resume(acp_env):
    env, state, ws = acp_env
    a = await _start(env)
    try:
        await a.request("initialize", {"protocolVersion": 1})
        resp, _ = await a.request("session/new", {"cwd": str(ws), "mcpServers": []})
        sid = resp["result"]["sessionId"]

        # Turn 1: fresh background session, reply extracted from the transcript.
        resp, updates = await a.request(
            "session/prompt",
            {"sessionId": sid, "prompt": [{"type": "text", "text": "hello"}]},
        )
        assert resp["result"]["stopReason"] == "end_turn"
        texts = [u["update"]["content"]["text"] for u in updates]
        assert "ECHO: hello" in "".join(texts)
        first = _bg_records(state)
        assert len(first) == 1 and first[0]["resume"] is None

        # Turn 2: must --resume the sessionId turn 1 produced (chained head).
        resp, updates = await a.request(
            "session/prompt",
            {"sessionId": sid, "prompt": [{"type": "text", "text": "again"}]},
        )
        assert resp["result"]["stopReason"] == "end_turn"
        texts = [u["update"]["content"]["text"] for u in updates]
        assert "ECHO: again" in "".join(texts)
        records = _bg_records(state)
        assert len(records) == 2
        turn2 = next(r for r in records if r["resume"] is not None)
        assert turn2["resume"] == first[0]["sessionId"]
    finally:
        await a.close()


async def test_api_key_is_stripped_from_claude_env(acp_env):
    # The whole point of this wire: sessions bill to subscription OAuth. Even
    # with an ambient ANTHROPIC_API_KEY, the spawned claude must never see it.
    env, state, ws = acp_env
    env["ANTHROPIC_API_KEY"] = "sk-ant-api03-should-never-leak"
    a = await _start(env)
    try:
        await a.request("initialize", {"protocolVersion": 1})
        resp, _ = await a.request("session/new", {"cwd": str(ws)})
        sid = resp["result"]["sessionId"]
        await a.request(
            "session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "x"}]}
        )
        assert all(r["api_key_present"] is False for r in _bg_records(state))
    finally:
        await a.close()


async def test_spawn_flags_carry_permission_mode_and_name(acp_env):
    env, state, ws = acp_env
    env["EVE_ACP_PERMISSION_MODE"] = "plan"
    env["EVE_ACP_SESSION_PREFIX"] = "unit-prefix"
    a = await _start(env)
    try:
        await a.request("initialize", {"protocolVersion": 1})
        resp, _ = await a.request("session/new", {"cwd": str(ws)})
        sid = resp["result"]["sessionId"]
        await a.request(
            "session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "x"}]}
        )
        argv = _bg_records(state)[0]["argv"]
        assert "--bg" in argv and "-p" not in argv and "--print" not in argv
        assert argv[argv.index("--permission-mode") + 1] == "plan"
        assert argv[argv.index("-n") + 1] == "unit-prefix"
    finally:
        await a.close()


async def test_unknown_method_and_unknown_session(acp_env):
    env, _state, _ws = acp_env
    a = await _start(env)
    try:
        resp, _ = await a.request("session/definitely_not_a_method", {})
        assert resp["error"]["code"] == -32601
        resp, _ = await a.request(
            "session/prompt", {"sessionId": "nope", "prompt": [{"type": "text", "text": "x"}]}
        )
        assert resp["error"]["code"] == -32602
    finally:
        await a.close()


async def test_turn_timeout_is_an_error_not_a_result(acp_env):
    # A server-side timeout must surface as a JSON-RPC ERROR (tier chain falls
    # through), never as a "[timed out]" text chunk masquerading as an answer —
    # and the hung background session must be stopped.
    env, state, ws = acp_env
    env["FAKE_CLAUDE_HANG"] = "1"
    env["EVE_ACP_TURN_TIMEOUT_S"] = "0.3"
    a = await _start(env)
    try:
        await a.request("initialize", {"protocolVersion": 1})
        resp, _ = await a.request("session/new", {"cwd": str(ws)})
        sid = resp["result"]["sessionId"]
        resp, updates = await a.request(
            "session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "hang"}]}
        )
        assert "error" in resp and "timed out" in resp["error"]["message"]
        assert updates == []  # no fake answer streamed
        records = _bg_records(state)
        assert records and records[0]["state"] == "stopped"  # claude stop ran
    finally:
        await a.close()


def test_extract_turn_reply_skips_tool_results_and_sidechains(tmp_path):
    # Unit-level: the transcript reader must anchor on the last REAL user
    # message (string/text content), ignore tool_result user entries from the
    # agent loop, and skip sidechain (subagent) chatter.
    import acp_claude_code as mod

    def line(kind, content, sidechain=False):
        return json.dumps(
            {"type": kind, "isSidechain": sidechain, "message": {"role": kind, "content": content}}
        )

    p = tmp_path / "t.jsonl"
    p.write_text(
        "\n".join(
            [
                line("user", "old question"),
                line("assistant", [{"type": "text", "text": "old answer"}]),
                line("user", [{"type": "text", "text": "real question"}]),
                line("assistant", [{"type": "tool_use", "id": "t1", "name": "Bash", "input": {}}]),
                line("user", [{"type": "tool_result", "tool_use_id": "t1", "content": "ran"}]),
                line("assistant", [{"type": "text", "text": "part one"}]),
                line("assistant", [{"type": "text", "text": "sidechain noise"}], sidechain=True),
                line("assistant", [{"type": "text", "text": "part two"}]),
            ]
        )
        + "\n"
    )
    assert mod._extract_turn_reply(p) == "part one\npart two"
