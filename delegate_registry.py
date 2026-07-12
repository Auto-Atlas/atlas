# delegate_registry.py
#
# Declarative delegate specs (DATA, not hand-written code) + one shared transport
# (EVE Agent Hub spec §4.2, §11.G). One thin tool per agent so qwen routes accurately and
# tool_policy gates each at its own risk tier.
#
# Hermes is reached via its CLI: `hermes -z "<task>"` prints the clean answer to stdout (logs
# go to stderr), so extraction is stdout.strip() — verified live. There is NO bound port and no
# change to the running gateway.
#
# SAFETY (BMAD plan review — Amelia/Winston): a side-effecting one-shot CLI cannot be safely
# interrupted mid-send, so it is run EXACTLY ONCE, by the poller (the sole executor). run_delegate
# never shields/detaches a still-running subprocess — that caused a double-send. The handler
# enqueues and says "on it"; the poller runs run_delegate under its lease with a hard timeout +
# kill. Honesty contract (from agent_bridge): success only if it actually returned.
#
import asyncio
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

from loguru import logger
from pipecat.adapters.schemas.function_schema import FunctionSchema

# Resolve the Hermes CLI: PATH first, then the known venv (so a venv move doesn't silently break
# delegation). The local install IS nousresearch/hermes-agent.
HERMES_BIN = (
    shutil.which("hermes")
    or os.getenv("EVE_HERMES_BIN")
    or str(Path.home() / "obsidian-vault-second-run" / "hermes-agent" / "venv" / "bin" / "hermes")
)
HARD_S = float(os.getenv("EVE_DELEGATE_HARD_S", "180"))


@dataclass(frozen=True)
class DelegateSpec:
    name: str
    transport: str                 # "cli" today; "http"/"ws" later
    command: tuple = ()            # argv template; "{task}" is substituted
    auth: str = "none"
    request_schema: tuple = ("task",)
    specialty: str = ""
    callback: str = "poll"         # poll | push
    risk: str = "high"
    allow_redelegate: bool = False
    side_effecting: bool = True    # reaches the outside world -> poller is the SOLE executor
    enabled: bool = True
    talkback: str = "none"         # how the agent initiates talk-back mid-task:
                                   #   "mcp"  = EVE's talk-back MCP tools (hermes)
                                   #   "http" = POSTs EVE-shape JSON to the inbound route
                                   #   "a2a"  = native A2A messages (resume by remote task id;
                                   #            NOT enable-able until the resume contract test
                                   #            exists — see tests/test_talkback_contract.py)
                                   #   "none" = no mid-task talk-back


# Same-chat continuity (2026-07-02, the owner): a delegation whose task text begins with a
# "[RESUME-SESSION:<id>]" line continues that EXISTING hermes chat session — verified live:
# `hermes chat -q <msg> --resume <id> -Q` recalls prior context and prints "session_id: <id>"
# on stdout, which run_delegate captures so EVERY hermes run's session is resumable later.
# (Native /slash commands only dispatch in hermes's fully interactive loop — headless paths
# hand them to the LLM — so follow-ups are plain language, passed verbatim.)
RESUME_LINE_PREFIX = "[RESUME-SESSION:"


def _split_resume(task: str):
    """('<session id>' | None, task without the resume line). Code-parsed, never LLM-driven."""
    if task.startswith(RESUME_LINE_PREFIX):
        head, _, rest = task.partition("\n")
        sid = head[len(RESUME_LINE_PREFIX):].rstrip("]").strip()
        if sid:
            return sid, rest
    return None, task


REGISTRY = {
    "hermes": DelegateSpec(
        name="hermes", transport="cli",
        command=(HERMES_BIN, "chat", "-q", "{task}", "--ignore-rules", "--yolo", "-Q"),
        specialty=("messaging on channels {assistant} can't reach herself — telegram, slack, discord, "
                   "matrix, etc. — and cross-channel scheduling. NOT plain SMS or email: {assistant} "
                   "sends those directly with her own tools (use prepare_text for a text message)"),
        callback="poll", risk="high", allow_redelegate=False, side_effecting=True, enabled=True,
        talkback="mcp"),  # EVE Talkback MCP tools (notify_eve / ask_eve) — proven live
    "jarvis": DelegateSpec(
        name="jarvis", transport="cli", command=(),
        specialty="generalist agentic brain (web, files, shell/code, git, memory)",
        callback="poll", risk="medium", allow_redelegate=True, side_effecting=True,
        enabled=False,    # Phase 3: fold the existing jarvis_agent into the registry
        talkback="http"), # its orchestrator POSTs EVE-shape JSON to the inbound route
    "open_claw": DelegateSpec(
        name="open_claw", transport="ws",
        specialty="breadth — 20+ live consumer channels + multi-agent routing",
        callback="push", risk="high", allow_redelegate=False, side_effecting=True,
        enabled=False,    # Phase 2 BLOCKED slot (WebSocket gateway), does not block Hermes
        talkback="http"), # an acpx/ws bridge POSTs EVE-shape JSON to the same inbound route
}


def tool_name_for(spec) -> str:
    return f"delegate_{spec.name}"


def last_session_for(agent: str):
    """Most recent chat session id this agent left on a finished task — the handle for
    'continue that same conversation'. None if no session is on record."""
    import agent_tasks
    for r in agent_tasks.list_for_audit(agent, 10):
        sid = (r.get("result") or {}).get("session_id")
        if sid:
            return sid
    return None


def delegate_schemas():
    """One FunctionSchema per ENABLED spec. Each carries `task` (required) and `confirmed`
    (optional) so the tool_policy draft-readback confirm flow can complete (spec §11.E)."""
    from persona import ASSISTANT_NAME  # configured self-name; EVE is a product
    out = []
    for spec in REGISTRY.values():
        if not spec.enabled:
            continue
        # Specialty text carries an optional "{assistant}" placeholder (only hermes uses it);
        # .format is a no-op on the others (no braces), so the self-name stays configurable.
        specialty = spec.specialty.format(assistant=ASSISTANT_NAME)
        out.append(FunctionSchema(
            name=tool_name_for(spec),
            description=(
                f"Hand a task to the {spec.name} agent — {specialty}. {ASSISTANT_NAME} confirms the "
                "handoff before it runs and tells you when it comes back. Use for work that "
                "agent specializes in; state the task fully in plain language."),
            properties={
                "task": {"type": "string",
                         "description": "The task, stated fully and precisely in plain language."},
                "continue_conversation": {
                    "type": "boolean",
                    "description": ("True to CONTINUE the same chat with this agent — it keeps "
                                    "all context from the last hand-off ('tell it to also…', "
                                    "'ask it in the same chat…'). Omit to start fresh.")},
                "confirmed": {"type": "boolean",
                              "description": "Set true only after the user approves the read-back."},
            },
            required=["task"]))
    return out


async def _run_cli(argv, env=None, timeout_s=None):
    """Run a subprocess to completion with a hard timeout + kill+reap (mirrors
    agent_bridge._run_cli) — so a hung delegate can't hold a poller lease forever."""
    proc = await asyncio.create_subprocess_exec(
        *argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=env)
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout_s or HARD_S)
    except asyncio.TimeoutError:
        proc.kill()
        try:
            await proc.communicate()      # reap the killed child so it can't linger
        except Exception:
            pass
        raise
    return proc.returncode or 0, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


def _clean_chat_output(out: str, err: str = ""):
    """Strip `hermes chat -q -Q` bookkeeping: '↻ …' status lines and the 'session_id: <id>'
    line — which hermes prints on STDERR (verified live; stdout carries only the reply, but
    both streams are scanned defensively). Returns (text, session_id|None)."""
    sid = None
    kept = []
    for line in out.splitlines():
        stripped = line.strip()
        if stripped.startswith("↻"):
            continue
        if stripped.startswith("session_id:"):
            sid = stripped.split(":", 1)[1].strip()
            continue
        kept.append(line)
    if sid is None:
        for line in err.splitlines():
            stripped = line.strip()
            if stripped.startswith("session_id:"):
                sid = stripped.split(":", 1)[1].strip()
                break
    return "\n".join(kept).strip(), sid


async def run_delegate(spec, task, *, timeout_s=None):
    """Run the delegate to completion ONCE. Called by the poller (the sole executor for
    side-effecting agents) — no shield, no detach, so a side-effecting one-shot is never raced
    by a second runner. Returns {"ok": bool, "text": str, "session_id"?: str} — session_id is
    the hermes chat session this run lives in (resume it to continue the same conversation)."""
    if spec.transport != "cli":
        return {"ok": False, "text": f"{spec.name}: transport {spec.transport!r} not wired yet"}
    session, task = _split_resume(task)
    argv = [a.replace("{task}", task) for a in spec.command]
    if session:
        argv += ["--resume", session]
    # Log a preview only: the enriched task text carries the per-task callback_token (talk-back
    # header) — the full text must not land in the journal.
    logger.info(f"run_delegate [{spec.name}] task_preview={task[-160:]!r} len={len(task)}"
                + (f" resume={session}" if session else ""))
    try:
        code, out, err = await _run_cli(argv, timeout_s=timeout_s or HARD_S)
    except asyncio.TimeoutError:
        return {"ok": False, "text": f"{spec.name} timed out after {int(timeout_s or HARD_S)}s"}
    except Exception as e:
        return {"ok": False, "text": f"{spec.name} failed to run: {e}"}
    if code != 0:
        return {"ok": False, "text": (err or out)[:200].strip() or f"{spec.name} exited {code}"}
    text, sid = _clean_chat_output(out, err)
    result = {"ok": True, "text": text[:1500]}
    if sid:
        result["session_id"] = sid
    return result


async def poll_tick(deliver, *, lease_s, hard_s, run=None):
    """One poller pass (EVE Agent Hub spec §4.6, §11.G) — the SOLE executor of side-effecting
    delegate tasks, so each task runs exactly once. Reap abandoned leases, then for every enabled
    poll-delivery spec: claim a pending task (token-CAS lease), run it ONCE to completion, resolve
    fenced by the lease's claim_token, finish + deliver the resolved row (or fail honestly).
    `deliver(row)` is an awaitable the caller supplies (announce + Activity receipt); `run` lets
    tests inject a fake delegate runner. Returns the number of tasks handled. Pure of pipecat."""
    import agent_tasks

    runner = run or run_delegate
    await asyncio.to_thread(agent_tasks.reap)
    handled = 0
    for spec in (s for s in REGISTRY.values() if s.enabled and s.callback == "poll"):
        for row in await asyncio.to_thread(agent_tasks.claim_for, spec.name, lease_s):
            out = await runner(spec, row["task"], timeout_s=hard_s)
            won = await asyncio.to_thread(agent_tasks.resolve, row["id"],
                                          claim_token=row["claim_token"])
            if not won:
                continue                      # a callback / zombie already took it
            if out.get("ok"):
                finished = {"ok": True, "text": out["text"]}
                if out.get("session_id"):
                    finished["session_id"] = out["session_id"]   # same-chat resume handle
                await asyncio.to_thread(agent_tasks.finish, row["id"], finished)
                await deliver(await asyncio.to_thread(agent_tasks.get, row["id"]))
            else:
                await asyncio.to_thread(agent_tasks.fail, row["id"], out.get("text"))
                # A blocker is a result too: deliver the failed row the same way successes are
                # delivered, so a hand-off that couldn't be completed never vanishes silently.
                await deliver(await asyncio.to_thread(agent_tasks.get, row["id"]))
            handled += 1
    return handled
