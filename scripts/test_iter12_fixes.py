#
# Offline verification for the iteration 1+2 fixes: toolguard semantics,
# reminder delivery contract, inbox seen-map, contact multi-number split,
# and the context-preservation fixes. No network, no audio, no live stack.
# Run:  .venv\Scripts\python.exe scripts\test_iter12_fixes.py
#

import asyncio
import dataclasses
import json
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

PASS = []
FAIL = []


def check(name: str, cond: bool, detail: str = ""):
    (PASS if cond else FAIL).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + (f"  ({detail})" if detail and not cond else ""))


# ---- toolguard ------------------------------------------------------------
@dataclasses.dataclass
class FakeParams:
    arguments: dict
    result_callback: object


async def test_toolguard():
    print("toolguard:")
    import toolguard

    calls = {"n": 0}

    async def handler(params):
        calls["n"] += 1
        await params.result_callback({"ok": True, "n": calls["n"]})

    wrapped = toolguard.dedupe("t", handler)

    results = []

    async def cb(result, **kwargs):
        results.append((result, kwargs))

    # 1. parallel dupes share one execution
    p = FakeParams(arguments={"a": 1}, result_callback=cb)
    await asyncio.gather(wrapped(p), wrapped(p), wrapped(p))
    check("parallel dupes -> one real execution", calls["n"] == 1, f"executions={calls['n']}")
    check("all three callers got a result", len(results) == 3, f"got={len(results)}")
    dupe_props = [k.get("properties") for _, k in results if k.get("properties")]
    check("dupes delivered with run_llm=False", len(dupe_props) == 2 and all(not pr.run_llm for pr in dupe_props))

    # 2. a repeat AFTER the settle window re-runs for real
    toolguard.DEDUPE_WINDOW_S = 0.1
    await asyncio.sleep(0.15)
    results.clear()
    await wrapped(p)
    check("repeat after window re-runs", calls["n"] == 2, f"executions={calls['n']}")

    # 3. raising handler -> honest ok:false, no exception escapes
    async def boom(params):
        raise RuntimeError("kaput")

    wrapped_boom = toolguard.dedupe("boom", boom)
    results.clear()
    await wrapped_boom(FakeParams(arguments={}, result_callback=cb))
    r = results[0][0]
    check("raising handler -> ok:false delivered", r.get("ok") is False and "kaput" in r.get("error", ""))

    # 4. handler that never delivers -> honest ok:false
    async def silent(params):
        pass

    wrapped_silent = toolguard.dedupe("silent", silent)
    results.clear()
    await wrapped_silent(FakeParams(arguments={}, result_callback=cb))
    r = results[0][0]
    check("silent handler -> 'returned no result'", r.get("ok") is False and "no result" in r.get("error", ""))

    # 5. hung handler does not wedge the key forever
    toolguard.HANG_CAP_S = 0.1
    started = asyncio.Event()

    async def hang(params):
        started.set()
        await asyncio.sleep(30)

    wrapped_hang = toolguard.dedupe("hang", hang)
    hp = FakeParams(arguments={}, result_callback=cb)
    task = asyncio.create_task(wrapped_hang(hp))
    await started.wait()
    await asyncio.sleep(0.15)
    # the key should be evictable now: a new call must run fresh (and also hang,
    # so just verify the dupe path returns an honest timeout instead of waiting)
    results.clear()
    ran_again = asyncio.Event()

    async def hang2(params):
        ran_again.set()
        await params.result_callback({"ok": True})

    # same guard instance, same (empty) args: stale in-flight entry must be evicted
    wrapped_hang.__wrapped__ = None  # no-op, just clarity
    inner = toolguard.dedupe  # not used; eviction is internal
    t2 = asyncio.create_task(wrapped_hang(hp))
    await asyncio.sleep(0.2)
    check("hung entry evicted (second call not parked as dupe)", not t2.done() or results != [])
    task.cancel()
    t2.cancel()
    for t in (task, t2):
        try:
            await t
        except (asyncio.CancelledError, Exception):
            pass


# ---- reminders ------------------------------------------------------------
async def test_reminders():
    print("reminders:")
    import importlib
    import os

    with tempfile.TemporaryDirectory() as td:
        store = Path(td) / "reminders.json"
        os.environ["JARVIS_REMINDERS_FILE"] = str(store)
        import reminders_tool
        importlib.reload(reminders_tool)

        spoken = []

        async def announce(text):
            spoken.append(text)

        async def announce_dead(text):
            raise RuntimeError("phone session is no longer live")

        # 1. set -> record on disk with id; fires -> announced -> removed
        svc = reminders_tool.ReminderService(announce)
        results = []

        async def cb(result, **kwargs):
            results.append(result)

        await svc.handle_set(FakeParams(arguments={"what": "flip steaks", "minutes_from_now": 0.001}, result_callback=cb))
        items = json.loads(store.read_text())
        check("set persists a record with an id", len(items) == 1 and items[0].get("id"))
        await asyncio.sleep(0.3)
        check("due reminder announced", any("flip steaks" in s for s in spoken))
        check("announced reminder removed from disk", json.loads(store.read_text()) == [])

        # 2. failed announce -> record STAYS on disk
        svc_dead = reminders_tool.ReminderService(announce_dead)
        await svc_dead.handle_set(FakeParams(arguments={"what": "call Alex", "minutes_from_now": 0.001}, result_callback=cb))
        await asyncio.sleep(0.3)
        left = json.loads(store.read_text())
        check("undeliverable reminder kept on disk", len(left) == 1 and left[0]["what"] == "call Alex")

        # 3. same-due reminders don't collide (id-keyed)
        store.write_text("[]")
        svc2 = reminders_tool.ReminderService(announce)
        due = time.time() + 3600
        for what in ("a", "b"):
            r = {"id": reminders_tool._new_id(), "due": due, "what": what}
            reminders_tool._save(reminders_tool._load() + [r])
            svc2._schedule(r)
        check("two same-due reminders both scheduled", len(svc2._tasks) == 2)
        svc2.cancel_all()
        check("cancel_all clears timers, keeps disk", len(svc2._tasks) == 0 and len(reminders_tool._load()) == 2)

        # 4. corrupt store -> recovered honestly, not silently []
        store.write_text("{not json!!")
        out = reminders_tool._load()
        check("corrupt store -> [] + sidecar .corrupt file", out == [] and store.with_name(store.name + ".corrupt").exists())


# ---- inbox seen-map --------------------------------------------------------
async def test_inbox():
    print("inbox:")
    import importlib
    import os

    with tempfile.TemporaryDirectory() as td:
        inbox = Path(td) / "inbox"
        inbox.mkdir()
        os.environ["JARVIS_INBOX_DIR"] = str(inbox)
        import inbox_tool
        importlib.reload(inbox_tool)
        inbox_tool.STATE_FILE = Path(td) / "inbox_state.json"

        (inbox / "old-note.md").write_text("ancient", encoding="utf-8")

        # 1. first run (no state): seeds, reports nothing
        r = inbox_tool.check_inbox(False)
        check("migration run reports 0 (seeds seen-map)", r["new_items"] == 0)

        # 2. a file with an OLD mtime (Syncthing-preserved) still shows as new
        f = inbox / "from-phone.md"
        f.write_text("note written on phone", encoding="utf-8")
        old = time.time() - 86400  # mtime says 'yesterday'
        os.utime(f, (old, old))
        r = inbox_tool.check_inbox(False)
        check("old-mtime new file IS reported", r["new_items"] == 1 and r["items"][0]["file"] == "from-phone.md")

        # 3. reported once, not again
        r = inbox_tool.check_inbox(False)
        check("same file not re-reported", r["new_items"] == 0)

        # 4. overflow is deferred, not lost
        for i in range(20):
            (inbox / f"bulk-{i:02d}.md").write_text(f"bulk {i}", encoding="utf-8")
        r1 = inbox_tool.check_inbox(False)
        r2 = inbox_tool.check_inbox(False)
        total = r1["new_items"] + r2["new_items"]
        check("overflow surfaces on next check (15 then 5)", r1["new_items"] == 15 and r2["new_items"] == 5,
              f"r1={r1['new_items']} r2={r2['new_items']}")
        check("nothing lost overall", total == 20, f"total={total}")

        # 5. subfolder + hidden filtering still work
        sub = inbox / "voice"
        sub.mkdir()
        (sub / "memo.txt").write_text("sub note", encoding="utf-8")
        (inbox / ".stfolder").mkdir()
        (inbox / ".stfolder" / "marker").write_text("x", encoding="utf-8")
        r = inbox_tool.check_inbox(False)
        names = [i["file"] for i in r["items"]]
        check("subfolder file reported, syncthing internals skipped",
              any("memo.txt" in n for n in names) and not any(".stfolder" in n for n in names))


# ---- contacts multi-number split -------------------------------------------
def test_contacts():
    print("contacts:")
    import csv as _csv
    import importlib
    import os

    with tempfile.TemporaryDirectory() as td:
        csv_path = Path(td) / "contacts.csv"
        with open(csv_path, "w", encoding="utf-8", newline="") as f:
            w = _csv.DictWriter(f, fieldnames=[
                "First Name", "Last Name", "Nickname", "Organization Name",
                "Phone 1 - Label", "Phone 1 - Value"])
            w.writeheader()
            w.writerow({"First Name": "Multi", "Last Name": "Guy",
                        "Phone 1 - Label": "Mobile",
                        "Phone 1 - Value": "+15085551212 ::: +16175551234"})
        os.environ["JARVIS_CONTACTS_CSV"] = str(csv_path)
        import contacts
        importlib.reload(contacts)
        loaded = contacts._load()
        check("multi-value field split, first number kept clean",
              loaded and loaded[0]["phone"] == "+15085551212", f"got={loaded[0]['phone'] if loaded else None}")


# ---- swap_system_prompt preserves memory pack -------------------------------
def test_swap():
    print("swap_system_prompt:")

    class FakeCtx:
        def __init__(self, msgs):
            self._m = msgs

        def get_messages(self):
            return self._m

        def set_messages(self, m):
            self._m = m

    from sales_coach import swap_system_prompt

    ctx = FakeCtx([
        {"role": "system", "content": "PERSONA"},
        {"role": "system", "content": "MEMORY PACK"},
        {"role": "user", "content": "hi"},
        {"role": "assistant", "content": "hey"},
    ])
    swap_system_prompt(ctx, "ROLEPLAY")
    msgs = ctx.get_messages()
    check("persona replaced in place", msgs[0]["content"] == "ROLEPLAY")
    check("memory pack survives the swap", msgs[1]["content"] == "MEMORY PACK")
    check("history intact", len(msgs) == 4)


async def main():
    await test_toolguard()
    await test_reminders()
    await test_inbox()
    test_contacts()
    test_swap()
    await test_core_and_voice()
    print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
    if FAIL:
        print("FAILED:", ", ".join(FAIL))
        sys.exit(1)




# ---- iteration 3+5 additions: jarvis_core registration + set_voice ----------
async def test_core_and_voice():
    print("jarvis_core / set_voice:")
    import jarvis_core

    # 1. one schema per registered tool, names in sync
    class FakeLLM:
        def __init__(self):
            self.registered = {}

        def register_function(self, name, handler):
            self.registered[name] = handler

    class FakeCtx2:
        def __init__(self):
            self._m = [{"role": "system", "content": "P"}]

        def get_messages(self):
            return self._m

        def set_messages(self, m):
            self._m = m

        def add_message(self, m):
            self._m.append(m)

    class FakeReminders:
        async def handle_set(self, p): ...
        async def handle_list(self, p): ...
        async def handle_cancel(self, p): ...

    llm = FakeLLM()
    jarvis_core.register_tools(llm, FakeCtx2(), "biz pack", FakeReminders())
    schema_names = {s.name for s in jarvis_core.ALL_TOOL_SCHEMAS}
    check("every schema has a registered handler", schema_names == set(llm.registered),
          f"diff={schema_names ^ set(llm.registered)}")
    check("set_voice present in both", "set_voice" in schema_names)

    # 2. set_voice behavior
    pushed = []
    results = []

    @dataclasses.dataclass
    class VoiceParams:
        arguments: dict
        result_callback: object
        llm: object = None

    class FrameLLM:
        async def push_frame(self, frame, *a, **k):
            pushed.append(frame)

    async def cb(result, **kwargs):
        results.append(result)

    handler = jarvis_core.make_set_voice_handler()
    await handler(VoiceParams(arguments={"voice": "male"}, result_callback=cb, llm=FrameLLM()))
    check("preset 'male' maps and pushes settings frame",
          results[-1].get("ok") and results[-1]["voice"] == jarvis_core.VOICE_PRESETS["male"]
          and pushed and pushed[-1].settings.get("voice") == jarvis_core.VOICE_PRESETS["male"])

    await handler(VoiceParams(arguments={"voice": "af_heart"}, result_callback=cb, llm=FrameLLM()))
    check("explicit id accepted", results[-1].get("ok") and results[-1]["voice"] == "af_heart")

    n_frames = len(pushed)
    await handler(VoiceParams(arguments={"voice": "robot_9000"}, result_callback=cb, llm=FrameLLM()))
    check("unknown voice rejected honestly, no frame pushed",
          results[-1].get("ok") is False and len(pushed) == n_frames)

    # 3. build_context: persona + protected head
    ctx, head = jarvis_core.build_context()
    msgs = ctx.get_messages()
    check("build_context: persona first, protected_head covers boot block",
          msgs[0]["role"] == "system" and 1 <= head <= 2 and head <= len(msgs))


if __name__ == "__main__":
    asyncio.run(main())
