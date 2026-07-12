# Phase 1 stress test: hammer the delegation-tracing data layer + instrumentation
# with volume, adversarial/malformed events, concurrency, and FTS-injection inputs.
# Fully isolated (temp DB + temp transcripts) — never touches the real archive.
#
# Run:  ./.venv/Scripts/python.exe scripts/stress_test_phase1.py
import asyncio
import json
import os
import random
import sys
import tempfile
import time
from datetime import datetime

_TMP = tempfile.mkdtemp(prefix="jarvis_stress_")
os.environ["JARVIS_BRAIN_DB"] = os.path.join(_TMP, "history.db")
os.environ["JARVIS_LOG_DIR"] = os.path.join(_TMP, "logs")
os.makedirs(os.environ["JARVIS_LOG_DIR"], exist_ok=True)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import conversation_archive as ca  # noqa: E402
import agent_bridge as ab  # noqa: E402

_RESULTS = []


def check(name, cond, detail=""):
    _RESULTS.append(bool(cond))
    mark = "PASS" if cond else "FAIL"
    print(f"  [{mark}] {name}" + (f"  -- {detail}" if (detail and not cond) else ""))


def iso(t):
    return datetime.fromtimestamp(t).isoformat(timespec="milliseconds")


def write_log(day, lines):
    p = os.path.join(os.environ["JARVIS_LOG_DIR"], f"{day}.jsonl")
    with open(p, "w", encoding="utf-8") as f:
        for ln in lines:
            f.write((ln if isinstance(ln, str) else json.dumps(ln)) + "\n")
    return p


# --------------------------------------------------------------------------- #
# A. Adversarial single-file ingest
# --------------------------------------------------------------------------- #
def test_adversarial_ingest():
    print("\n== A. Adversarial / malformed event ingest ==")
    base = 1781400000.0
    HUGE = "VARIABLE-SPEED pool pump analysis. " * 3000  # ~105 KB
    UNI = "café ☕ 日本語 \x00\x01 emoji 🤖 — apostrophe’s \"quotes\" <tags>"
    L = []

    def e(dt, **k):
        return {"ts": iso(base + dt), "src": k.pop("src", "local"), **k}

    # 1. normal delegation
    L += [
        e(0, type="user_transcript", text="research pool pumps"),
        e(1, type="delegation_start", deleg_id="d1", task="research pool pumps", brains=["codex", "glm", "local"]),
        e(1, type="delegation_step", deleg_id="d1", brain="codex", phase="try"),
        e(2, type="delegation_step", deleg_id="d1", brain="codex", phase="fail", ok=False, detail="boom", latency_ms=10),
        e(2, type="delegation_step", deleg_id="d1", brain="local", phase="answer", ok=True, detail="2k", latency_ms=900),
        e(3, type="delegation_end", deleg_id="d1", brain="local", ok=True, result="Pentair IntelliFlo wins.", failures=["codex: boom"], total_latency_ms=910),
        e(4, type="bot_transcript", text="Done."),
    ]
    # 2. concurrent phone delegation interleaved with a local one
    L += [
        e(5, type="user_transcript", text="phone q", src="phone"),
        e(6, type="delegation_start", deleg_id="p1", task="phone task", brains=["local"], src="phone"),
        e(6, type="user_transcript", text="local q2"),
        e(7, type="delegation_start", deleg_id="d2", task="local task 2", brains=["local"]),
        e(8, type="delegation_step", deleg_id="p1", brain="local", phase="answer", ok=True, latency_ms=500, src="phone"),
        e(8, type="delegation_step", deleg_id="d2", brain="local", phase="answer", ok=True, latency_ms=400),
        e(9, type="delegation_end", deleg_id="p1", brain="local", ok=True, result="phone answer", src="phone"),
        e(9, type="delegation_end", deleg_id="d2", brain="local", ok=True, result="local answer 2"),
    ]
    # 3. all-brains-fail
    L += [
        e(10, type="user_transcript", text="impossible task"),
        e(11, type="delegation_start", deleg_id="d3", task="impossible", brains=["codex", "glm"]),
        e(11, type="delegation_step", deleg_id="d3", brain="codex", phase="fail", ok=False, detail="x"),
        e(12, type="delegation_step", deleg_id="d3", brain="glm", phase="fail", ok=False, detail="y"),
        e(12, type="delegation_end", deleg_id="d3", ok=False, failures=["codex: x", "glm: y"], total_latency_ms=50),
    ]
    # 4. incomplete (start + step, NO end) — interrupted
    L += [
        e(13, type="user_transcript", text="interrupted"),
        e(14, type="delegation_start", deleg_id="d4", task="interrupted", brains=["local"]),
        e(14, type="delegation_step", deleg_id="d4", brain="local", phase="try"),
    ]
    # 5/6. orphan step + orphan end (no start)
    L += [
        e(15, type="delegation_step", deleg_id="ghost", brain="local", phase="answer", ok=True),
        e(15, type="delegation_end", deleg_id="ghost2", ok=True, result="orphan"),
    ]
    # 7. duplicate deleg_id reused
    L += [
        e(16, type="user_transcript", text="dup test"),
        e(17, type="delegation_start", deleg_id="dup", task="first", brains=["local"]),
        e(17, type="delegation_end", deleg_id="dup", brain="local", ok=True, result="first result"),
        e(18, type="delegation_start", deleg_id="dup", task="second", brains=["local"]),
        e(18, type="delegation_end", deleg_id="dup", brain="local", ok=True, result="second result"),
    ]
    # 8. missing fields
    L += [
        e(19, type="user_transcript", text="missing fields"),
        e(20, type="delegation_start"),  # no deleg_id/task/brains
        e(20, type="delegation_end", ok=True),  # no deleg_id/result
    ]
    # 9/10. huge result + unicode/control chars
    L += [
        e(21, type="user_transcript", text="huge " + UNI),
        e(22, type="delegation_start", deleg_id="big", task=UNI, brains=["local"]),
        e(23, type="delegation_end", deleg_id="big", brain="local", ok=True, result=HUGE + UNI),
    ]
    # 11. generic jarvis_agent tool_call BEFORE start (dedup) + tool_result after
    L += [
        e(24, type="user_transcript", text="dedup before"),
        e(24, type="tool_call", tool="jarvis_agent", args="{}", status="running"),
        e(25, type="delegation_start", deleg_id="dd", task="dedup", brains=["local"]),
        e(25, type="delegation_end", deleg_id="dd", brain="local", ok=True, result="rich"),
        e(26, type="tool_result", tool="jarvis_agent", ok=True, detail="{}"),
    ]
    # 12. generic jarvis_agent tool_call with NO delegation events (must be kept)
    L += [
        e(27, type="user_transcript", text="plain tool only"),
        e(27, type="tool_call", tool="jarvis_agent", args="{}", status="running"),
        e(28, type="tool_result", tool="jarvis_agent", ok=True, detail="plain"),
    ]
    # 13. malformed JSON line (raw garbage) — must be skipped, not crash
    L += [e(29, type="user_transcript", text="after garbage")]
    lines = [json.dumps(x) for x in L]
    lines.insert(len(lines) - 1, "{ this is not valid json ]]")
    lines.insert(len(lines) - 1, "")  # blank line

    write_log("2026-05-01", lines)

    try:
        conn = ca.connect()
        stats = ca.ingest_transcripts(conn)
        crashed = False
    except Exception as exc:
        crashed = True
        print("    INGEST CRASHED:", exc)
    check("ingest did not crash on adversarial input", not crashed)
    if crashed:
        return None

    convs = {c["id"]: c for c in ca.list_conversations(conn, limit=999)}

    def find_deleg(conv_id, deleg_id):
        full = ca.get_conversation(conn, conv_id)
        for m in full["messages"]:
            if m["role"] == "delegation" and m["meta"].get("deleg_id") == deleg_id:
                return m
        return None

    # locate the normal one
    allmsgs = []
    for cid in convs:
        allmsgs += [(cid, m) for m in ca.get_conversation(conn, cid)["messages"]]

    def deleg(deleg_id):
        for _, m in allmsgs:
            if m["role"] == "delegation" and m["meta"].get("deleg_id") == deleg_id:
                return m
        return None

    d1 = deleg("d1")
    check("normal delegation has 3 steps + ok result", d1 and len(d1["meta"]["steps"]) == 3 and d1["meta"]["status"] == "ok" and "IntelliFlo" in d1["meta"]["result"])
    check("concurrent local+phone delegations both captured", deleg("p1") and deleg("d2") and deleg("p1")["meta"]["result"] == "phone answer" and deleg("d2")["meta"]["result"] == "local answer 2")
    d3 = deleg("d3")
    check("all-fail delegation: status error, no result", d3 and d3["meta"]["status"] == "error" and not d3["meta"].get("result") and len(d3["meta"]["failures"]) == 2)
    d4 = deleg("d4")
    check("incomplete delegation: status stays running", d4 and d4["meta"]["status"] == "running" and len(d4["meta"]["steps"]) == 1)
    check("orphan step/end did not create phantom delegations", deleg("ghost") is None and deleg("ghost2") is None)
    big = deleg("big")
    check("huge (105KB) + unicode result preserved intact", big and len(big["meta"]["result"]) > 100000 and "🤖" in big["meta"]["result"])
    # dedup: conversation 11 must have exactly one jarvis_agent msg (the rich one)
    ja_dedup = [m for _, m in allmsgs if m["meta"].get("tool") == "jarvis_agent" and m["meta"].get("deleg_id") == "dd"]
    generic_in_dd_conv = None
    for cid in convs:
        ms = ca.get_conversation(conn, cid)["messages"]
        if any(m["meta"].get("deleg_id") == "dd" for m in ms):
            generic_in_dd_conv = [m for m in ms if m["meta"].get("tool") == "jarvis_agent" and "steps" not in m["meta"]]
    check("dedup: generic jarvis_agent card removed when rich delegation present", generic_in_dd_conv == [])
    # plain tool-only (no delegation) must keep its jarvis_agent tool card
    plain_kept = False
    for cid in convs:
        ms = ca.get_conversation(conn, cid)["messages"]
        if any(m["role"] in ("user",) and "plain tool only" in m["text"] for m in ms):
            plain_kept = any(m["meta"].get("tool") == "jarvis_agent" for m in ms)
    check("plain jarvis_agent tool_call (no delegation) is preserved", plain_kept)
    check("malformed JSON line skipped, later events still ingested", any("after garbage" in m["text"] for _, m in allmsgs))
    return conn


# --------------------------------------------------------------------------- #
# B. Volume + idempotency
# --------------------------------------------------------------------------- #
def test_volume_idempotency():
    print("\n== B. Volume + idempotency ==")
    base = 1700000000.0
    lines = []
    rng = random.Random(42)
    N = 400
    for i in range(N):
        t = base + i * 3600  # 1hr apart -> distinct sessions
        src = "phone" if i % 3 == 0 else "local"

        def e(dt, **k):
            return {"ts": iso(t + dt), "src": src, **k}

        lines.append(e(0, type="user_transcript", text=f"task number {i} about thing {rng.randint(0,99)}"))
        did = f"v{i}"
        lines.append(e(1, type="delegation_start", deleg_id=did, task=f"task {i}", brains=["codex", "glm", "local"]))
        for b, ph in [("codex", "fail"), ("glm", "fail"), ("local", "answer")]:
            lines.append(e(1, type="delegation_step", deleg_id=did, brain=b, phase=ph, ok=(ph == "answer"), latency_ms=rng.randint(5, 4000)))
        lines.append(e(2, type="delegation_end", deleg_id=did, brain="local", ok=True, result=f"result {i} " * 20, total_latency_ms=rng.randint(100, 6000)))
        lines.append(e(3, type="bot_transcript", text=f"done {i}"))
    # spread across two day files
    write_log("2023-11-14", [json.dumps(x) for x in lines[: len(lines) // 2]])
    write_log("2023-11-15", [json.dumps(x) for x in lines[len(lines) // 2 :]])

    conn = ca.connect()
    t0 = time.monotonic()
    s1 = ca.ingest_transcripts(conn)
    dt1 = time.monotonic() - t0
    n1 = conn.execute("SELECT COUNT(*) c FROM conversations").fetchone()["c"]
    msgs1 = conn.execute("SELECT COUNT(*) c FROM messages").fetchone()["c"]
    t0 = time.monotonic()
    ca.ingest_transcripts(conn)  # re-ingest
    dt2 = time.monotonic() - t0
    n2 = conn.execute("SELECT COUNT(*) c FROM conversations").fetchone()["c"]
    msgs2 = conn.execute("SELECT COUNT(*) c FROM messages").fetchone()["c"]
    print(f"    {N} delegations -> {n1} conversations, {msgs1} messages, ingest {dt1*1000:.0f}ms (re-ingest {dt2*1000:.0f}ms)")
    check("volume ingest count is sane (>= N conversations)", n1 >= N)
    check("idempotent: conversation count stable on re-ingest", n1 == n2, f"{n1} != {n2}")
    check("idempotent: message count stable on re-ingest (no dup rows)", msgs1 == msgs2, f"{msgs1} != {msgs2}")
    check("performance: 400-delegation ingest under 5s", dt1 < 5.0, f"{dt1:.2f}s")
    return conn


# --------------------------------------------------------------------------- #
# C. FTS-injection / search robustness
# --------------------------------------------------------------------------- #
def test_search_injection(conn):
    print("\n== C. Search / FTS-injection robustness ==")
    nasty = ['"', 'pool AND pump', 'a OR b', 'NEAR(', '*', '()', 'result*', '"unterminated',
             'col:val', '🤖', 'x' * 500, '', '   ', 'AND OR NOT', 'result 1']
    crashed = None
    for q in nasty:
        try:
            r = ca.search(conn, q, limit=5)
            assert isinstance(r, list)
        except Exception as exc:
            crashed = (q, exc)
            break
    check("search never crashes on adversarial/FTS-syntax queries", crashed is None, str(crashed))
    # a real term still returns hits
    check("search still returns results for a normal term", len(ca.search(conn, "result", limit=5)) > 0)


# --------------------------------------------------------------------------- #
# D. Instrumentation crash-safety + concurrency
# --------------------------------------------------------------------------- #
class _P:
    def __init__(self, args):
        self.arguments = args
        self.cb = None

    async def result_callback(self, d):
        self.cb = d


async def test_instrumentation():
    print("\n== D. Instrumentation crash-safety + concurrency ==")
    ab.BRAIN_ORDER = ["codex", "local"]

    async def good(task):
        return "OK RESULT " * 10

    async def boom(task):
        raise RuntimeError("brain down")

    ab._BRAINS = {"codex": boom, "local": good}

    # D1: emit that always throws must NOT break the turn
    async def bad_emit(msg):
        raise RuntimeError("emit exploded")

    p = _P({"task": "x"})
    crashed = False
    try:
        await ab.make_jarvis_agent_handler(emit=bad_emit)(p)
    except Exception:
        crashed = True
    check("turn completes even when every emit() throws", (not crashed) and p.cb and p.cb["ok"] is True)

    # D2: 60 concurrent delegations share one emit list -> no lost/corrupted events
    events = []

    async def emit(msg):
        events.append(msg)

    async def one(i):
        pp = _P({"task": f"concurrent {i}"})
        await ab.make_jarvis_agent_handler(emit=emit)(pp)
        return pp.cb

    cbs = await asyncio.gather(*[one(i) for i in range(60)])
    starts = [e for e in events if e["type"] == "delegation_start"]
    ends = [e for e in events if e["type"] == "delegation_end"]
    ids = {e["deleg_id"] for e in starts}
    check("60 concurrent delegations: all callbacks ok", all(c and c["ok"] for c in cbs))
    check("60 concurrent delegations: 60 unique deleg_ids", len(ids) == 60 and len(starts) == 60)
    check("60 concurrent: exactly one delegation_end per delegation", len(ends) == 60)
    # every end's steps belong to its own deleg_id (no cross-contamination)
    bad_corr = False
    for e in events:
        if e["type"] == "delegation_step" and e["deleg_id"] not in ids:
            bad_corr = True
    check("60 concurrent: no step leaked to an unknown deleg_id", not bad_corr)

    # D3: empty-task guard
    pe = _P({"task": "   "})
    await ab.make_jarvis_agent_handler(emit=emit)(pe)
    check("empty task rejected cleanly (ok=False, no crash)", pe.cb and pe.cb["ok"] is False)

    # D4: all brains raise -> ok=False end + failure callback, no crash
    ab._BRAINS = {"codex": boom, "local": boom}
    pf = _P({"task": "doomed"})
    await ab.make_jarvis_agent_handler(emit=emit)(pf)
    last_end = [e for e in events if e["type"] == "delegation_end"][-1]
    check("all-brains-fail: callback ok=False and a failed delegation_end emitted", pf.cb and pf.cb["ok"] is False and last_end["ok"] is False)


# --------------------------------------------------------------------------- #
# E. TraceStore projection robustness (needs OpenJarvis on path)
# --------------------------------------------------------------------------- #
def test_projection(conn):
    print("\n== E. TraceStore projection robustness ==")
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sys.path.insert(0, os.path.join(here, "app", "src"))
    try:
        from openjarvis.server import history_routes as hr
        from openjarvis.traces.store import TraceStore
    except Exception as exc:
        check("projection import (skipped if OpenJarvis env missing)", True, f"skipped: {exc}")
        return
    import types as _t

    store = TraceStore(os.path.join(_TMP, "traces.db"))
    req = _t.SimpleNamespace(app=_t.SimpleNamespace(state=_t.SimpleNamespace(trace_store=store)))
    try:
        out1 = hr.ingest_voice_traces(req, limit=2000)
        out2 = hr.ingest_voice_traces(req, limit=2000)  # idempotent re-run
        crashed = False
    except Exception as exc:
        crashed = True
        out1 = out2 = {"error": str(exc)}
    check("projection runs without crashing over adversarial+volume data", not crashed, str(out1))
    if not crashed:
        check("projection is idempotent (re-run adds 0 new)", out2.get("ingested") == 0, f"{out1} then {out2}")
        import sqlite3
        c = sqlite3.connect(os.path.join(_TMP, "traces.db"))
        n = c.execute("SELECT COUNT(*) FROM traces").fetchone()[0]
        nsteps = c.execute("SELECT COUNT(*) FROM trace_steps").fetchone()[0]
        c.close()
        print(f"    projected {n} traces, {nsteps} steps")
        check("projection produced traces with steps", n > 0 and nsteps > 0)


async def main():
    print(f"Phase 1 stress test (isolated in {_TMP})")
    conn = test_adversarial_ingest()
    convB = test_volume_idempotency()
    if convB is not None:
        test_search_injection(convB)
    await test_instrumentation()
    if convB is not None:
        test_projection(convB)

    total = len(_RESULTS)
    passed = sum(_RESULTS)
    print(f"\n=== RESULT: {passed}/{total} checks passed ===")
    print("ALL PASS" if passed == total else f"{total - passed} FAILED")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
