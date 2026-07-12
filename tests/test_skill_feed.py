# Tests for skill_feed — the api<->bot firewall queue for "feed EVE a skill".
import importlib
import threading
import time

import pytest


@pytest.fixture
def feed(tmp_path, monkeypatch):
    db = tmp_path / "approvals.db"
    monkeypatch.setenv("EVE_APPROVAL_DB", str(db))
    import approval_store
    importlib.reload(approval_store)
    import skill_feed
    importlib.reload(skill_feed)
    return skill_feed


# ---- store core -------------------------------------------------------------
def test_enqueue_then_list_pending(feed):
    fid = feed.enqueue("create_invoice", "next", "BODY-TEXT", ttl_s=86400)
    assert isinstance(fid, str) and fid
    pending = feed.list_pending()
    assert len(pending) == 1
    row = pending[0]
    assert row["tool"] == "create_invoice"
    assert row["mode"] == "next"
    assert row["body_snapshot"] == "BODY-TEXT"
    assert row["effective_status"] == "pending"


def test_list_pending_excludes_expired_without_writing(feed):
    feed.enqueue("get_weather", "live", "B", ttl_s=1)
    time.sleep(1.1)
    assert feed.list_pending() == []


def test_clear_pending_by_tool(feed):
    feed.enqueue("create_invoice", "next", "B", ttl_s=86400)
    feed.enqueue("get_weather", "next", "B", ttl_s=86400)
    assert feed.clear_pending("create_invoice") == 1
    remaining = [p["tool"] for p in feed.list_pending()]
    assert remaining == ["get_weather"]


# ---- token-CAS claims -------------------------------------------------------
def test_claim_next_single_winner_under_concurrency(feed):
    feed.enqueue("create_invoice", "next", "B", ttl_s=86400)
    barrier = threading.Barrier(8)
    results = []

    def worker():
        barrier.wait()
        results.append(feed.claim_next())

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    claimed = [r for batch in results for r in batch]
    assert len(claimed) == 1
    assert claimed[0]["tool"] == "create_invoice"


def test_claim_live_isolates_mode_and_uses_delivering(feed):
    feed.enqueue("create_invoice", "next", "N", ttl_s=86400)
    feed.enqueue("get_weather", "live", "L", ttl_s=300)
    live = feed.claim_live()
    assert [r["tool"] for r in live] == ["get_weather"]
    pend = {p["tool"]: p["status"] for p in feed.list_pending()}
    assert pend["create_invoice"] == "pending"
    assert pend["get_weather"] == "delivering"


def test_mark_delivered_finishes_a_live_feed(feed):
    feed.enqueue("get_weather", "live", "L", ttl_s=300)
    live = feed.claim_live()
    feed.mark_delivered([live[0]["id"]])
    assert feed.list_pending() == []


# ---- pure framing -----------------------------------------------------------
def _frame(feeds):
    import skill_feed
    return skill_feed.skill_feed_messages(feeds)


def test_skill_feed_messages_frames_each_body():
    msgs = _frame([{"id": "1", "body_snapshot": "AAA"}, {"id": "2", "body_snapshot": "BBB"}])
    assert len(msgs) == 2
    assert all(m["role"] == "system" for m in msgs)
    assert "AAA" in msgs[0]["content"]
    assert msgs[0]["content"].startswith("The operator just loaded")


def test_skill_feed_messages_empty():
    assert _frame([]) == []


def test_pending_live_messages_claims_and_frames(feed):
    feed.enqueue("get_weather", "live", "WX", ttl_s=300)
    msgs, ids = feed.pending_live_messages()
    assert len(msgs) == 1 and "WX" in msgs[0]["content"]
    assert len(ids) == 1
    msgs2, ids2 = feed.pending_live_messages()
    assert msgs2 == [] and ids2 == []
