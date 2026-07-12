#!/usr/bin/env python3
"""Live talk-back harness (pre-restart acceptance proof, talk-back spec §8).

Stands up the REAL production path — real `hermes -z` via the REAL A2A adapter (uvicorn
subprocess, auth key) with the REAL inbound bridge (add_inbound_route + agent_delivery) — on
EPHEMERAL ports with a SCRATCH approvals DB, so nothing touches the live jarvis-sidecar
(:8787) or its data. "EVE speaks" is a printed line (the exact instruction the voice LLM
would receive); everything else is production code.

Scenarios (run: .venv/bin/python scripts/talkback_harness.py [result|blocker|question|all]):
  result   — benign task; hermes completes; EVE speaks the result; row -> resolved.
  blocker  — impossible task; hermes reports the blocker mid-run via notify_eve(kind=blocker)
             and/or fails; EVE speaks the blocker; check_delegations-style audit shows why.
  question — hermes calls ask_eve and BLOCKS; a gated approval is staged (never executed);
             the harness answers via a2a_fabric.resume() (the same call the voice
             resume_delegate tool makes); hermes CONTINUES to completion.

It temporarily registers an 'eve-harness' MCP server in ~/.hermes/config.yaml (pointing at
the harness's inbound URL) and removes it on exit.
"""
import asyncio
import json
import os
import pathlib
import secrets
import socket
import subprocess
import sys
import tempfile
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))

# ---- scratch environment BEFORE importing eve modules -------------------------------------
SCRATCH = tempfile.mkdtemp(prefix="talkback-harness-")
DB = os.path.join(SCRATCH, "approvals.db")
ADAPTER_KEY = secrets.token_urlsafe(16)


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


INBOUND_PORT = _free_port()
ADAPTER_PORT = _free_port()
INBOUND_URL = f"http://127.0.0.1:{INBOUND_PORT}/agent/a2a/HARNESS"

os.environ.update({
    "EVE_APPROVAL_DB": DB,
    "EVE_A2A_ENABLED": "1",
    "EVE_A2A_ADAPTER_KEY": ADAPTER_KEY,
    "EVE_A2A_PORT": str(ADAPTER_PORT),
    "EVE_A2A_HERMES_URL": f"http://127.0.0.1:{ADAPTER_PORT}",
    "EVE_A2A_INBOUND_URL": INBOUND_URL,
    "EVE_QUIET_HOURS": "",                      # live-session semantics: speak, don't notify
})

import a2a_fabric            # noqa: E402
import agent_delivery        # noqa: E402
import agent_tasks           # noqa: E402
import approval_store        # noqa: E402

SPOKEN: list = []
BROADCAST: list = []


async def _announce(instruction):
    SPOKEN.append(instruction)
    print(f"\n🗣  EVE WOULD SPEAK (instruction to the voice LLM):\n    {instruction}\n")


async def _deliver(row, kind=None, text=None):
    return await agent_delivery.deliver_update(
        row, announce=_announce, broadcast=BROADCAST.append,
        is_alive=lambda: True, kind=kind, text=text)


def _mcp_env_url():
    return INBOUND_URL


HERMES_CFG = pathlib.Path.home() / ".hermes" / "config.yaml"
_ORIG_HERMES_CFG = None


def register_harness_mcp():
    """Point the PRODUCTION `eve` MCP server at the harness inbound for the duration.

    The delegation header names `eve` as the sanctioned server, so hermes uses IT — a
    separately-registered `eve-harness` is ignored and every push 404s against the scratch
    DB (proven live 2026-07-10). Line-surgical URL swap (link_pair.py style), exact
    original config restored on exit. NOTE: while the harness runs, a concurrent REAL
    delegation's notify/ask would 404 — run the harness when no live hand-off is in flight.
    """
    global _ORIG_HERMES_CFG
    txt = HERMES_CFG.read_text(encoding="utf-8")
    marker = "EVE_TALKBACK_INBOUND_URL:"
    assert marker in txt, "production eve MCP server not in ~/.hermes/config.yaml"
    _ORIG_HERMES_CFG = txt
    import re
    new_txt = re.sub(r"EVE_TALKBACK_INBOUND_URL:\s*\S+",
                     f"EVE_TALKBACK_INBOUND_URL: {_mcp_env_url()}", txt, count=1)
    HERMES_CFG.write_text(new_txt, encoding="utf-8")
    print(f"• repointed hermes MCP server 'eve' -> {_mcp_env_url()} (restored on exit)")


def remove_harness_mcp():
    global _ORIG_HERMES_CFG
    if _ORIG_HERMES_CFG is not None:
        HERMES_CFG.write_text(_ORIG_HERMES_CFG, encoding="utf-8")
        _ORIG_HERMES_CFG = None
        print("• restored hermes MCP server 'eve' to the live inbound URL")
    subprocess.run(["hermes", "mcp", "remove", "eve-harness"], capture_output=True, text=True)


async def start_inbound():
    from aiohttp import web
    app = web.Application()
    a2a_fabric.add_inbound_route(app, "HARNESS", deliver=_deliver, broadcast=BROADCAST.append)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", INBOUND_PORT).start()
    print(f"• inbound bridge live on :{INBOUND_PORT} (real handle_push + agent_delivery)")
    return runner


def start_adapter():
    env = {**os.environ}
    log = open(os.path.join(SCRATCH, "adapter.log"), "w")
    print(f"• adapter log: {log.name}")
    proc = subprocess.Popen(
        [str(REPO / ".venv" / "bin" / "python"), str(REPO / "scripts" / "run_a2a_adapter.py")],
        env=env, stdout=log, stderr=subprocess.STDOUT, text=True)
    # wait for uvicorn to come up
    import urllib.request
    for _ in range(50):
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{ADAPTER_PORT}/.well-known/agent-card.json",
                headers={"X-EVE-A2A-Key": ADAPTER_KEY})
            with urllib.request.urlopen(req, timeout=1) as r:
                if r.status == 200:
                    print(f"• A2A adapter live on :{ADAPTER_PORT} (auth key required)")
                    return proc
        except Exception:
            time.sleep(0.3)
    proc.kill()
    raise RuntimeError("adapter did not come up")


async def wait_for(cid, statuses, timeout_s=600):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        row = agent_tasks.get(cid)
        if row and row["status"] in statuses:
            return row
        await asyncio.sleep(1.0)
    raise TimeoutError(f"cid={cid} never reached {statuses}; "
                       f"now={agent_tasks.get(cid)['status']}")


def evidence(cid, label):
    row = agent_tasks.get(cid)
    print(f"── EVIDENCE [{label}] "
          f"cid={cid[:8]} status={row['status']} delivery={row['delivery']} "
          f"delivered_at={'set' if row['delivered_at'] else 'NULL'}")
    if row.get("result"):
        print(f"   result: {json.dumps(row['result'])[:220]}")
    if row.get("question"):
        print(f"   question: {json.dumps(row['question'])[:220]}")
    print(f"   spoken lines so far: {len(SPOKEN)}  broadcasts: "
          f"{[b['type'] for b in BROADCAST]}")


async def scenario_result():
    print("\n===== SCENARIO: result talk-back (real hermes, real adapter) =====")
    cid = await a2a_fabric.delegate(
        "Reply with exactly one short sentence stating that the talkback harness result test "
        "passed. Do not use any tools. Do not send any messages anywhere.",
        requester="Owner", tier="owner", ttl_s=1200)
    print(f"• delegated over A2A, cid={cid[:8]} (delivery=push — poller can never run it)")
    row = await wait_for(cid, ("resolved", "failed"))
    evidence(cid, "result")
    assert row["status"] == "resolved", f"expected resolved, got {row['status']}"
    assert any("finished" in s or "RESULT" in s for s in SPOKEN), "EVE never spoke the result"
    assert any(b["type"] == "agent_result" for b in BROADCAST)
    print("✅ result talk-back: hermes ran once, EVE spoke the result, row resolved")


async def scenario_blocker():
    print("\n===== SCENARIO: blocker talk-back =====")
    cid = await a2a_fabric.delegate(
        "Post the message 'harness test' to the Slack channel #eve-talkback-harness. You have "
        "no Slack access configured, so this will not be possible. When you hit the blocker, "
        "call the notify_eve tool with kind='blocker' describing exactly what is missing, then "
        "end your run stating clearly that the task FAILED because of that blocker. Do not "
        "pretend success. Do not message any other platform.",
        requester="Owner", tier="owner", ttl_s=1200)
    print(f"• delegated over A2A, cid={cid[:8]}")
    row = await wait_for(cid, ("resolved", "failed"))
    evidence(cid, "blocker")
    blocker_spoken = any("snag" in s or "BLOCKER" in s or "could NOT" in s for s in SPOKEN)
    assert blocker_spoken, "EVE never spoke a blocker line"
    audit = [r for r in agent_tasks.list_for_audit("hermes", 5) if r["id"] == cid][0]
    reason = ((audit.get("result") or {}).get("text")
              or (audit.get("result") or {}).get("error") or "")
    print(f"   audit reason (check_delegations view): {reason[:180]}")
    assert reason, "audit trail has no blocker reason"
    print("✅ blocker talk-back: blocker spoken + audit shows why")


async def scenario_question():
    print("\n===== SCENARIO: blocking question talk-back (the real prize) =====")
    cid = await a2a_fabric.delegate(
        "You must ask your delegator a question before doing anything else. Call the ask_eve "
        "tool with the question: 'Should the standup post go to the general channel or the "
        "standup channel?' and WAIT for the answer (it may take a while — do not give up). "
        "After you receive the answer, reply with one short sentence confirming which channel "
        "Owner chose, and finish. Do not actually post anything anywhere.",
        requester="Owner", tier="owner", ttl_s=1800)
    print(f"• delegated over A2A, cid={cid[:8]}")
    row = await wait_for(cid, ("awaiting_user",), timeout_s=300)
    q = row["question"]
    print(f"• hermes is BLOCKED on: {q['question']!r} (qid={q['qid']})")
    pend = [a for a in approval_store.list_pending() if a["id"] == q["approval_id"]]
    assert pend, "no gated approval staged for the question"
    print(f"• gated approval staged (id={q['approval_id'][:8]}, tool={pend[0]['tool']}) — "
          "STAGED, not executed; a real away-Owner would get the ntfy/Telegram push here")
    assert any("waiting on the user's answer" in s or "QUESTION" in s for s in SPOKEN), \
        "EVE never relayed the question"
    print("• answering as Owner (same call the gated resume_delegate tool makes) in 3s …")
    await asyncio.sleep(3)
    res = await a2a_fabric.resume(cid, "the standup channel")
    assert res.get("ok"), f"resume failed: {res}"
    print(f"• answer stored -> ANSWERED (+TTL bump, approval closed): {res}")
    row = await wait_for(cid, ("resolved", "failed"), timeout_s=600)
    evidence(cid, "question")
    assert row["status"] == "resolved", f"expected resolved, got {row['status']}"
    final = (row["result"] or {}).get("text", "").lower()
    assert "standup" in final, f"hermes did not act on the answer: {final!r}"
    ap = approval_store.get(q["approval_id"])
    assert ap["status"] == "denied", f"approval card not closed: {ap['status']}"
    print("✅ question talk-back: ask_eve blocked -> gated approval -> answer via the gate -> "
          "hermes CONTINUED to completion using the answer; card closed")


async def _wait_broadcast(evt_type, cid, timeout_s=240):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        for b in BROADCAST:
            if b.get("type") == evt_type and b.get("cid") == cid:
                return b
        await asyncio.sleep(0.5)
    raise TimeoutError(f"no {evt_type} broadcast for cid={cid[:8]}; "
                       f"saw {[b['type'] for b in BROADCAST]}")


async def scenario_cancel():
    print("\n===== SCENARIO: owner CANCELS a running task (live-delegation-approvals) =====")
    cid = await a2a_fabric.delegate(
        "Do the following IN ORDER. 1) Call the notify_eve tool with kind='progress' and "
        "text='starting a long job'. 2) Run the shell command `sleep 15`. 3) Call notify_eve "
        "again with kind='progress' and text='checking in'. Repeat steps 2-3 up to four more "
        "times, then reply with one sentence saying you finished. Do not use any other tools.",
        requester="Owner", tier="owner", ttl_s=1200)
    print(f"• delegated over A2A, cid={cid[:8]}")
    await _wait_broadcast("agent_progress", cid)
    print("• first live progress event landed (this is what the app's feed card shows)")
    t_cancel = time.time()
    st = agent_tasks.request_cancel(cid)
    print(f"• The owner hits Cancel in the app -> request_cancel -> {st!r} (honest: not "
          "'cancelled' yet — the stop is not observed)")
    assert st == agent_tasks.CANCEL_REQUESTED
    row = await wait_for(cid, ("cancelled",), timeout_s=240)
    dt = time.time() - t_cancel
    evidence(cid, "cancel")
    cancelled_evt = await _wait_broadcast("agent_task_cancelled", cid, timeout_s=10)
    assert cancelled_evt.get("status") == "cancelled"
    assert not any(b["type"] == "agent_result" and b.get("cid") == cid for b in BROADCAST), \
        "a cancelled task's result must never be announced"
    assert not any("finished" in s.lower() for s in SPOKEN[-3:]), \
        "EVE spoke a result for a cancelled task"
    print(f"✅ cancel: hermes got the STOP directive at its next check-in and the run "
          f"terminalized CANCELLED {dt:.1f}s after the button press; result discarded "
          f"unspoken; the app got the terminal agent_task_cancelled event")


async def scenario_redirect():
    print("\n===== SCENARIO: owner REDIRECTS a running task =====")
    cid = await a2a_fabric.delegate(
        "You are drafting a haiku about the ocean. Do the following IN ORDER. 1) Call the "
        "notify_eve tool with kind='progress' and text='drafting a haiku about the ocean'. "
        "2) Run the shell command `sleep 15`. 3) Call notify_eve with kind='progress' and "
        "text='second check-in'. 4) Finish by replying with ONLY the final haiku. Do not "
        "use any other tools.",
        requester="Owner", tier="owner", ttl_s=1200)
    print(f"• delegated over A2A, cid={cid[:8]}")
    await _wait_broadcast("agent_progress", cid)
    ok = agent_tasks.set_redirect(
        cid, "Make the haiku about MOUNTAINS instead of the ocean. Mention mountains "
             "explicitly.")
    print(f"• The owner sends a Redirect from the app -> set_redirect -> {ok}")
    assert ok is True
    delivered = await _wait_broadcast("agent_task_redirected", cid)
    assert delivered.get("status") == "redirect_delivered"
    print("• the steer LANDED at the agent's next check-in (redirect_delivered on the app "
          "stream — the feed shows it)")
    row = await wait_for(cid, ("resolved", "failed"), timeout_s=300)
    evidence(cid, "redirect")
    assert row["status"] == "resolved", f"expected resolved, got {row['status']}"
    final = (row["result"] or {}).get("text", "").lower()
    assert "mountain" in final, f"hermes did not follow the steer: {final!r}"
    print(f"✅ redirect: new instructions delivered mid-run and OBEYED — final output "
          f"followed the steer: {final[:120]!r}")


async def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    print(f"scratch DB: {DB}\ninbound: {INBOUND_URL}\nadapter: :{ADAPTER_PORT}")
    register_harness_mcp()
    inbound = await start_inbound()
    adapter = start_adapter()
    try:
        if which in ("result", "all"):
            await scenario_result()
        if which in ("blocker", "all"):
            await scenario_blocker()
        if which in ("question", "all"):
            await scenario_question()
        if which in ("cancel", "all"):
            await scenario_cancel()
        if which in ("redirect", "all"):
            await scenario_redirect()
        print("\n🏁 harness complete. Transport attribution: every scenario ran over the A2A "
              "adapter (delivery=push rows; adapter subprocess logs available).")
    finally:
        adapter.kill()
        await inbound.cleanup()
        remove_harness_mcp()


if __name__ == "__main__":
    asyncio.run(main())
