# Follow-up source: open loops mined from the USER'S OWN archived words via deterministic
# patterns (code, never an LLM — nothing can be invented). Low urgency (brief-only),
# once a day, capped, traceable to conv_id@ts. Temp DB — no live archive touched.
import asyncio
from datetime import datetime, timedelta

import pytest

import conversation_archive
import initiative

NOW = datetime(2026, 7, 2, 9, 0, 0)


def _run(coro):
    return asyncio.run(coro)


def _ms(dt):
    return int(dt.timestamp() * 1000)


@pytest.fixture
def db(tmp_path):
    path = tmp_path / "history.db"
    conn = conversation_archive.connect(path)
    yesterday = NOW - timedelta(days=1)
    rows = [
        ("m1", "c1", 1, "user", _ms(yesterday),
         "I'll call Sam about the route packet tomorrow."),
        ("m2", "c1", 2, "assistant", _ms(yesterday), "I'll make a note of that."),
        ("m3", "c2", 1, "user", _ms(yesterday + timedelta(hours=1)),
         "Will I need to bring the trailer?"),                    # question -> skip
        ("m4", "c2", 2, "user", _ms(yesterday + timedelta(hours=2)),
         "Remind me to flip the steaks in 20 minutes."),          # reminders own this
        ("m5", "c3", 1, "user", _ms(NOW - timedelta(days=10)),
         "I need to renew the insurance."),                       # too old
        ("m6", "c3", 2, "user", _ms(NOW - timedelta(hours=1)),
         "I promised to send the quote today."),                  # today -> skip (not yet a loop)
    ]
    conn.executemany(
        "INSERT INTO messages (id, conv_id, seq, role, ts, text) VALUES (?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()
    return path


def test_extract_commitments_patterns():
    hits = initiative.extract_commitments([
        {"conv_id": "c", "ts": 1, "text": "I'll call Sam. Also the weather is nice."},
        {"conv_id": "c", "ts": 2, "text": "I need to renew the insurance"},
        {"conv_id": "c", "ts": 3, "text": "you should call Sam"},        # not a commitment
        {"conv_id": "c", "ts": 4, "text": "will I need to bring the trailer?"},  # question
        {"conv_id": "c", "ts": 5, "text": "remind me to flip the steaks"},   # reminders own it
    ])
    assert [h["text"] for h in hits] == ["I'll call Sam.", "I need to renew the insurance"]


def test_followup_source_mines_yesterdays_open_loop(db):
    st = initiative.EngineState()
    items = _run(initiative.followup_source(st, NOW, 0.0, db_path=db))
    assert [i.kind for i in items] == ["open_loop"]
    it = items[0]
    assert it.urgency == "low" and it.source == "followup"
    assert "call Sam about the route packet" in it.instruction
    assert "Wednesday" in it.instruction          # 2026-07-01 was a Wednesday
    assert it.source_ref.startswith("c1@")
    # once a day: second call same day is silent
    assert _run(initiative.followup_source(st, NOW, 0.0, db_path=db)) == []


def test_followup_source_caps_at_configured_max_newest_first(tmp_path, monkeypatch):
    path = tmp_path / "history.db"
    conn = conversation_archive.connect(path)
    base = NOW - timedelta(days=1)
    rows = [(f"m{i}", "c1", i, "user", _ms(base + timedelta(minutes=i)),
             f"I need to fix machine number {i}.") for i in range(5)]
    conn.executemany(
        "INSERT INTO messages (id, conv_id, seq, role, ts, text) VALUES (?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()
    st = initiative.EngineState()
    items = _run(initiative.followup_source(st, NOW, 0.0, db_path=path))
    assert len(items) == 3                               # default EVE_FOLLOWUP_MAX
    assert "machine number 4" in items[0].instruction    # newest first
    # configurable per the owner's everything-configurable rule
    monkeypatch.setenv("EVE_FOLLOWUP_MAX", "2")
    st2 = initiative.EngineState()
    assert len(_run(initiative.followup_source(st2, NOW, 0.0, db_path=path))) == 2


def test_missing_db_is_honest_silence(tmp_path):
    st = initiative.EngineState()
    items = _run(initiative.followup_source(
        st, NOW, 0.0, db_path=tmp_path / "nope" / "history.db"))
    assert items == []
