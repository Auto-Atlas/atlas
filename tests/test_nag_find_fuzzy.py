# Pins the MODEL-DRIVEN completion loop for open nags. Live miss 2026-07-05: the
# voice model called complete_reminder(what="Test reminder from your alarm clock")
# for an item stored as "Test reminder — this is the follow-up from the alarm clock
# test." — no substring either way — and then never retried, so the nag kept
# resurfacing after the owner said it was done.
#
# Design (the owner 2026-07-05): NO server-side fuzzy matching / stopword lists. The
# model heard the conversation, so the model is the fuzzy matcher: a miss returns
# the full open list with ids and an instruction to IMMEDIATELY re-call with the
# right id. These tests pin (1) find() stays a pure id/substring lookup, (2) the
# miss payload carries everything the model needs to finish the job, (3) the id
# retry actually closes the item.
import asyncio
import importlib
import os
import tempfile
import time

import pytest


@pytest.fixture
def env(monkeypatch):
    d = tempfile.mkdtemp()
    monkeypatch.setenv("EVE_NAG_FILE", os.path.join(d, "nags.json"))
    import nag_store
    import nag_tool
    importlib.reload(nag_store)
    return nag_store, nag_tool


class _Params:
    def __init__(self, arguments):
        self.arguments = arguments
        self.results = []

    async def result_callback(self, result):
        self.results.append(result)


LIVE_ITEM = "Test reminder — this is the follow-up from the alarm clock test."
LIVE_PARAPHRASE = "Test reminder from your alarm clock"


def _add(ns, what):
    return ns.add(what, source="reminder", ref="r1", due=time.time(),
                  expire_at=time.time() + 3600)


def test_find_is_deliberately_not_fuzzy(env):
    ns, _ = env
    _add(ns, LIVE_ITEM)
    # The paraphrase misses on purpose — matching it is the MODEL's job via the
    # open-list retry, not a server-side heuristic.
    assert ns.find(LIVE_PARAPHRASE) == []
    # Substring still works both directions.
    assert len(ns.find("alarm clock test")) == 1


def test_miss_payload_gives_model_everything_to_retry(env):
    ns, nt = env
    rec = _add(ns, LIVE_ITEM)
    p = _Params({"what": LIVE_PARAPHRASE})
    asyncio.run(nt.handle_complete_reminder(p))
    res = p.results[0]
    assert res["ok"] is False
    assert res["open"] == [{"what": LIVE_ITEM, "id": rec["id"]}]
    # The instruction must demand the immediate id retry — that's the whole loop.
    assert "IMMEDIATELY" in res["instruction"]
    assert "id" in res["instruction"]


def test_id_retry_closes_the_item(env):
    ns, nt = env
    rec = _add(ns, LIVE_ITEM)
    # Step 2 of the contract: the model re-calls with the exact id as `what`.
    p = _Params({"what": rec["id"]})
    asyncio.run(nt.handle_complete_reminder(p))
    assert p.results[0]["ok"] is True
    assert p.results[0]["completed"] == LIVE_ITEM
    assert ns.pending() == []


def test_schema_teaches_the_two_step_contract(env):
    _, nt = env
    desc = nt.COMPLETE_REMINDER_SCHEMA.description
    assert "TWO-STEP" in desc and "id" in desc
