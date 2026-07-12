# Tests for reminders_tool cross-process lock + atomic write on the shared store.
#
# Codex audit: desktop (bot.py) and phone (phone_bot.py) are SEPARATE processes
# both doing read-modify-write on the same reminders JSON. A concurrent write
# could lose a reminder or corrupt the file. We added an fcntl LOCK_EX over a
# sidecar lock file around each RMW, and writes go through a temp file +
# os.replace. These tests pin: (1) the locked RMW reads fresh and doesn't
# lose-update, (2) writes are atomic (temp + os.replace, no half-written file),
# (3) add/cancel/fire/list still work.
import asyncio
import importlib
import os
import tempfile

import pytest


@pytest.fixture
def rt(monkeypatch):
    d = tempfile.mkdtemp()
    store = os.path.join(d, "reminders.json")
    monkeypatch.setenv("JARVIS_REMINDERS_FILE", store)
    import reminders_tool
    importlib.reload(reminders_tool)
    return reminders_tool


class _Params:
    """Minimal stand-in for pipecat's FunctionCallParams."""

    def __init__(self, arguments):
        self.arguments = arguments
        self.results = []

    async def result_callback(self, result):
        self.results.append(result)


def _run(coro):
    asyncio.run(coro)


# ---------------------------------------------------------------------------
# Atomic read-modify-write: the lock must serialize RMWs so a concurrent
# add can't be lost (last-writer-wins clobber).
# ---------------------------------------------------------------------------
def test_locked_mutate_reads_fresh_no_lost_update(rt):
    # Seed with reminder A on disk.
    rt._save([{"id": "aaa", "due": 100.0, "what": "A"}])
    # _mutate must re-read fresh under the lock, NOT operate on a stale snapshot.
    # If it clobbered, the appended B would overwrite/lose A.
    rt._mutate(lambda cur: cur + [{"id": "bbb", "due": 200.0, "what": "B"}])
    whats = {r["what"] for r in rt._load()}
    assert whats == {"A", "B"}, "lost-update: the locked RMW must preserve both records"


def test_two_adds_both_survive(rt):
    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        p1 = _Params({"what": "steaks", "minutes_from_now": 10})
        p2 = _Params({"what": "call Alex", "minutes_from_now": 20})
        await svc.handle_set(p1)
        await svc.handle_set(p2)
        svc.cancel_all()

    _run(scenario())
    whats = {r["what"] for r in rt._load()}
    assert whats == {"steaks", "call Alex"}, "both reminders must survive serialized adds"


# ---------------------------------------------------------------------------
# Atomic write: a crash between writing the temp file and the rename must
# leave the original store intact (os.replace is the only mutation point).
# ---------------------------------------------------------------------------
def test_save_is_atomic_crash_before_replace_keeps_original(rt, monkeypatch):
    rt._save([{"id": "aaa", "due": 100.0, "what": "original"}])

    boom = RuntimeError("simulated crash mid-write")

    def crash(*_a, **_k):
        raise boom

    monkeypatch.setattr(rt.os, "replace", crash)
    with pytest.raises(RuntimeError):
        rt._save([{"id": "bbb", "due": 200.0, "what": "clobbered"}])

    # Original store must be intact and readable — not half-written.
    items = rt._load()
    assert items == [{"id": "aaa", "due": 100.0, "what": "original"}]


def test_save_uses_temp_then_replace(rt, monkeypatch):
    calls = {}

    real_replace = rt.os.replace

    def spy(src, dst, *a, **k):
        calls["src"] = src
        calls["dst"] = dst
        return real_replace(src, dst, *a, **k)

    monkeypatch.setattr(rt.os, "replace", spy)
    rt._save([{"id": "x", "due": 1.0, "what": "t"}])
    assert calls["dst"] == str(rt._STORE)
    assert calls["src"] != str(rt._STORE), "must write a temp file, not the store directly"
    assert calls["src"].endswith(".tmp")


# ---------------------------------------------------------------------------
# Lock is real and released (no deadlock): two sequential RMWs both complete,
# and a sidecar lock file is used.
# ---------------------------------------------------------------------------
def test_lock_file_used_and_released(rt):
    with rt._locked(exclusive=True):
        pass
    # Re-acquiring after release must not block/deadlock.
    with rt._locked(exclusive=True):
        pass
    assert rt._LOCK.name.endswith(".lock")
    # The lock file lives beside the store.
    assert rt._LOCK.parent == rt._STORE.parent


# ---------------------------------------------------------------------------
# Regression: add / list / cancel / fire still work end to end.
# ---------------------------------------------------------------------------
def test_add_list_cancel_roundtrip(rt):
    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        await svc.handle_set(_Params({"what": "one", "minutes_from_now": 5}))
        await svc.handle_set(_Params({"what": "two", "minutes_from_now": 10}))

        lp = _Params({})
        await svc.handle_list(lp)
        listing = lp.results[-1]
        assert listing["ok"] and listing["count"] == 2
        assert [r["what"] for r in listing["reminders"]] == ["one", "two"]

        cp = _Params({"number": 1})
        await svc.handle_cancel(cp)
        assert cp.results[-1] == {"ok": True, "cancelled": "one"}
        svc.cancel_all()

    _run(scenario())
    assert [r["what"] for r in rt._load()] == ["two"]


def test_cancel_bad_number(rt):
    async def scenario():
        svc = rt.ReminderService(announce=lambda *_: None)
        await svc.handle_set(_Params({"what": "only", "minutes_from_now": 5}))
        cp = _Params({"number": 9})
        await svc.handle_cancel(cp)
        assert cp.results[-1]["ok"] is False
        svc.cancel_all()

    _run(scenario())
    assert [r["what"] for r in rt._load()] == ["only"]


def test_fire_clears_only_its_record(rt):
    spoke = []

    async def announce(text):
        spoke.append(text)

    async def scenario():
        svc = rt.ReminderService(announce=announce)
        # Due immediately so the fire timer runs right away.
        r = {"id": "fire1", "due": 0.0, "what": "ping"}
        keep = {"id": "keep1", "due": 9e12, "what": "later"}
        rt._save([r, keep])
        svc._schedule(r)
        await asyncio.sleep(0.05)  # let the fire task run + announce + clear
        svc.cancel_all()

    _run(scenario())
    assert spoke, "fire should announce"
    remaining = {x["what"] for x in rt._load()}
    assert remaining == {"later"}, "fire must clear only its own record"


def test_missed_on_start_announced_and_cleared(rt):
    spoke = []

    async def announce(text):
        spoke.append(text)

    async def scenario():
        rt._save([
            {"id": "m1", "due": 1.0, "what": "missed"},      # long past
            {"id": "p1", "due": 9e12, "what": "pending"},    # far future
        ])
        svc = rt.ReminderService(announce=announce)
        await svc.start()
        svc.cancel_all()

    _run(scenario())
    assert spoke and "missed" in spoke[0]
    remaining = {x["what"] for x in rt._load()}
    assert remaining == {"pending"}, "missed cleared after announce; pending kept"
