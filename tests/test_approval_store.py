# Tests for approval_store — the durable, single-fire, TTL-bounded staging store.
# Real components: a real SQLite file on disk per test (tmp_path), real threads for the
# concurrency proof. No mocks of the system under test.
import importlib
import threading
import time

import pytest


@pytest.fixture
def store(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "approvals.db"))
    import approval_store
    importlib.reload(approval_store)
    return approval_store


# ---- B1: stage / get / list_pending + read-only expiry ----------------------

def test_stage_then_get_roundtrips_args(store):
    aid = store.stage(
        "create_invoice",
        {"customer": {"name": "The Browns"},
         "line_items": [{"description": "Deep clean", "quantity": 2, "rate": 480}]},
        requester="Alex", tier="known", risk="high",
        summary="Alex -> invoice The Browns", ttl_s=14400,
    )
    row = store.get(aid)
    assert row["tool"] == "create_invoice"
    assert row["status"] == "pending"
    assert row["requester"] == "Alex"
    assert row["requester_tier"] == "known"
    assert row["risk_level"] == "high"
    assert row["args"]["line_items"][0]["rate"] == 480       # real JSON roundtrip
    assert row["seconds_left"] > 0


def test_list_pending_excludes_expired_without_writing(store):
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "hi"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=1)
    assert len(store.list_pending()) == 1
    time.sleep(1.1)
    assert store.list_pending() == []                         # computed expiry, read-only
    # the read MUST NOT have mutated the persisted row to 'expired'
    raw = store.get(aid)
    assert raw["status"] == "pending"                         # underlying row untouched
    assert raw["seconds_left"] == 0                           # but computed as expired


def test_unknown_id_returns_none(store):
    assert store.get("nope") is None


# ---- B2: atomic single-fire consume / finish / deny / settings --------------

def test_consume_is_single_fire_under_concurrency(store):
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "hi"},
                      requester="Alex", tier="known", risk="high", summary="s", ttl_s=600)
    wins = []
    barrier = threading.Barrier(8)

    def race():
        barrier.wait()                       # maximize contention on the CAS
        r = store.consume(aid)
        if r is not None:
            wins.append(r)

    ts = [threading.Thread(target=race) for _ in range(8)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert len(wins) == 1                     # exactly one winner across 8 threads
    assert store.get(aid)["status"] == "releasing"
    assert store.consume(aid) is None         # already releasing -> no second fire


def test_consume_rejects_expired(store):
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                      requester="A", tier="known", risk="high", summary="s", ttl_s=1)
    time.sleep(1.1)
    assert store.consume(aid) is None
    assert store.get(aid)["status"] == "pending"   # not consumed; just unavailable


def test_finish_and_deny_and_list_releasing(store):
    aid = store.stage("send_to_channel", {"channel": "telegram", "message": "x"},
                      requester="A", tier="known", risk="high", summary="s", ttl_s=600)
    assert store.consume(aid) is not None
    assert len(store.list_releasing()) == 1        # orphan visible until finish
    store.finish(aid, {"ok": True, "sent": True})
    row = store.get(aid)
    assert row["status"] == "consumed"
    assert row["result"]["ok"] is True
    assert store.list_releasing() == []

    bid = store.stage("send_to_channel", {"channel": "telegram", "message": "y"},
                      requester="A", tier="known", risk="high", summary="s", ttl_s=600)
    assert store.deny(bid) is True
    assert store.get(bid)["status"] == "denied"
    assert store.deny(bid) is False                # already decided -> no-op


def test_settings_roundtrip(store):
    assert store.get_setting("remote_approval_enabled") is None
    store.set_setting("remote_approval_enabled", "true")
    assert store.get_setting("remote_approval_enabled") == "true"
    store.set_setting("remote_approval_enabled", "false")    # upsert
    assert store.get_setting("remote_approval_enabled") == "false"
    assert store.get_setting("missing", "d") == "d"


def test_survives_reopen(store, tmp_path, monkeypatch):
    # durability: a staged row outlives a fresh module load (process restart analogue)
    aid = store.stage("create_invoice", {"customer": {"name": "X"}, "line_items": []},
                      requester="A", tier="known", risk="high", summary="s", ttl_s=600)
    import approval_store
    importlib.reload(approval_store)             # same EVE_APPROVAL_DB env still set
    assert approval_store.get(aid)["status"] == "pending"


# ---- migration ladder: reaches the current schema with all tables ----------
def test_migration_reaches_current_with_all_tables(store):
    conn = store._connect()
    # Current schema is v4 (talk-back Q&A columns). The ladder is v1 (approvals/settings)
    # -> v2 (skill_feed) -> v3 (agent_tasks) -> v4 (question/answer columns + token index);
    # a fresh DB must land at 4 with every table.
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 5
    for t in ("approvals", "settings", "skill_feed", "agent_tasks"):
        assert conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?", (t,)
        ).fetchone() is not None
    conn.close()


def test_migration_v1_ladder_preserves_existing_rows(store):
    # Seed a real approvals row, then simulate a v1 db: drop the newer tables + stamp
    # user_version back to 1, COMMIT, close. Reconnecting must run the full v2+v3 ladder
    # and keep the row.
    store.stage("create_invoice", {"amount": 5}, requester="alex", tier="known",
                risk="high", summary="s", ttl_s=600)
    conn = store._connect()
    conn.execute("DROP TABLE skill_feed")
    conn.execute("DROP TABLE agent_tasks")
    conn.execute("PRAGMA user_version=1")
    conn.commit()
    conn.close()
    conn2 = store._connect()
    assert conn2.execute("PRAGMA user_version").fetchone()[0] == 5
    for t in ("skill_feed", "agent_tasks"):
        assert conn2.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?", (t,)
        ).fetchone() is not None
    assert conn2.execute("SELECT COUNT(*) FROM approvals").fetchone()[0] == 1
    conn2.close()


def test_migration_upgrades_an_existing_v4_db_to_v5(tmp_path, monkeypatch):
    # The _SCHEMA_VERSION early-return trap, caught LIVE 2026-07-10: a new migration step
    # without the constant bump runs on FRESH DBs (tests) but is skipped on an existing
    # production DB (version >= constant -> return). Pin the UPGRADE path, not just fresh.
    import importlib
    import sqlite3
    db = str(tmp_path / "v4.db")
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    import approval_store
    importlib.reload(approval_store)
    # Build a real DB, then rewind it to v4 without the v5 column (as production was).
    conn = approval_store._connect()
    conn.execute("ALTER TABLE agent_tasks DROP COLUMN redirect_json")
    conn.execute("PRAGMA user_version=4")
    conn.commit()
    conn.close()
    # A fresh connect must complete the ladder: v4 -> v5.
    conn = approval_store._connect()
    cols = [r[1] for r in conn.execute("PRAGMA table_info(agent_tasks)")]
    assert "redirect_json" in cols, "v4->v5 upgrade skipped (constant not bumped?)"
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 5
    conn.close()
