#!/usr/bin/env python3
# eve_talkback_mcp.py — EVE's talk-back MCP server (talk-back spec §4.1).
#
# A zero-dependency stdio MCP server (newline-delimited JSON-RPC 2.0) that Hermes spawns per
# session. It gives the agent two STRUCTURED ways to reach EVE mid-task — no "please curl":
#   notify_eve(correlation_id, callback_token, kind, text)   fire-and-forget, NON-terminal
#   ask_eve(correlation_id, callback_token, question)        blocks until the human answers
# Both are thin authenticated HTTP calls to EVE's ONE gated inbound contract on loopback
# (:8787 /agent/a2a/<token>); the per-task callback_token is the capability. Injection-safe by
# construction: EVE only stages/relays what arrives — nothing here can execute anything.
#
# Protocol discipline: requests are answered; NOTIFICATIONS (no id) are never answered;
# initialize/ping/tools/list answer INLINE on the reader thread (no I/O), while tools/call runs
# on a bounded worker pool so a blocked ask_eve can't starve pings and a looping agent can't
# spawn unbounded threads. stdout writes are serialized; logs go to stderr only. Transport
# errors/429s are retried until the ask deadline — only the deadline yields the graceful
# non-answer (an EVE restart mid-ask must not abort the ask).
#
# stdlib only. Runs under any python3 (no venv assumption).
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

def _cfg(name, default=""):
    """Env first; then argv KEY=VALUE pairs (belt-and-braces: `hermes mcp add --args` is
    greedy, so a mis-ordered registration lands the env pair in OUR argv — accept it)."""
    val = os.getenv(name)
    if val:
        return val
    for a in sys.argv[1:]:
        if a.startswith(f"{name}="):
            return a.split("=", 1)[1]
    return default


INBOUND_URL = _cfg("EVE_TALKBACK_INBOUND_URL").rstrip("/")
LINK_KEY = _cfg("EVE_AGENT_LINK_KEY")          # standing link: unsolicited messages to EVE
LINK_AGENT = _cfg("EVE_LINK_AGENT", "hermes")
ASK_WAIT_S = float(os.getenv("EVE_TALKBACK_ASK_WAIT_S", "840"))
POLL_S = float(os.getenv("EVE_TALKBACK_POLL_S", "2"))
HTTP_TIMEOUT_S = float(os.getenv("EVE_TALKBACK_HTTP_TIMEOUT_S", "10"))
MAX_WORKERS = int(os.getenv("EVE_TALKBACK_MAX_WORKERS", "8"))

# The assistant's configured self-name, spoken to the delegated agent in tool
# descriptions/results. Read straight from env (this module is stdlib-only and is
# spawned standalone by Hermes, so it must NOT import persona). Mirrors persona's
# JARVIS_ASSISTANT_NAME default. Protocol identifiers (tool names notify_eve /
# ask_eve / message_eve, the X-EVE-Callback-Token header, the eve-talkback server
# name, env var names) are NOT the self-name and stay fixed.
ASSISTANT_NAME = os.getenv("JARVIS_ASSISTANT_NAME", "Atlas")
# The owner's name for surfacing answers back to the delegated agent; neutral
# fallback when unset (same env var the persona layer reads).
OWNER_NAME = os.getenv("JARVIS_USER_NAME", "").strip() or "The owner"

_stdout_lock = threading.Lock()


def _log(msg):
    print(f"[eve-talkback] {msg}", file=sys.stderr, flush=True)


def _reply(id_, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": id_}
    if error is not None:
        msg["error"] = error
    else:
        msg["result"] = result
    with _stdout_lock:
        sys.stdout.write(json.dumps(msg) + "\n")
        sys.stdout.flush()


def _post(path_suffix, payload, headers=None):
    """One authenticated POST to EVE. Returns (status, dict|None); never raises.
    status 0 == transport error (EVE unreachable — retryable)."""
    url = INBOUND_URL + path_suffix
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json",
                                          **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace") or "{}")
        except Exception:
            return e.code, None
    except Exception as e:
        _log(f"transport error: {e!r}")
        return 0, None


TOOLS = [
    {
        "name": "notify_eve",
        "description": (f"Send {ASSISTANT_NAME} (your delegator) a mid-task update: kind=progress for status, "
                        "kind=blocker when you're stuck but still trying, kind=result for an "
                        "interim finding. Informational only — it never ends your task; finish "
                        "the task normally and use progress sparingly. Use the correlation_id "
                        "and callback_token from your task header, copied exactly."),
        "inputSchema": {"type": "object", "properties": {
            "correlation_id": {"type": "string"},
            "callback_token": {"type": "string"},
            "kind": {"type": "string", "enum": ["progress", "result", "blocker"]},
            "text": {"type": "string"}},
            "required": ["correlation_id", "callback_token", "kind", "text"]},
    },
    {
        "name": "ask_eve",
        "description": ("Ask the human owner a question and WAIT for the answer (it may take "
                        "many minutes — that is expected; do not give up early). Use it when "
                        "you genuinely need a decision to continue. Use the correlation_id and "
                        "callback_token from your task header, copied exactly."),
        "inputSchema": {"type": "object", "properties": {
            "correlation_id": {"type": "string"},
            "callback_token": {"type": "string"},
            "question": {"type": "string"}},
            "required": ["correlation_id", "callback_token", "question"]},
    },
    {
        "name": "message_eve",
        "description": (f"Send {ASSISTANT_NAME} (the house agent) a message ANYTIME — no active delegation "
                        "or correlation_id needed. Use it to report news, finished work, or "
                        f"anything the owner should hear: {ASSISTANT_NAME} speaks it to him live, or "
                        "push-notifies him when he's away. kind=message (default) for normal "
                        "news; kind=blocker only for urgent problems. The message is relayed "
                        "verbatim and never executed. Don't spam — one consolidated message "
                        "beats many small ones. If you know your own chat session id (it may "
                        "be in your system prompt), pass it as session_id so the owner can "
                        "reply to you in this same conversation later."),
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string"},
            "kind": {"type": "string", "enum": ["message", "blocker"]},
            "session_id": {"type": "string"}},
            "required": ["text"]},
    },
]


def _tool_text(text, is_error=False):
    return {"content": [{"type": "text", "text": text}], "isError": bool(is_error)}


def _do_notify(args):
    status, body = _post("", {
        "correlation_id": str(args.get("correlation_id", "")),
        "callback_token": str(args.get("callback_token", "")),
        "state": "working", "kind": str(args.get("kind", "progress")),
        "result": {"text": str(args.get("text", ""))[:2000]}})
    if status == 200 and (body or {}).get("directive"):
        # The owner steered this task from the app (cancel/redirect): the check-in response
        # IS the control channel. Relay the order verbatim — not an error, an instruction.
        return _tool_text(str(body["directive"]))
    if status == 200 and (body or {}).get("ok"):
        return _tool_text(f"{ASSISTANT_NAME} received your update.")
    return _tool_text(f"could not reach {ASSISTANT_NAME} (status={status}): {body}", is_error=True)


def _do_message(args):
    """Standing-link message: authenticated by the long-lived pairing key, not a per-task
    token — works with no active delegation. Same ONE inbound URL; the link_key field is
    what routes it to the link handler on EVE's side."""
    if not LINK_KEY:
        return _tool_text(f"no standing link is paired — run scripts/link_pair.py on {ASSISTANT_NAME}'s "
                          "box (EVE_AGENT_LINK_KEY is unset).", is_error=True)
    payload = {
        "link_key": LINK_KEY, "agent": LINK_AGENT,
        "kind": str(args.get("kind") or "message"),
        "text": str(args.get("text", ""))[:2000]}
    if args.get("session_id"):
        payload["session_id"] = str(args["session_id"])[:64]
    status, body = _post("", payload)
    if status == 200 and (body or {}).get("ok"):
        return _tool_text(f"{ASSISTANT_NAME} received your message and will relay it to the owner.")
    if status == 429:
        return _tool_text(f"{ASSISTANT_NAME} is rate-limiting link messages — wait a few seconds and send "
                          "ONE consolidated message.", is_error=True)
    return _tool_text(f"could not reach {ASSISTANT_NAME} (status={status}): {body}", is_error=True)


def _do_ask(args):
    cid = str(args.get("correlation_id", ""))
    tok = str(args.get("callback_token", ""))
    deadline = time.monotonic() + ASK_WAIT_S
    qid = None
    # 1) submit the question — transport errors are retried until the deadline (an EVE
    #    restart mid-ask must not abort the ask); definitive rejections exit immediately.
    while time.monotonic() < deadline and qid is None:
        status, body = _post("", {"correlation_id": cid, "callback_token": tok,
                                  "state": "input_required",
                                  "question": str(args.get("question", ""))[:1000]})
        if status in (403, 404):
            return _tool_text(f"{ASSISTANT_NAME} rejected the question (status={status}) — check your "
                              "correlation_id/callback_token.", is_error=True)
        if status == 200 and (body or {}).get("directive"):
            # Owner cancelled/redirected — the question is moot; relay the order.
            return _tool_text(str(body["directive"]))
        if status == 200 and (body or {}).get("staged"):
            qid = (body or {}).get("question_id")
            break
        if status == 200 and (body or {}).get("ok") and not (body or {}).get("staged"):
            # Definitive: the task is not open for questions (already finished/answered) —
            # do NOT stall until the deadline (W11).
            return _tool_text(f"{ASSISTANT_NAME} says this task is not open for questions — continue with "
                              "your best judgment or finish the task.")
        if status == 200 and body is not None and body.get("ok") is False:
            return _tool_text(f"{ASSISTANT_NAME} could not stage the question: {body}", is_error=True)
        time.sleep(min(POLL_S, 2.0))
    if qid is None:
        return _tool_text(f"{ASSISTANT_NAME} was unreachable — the owner was NOT asked. Continue with your "
                          "best judgment or report a blocker.", is_error=True)
    # 2) poll for the answer until the deadline; only the deadline yields the non-answer.
    while time.monotonic() < deadline:
        status, body = _post("/answer", {"correlation_id": cid, "question_id": qid},
                             headers={"X-EVE-Callback-Token": tok})
        if status == 200 and (body or {}).get("directive"):
            # Owner cancelled mid-question: stop polling and hand the agent the order now.
            return _tool_text(str(body["directive"]))
        if status == 200 and (body or {}).get("answered"):
            return _tool_text(f"{OWNER_NAME} answered: {body.get('answer', '')}")
        time.sleep(POLL_S)
    return _tool_text("No answer yet — the owner was notified and can send it later. Continue "
                      "with your best judgment, or report a blocker mentioning the open "
                      "question.")


def _run_tool(id_, name, arguments):
    try:
        if name == "notify_eve":
            _reply(id_, _do_notify(arguments))
        elif name == "ask_eve":
            _reply(id_, _do_ask(arguments))
        elif name == "message_eve":
            _reply(id_, _do_message(arguments))
        else:
            _reply(id_, error={"code": -32602, "message": f"unknown tool {name!r}"})
    except Exception as e:  # a tool crash must answer, not hang hermes
        _log(f"tool {name} crashed: {e!r}")
        _reply(id_, _tool_text(f"tool error: {e}", is_error=True))


def main():
    if not INBOUND_URL:
        _log("EVE_TALKBACK_INBOUND_URL is not set — tools will fail closed")
    pool = ThreadPoolExecutor(max_workers=MAX_WORKERS)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        method, id_ = msg.get("method"), msg.get("id")
        if id_ is None:
            continue                                 # notification: NEVER respond
        if method == "initialize":
            _reply(id_, {"protocolVersion": msg.get("params", {}).get("protocolVersion",
                                                                      "2025-06-18"),
                         "capabilities": {"tools": {}},
                         "serverInfo": {"name": "eve-talkback", "version": "1.0.0"}})
        elif method == "ping":
            _reply(id_, {})
        elif method == "tools/list":
            _reply(id_, {"tools": TOOLS})
        elif method == "tools/call":
            p = msg.get("params", {})
            pool.submit(_run_tool, id_, p.get("name"), p.get("arguments") or {})
        else:
            _reply(id_, {})
    # stdin EOF: hermes went away — daemonized pool threads die with the process; EVE-side
    # state recovers via TTL/replay.


if __name__ == "__main__":
    main()
