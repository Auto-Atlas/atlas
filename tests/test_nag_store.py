# Ack-loop store: open items resurface on an interval until the owner confirms complete
# (complete), snoozes, they run out of repeats (exhausted — one last call), or they expire
# (moot — silent). Idempotent on (source, ref); restart-proof (plain JSON on disk).
import nag_store

T0 = 1_760_000_000.0


def _add(**kw):
    args = dict(what="flip the steaks", source="reminder", ref="r1",
                due=T0, expire_at=T0 + 3600, now=T0, interval=600)
    args.update(kw)
    return nag_store.add(**args)


def test_add_is_idempotent_on_source_ref():
    assert _add() is not None
    assert _add() is None                       # same (source, ref): no second loop
    assert _add(ref="r2") is not None           # different ref: fine
    assert len(nag_store.pending(T0)) == 2


def test_claim_due_bumps_and_persists():
    _add()
    due, exhausted, expired = nag_store.claim_due(T0 + 601)
    assert [x["what"] for x in due] == ["flip the steaks"]
    assert due[0]["repeats"] == 1 and (exhausted, expired) == ([], [])
    # Bump persisted: not due again until the interval passes.
    assert nag_store.claim_due(T0 + 602)[0] == []
    assert nag_store.claim_due(T0 + 1202)[0][0]["repeats"] == 2


def test_not_due_yet_stays_quiet():
    _add()
    assert nag_store.claim_due(T0 + 1) == ([], [], [])


def test_exhausted_after_max_repeats_removed_with_last_call():
    _add(repeats_max=2)
    t = T0
    for expected in (1, 2):
        t += 601
        due, _, _ = nag_store.claim_due(t)
        assert due[0]["repeats"] == expected
    t += 601
    due, exhausted, _ = nag_store.claim_due(t)
    assert due == [] and [x["ref"] for x in exhausted] == ["r1"]
    assert nag_store.pending(t) == []            # gone — no infinite nagging


def test_expired_removed_silently():
    _add(expire_at=T0 + 100)
    due, exhausted, expired = nag_store.claim_due(T0 + 601)
    assert due == [] and exhausted == [] and [x["ref"] for x in expired] == ["r1"]
    assert nag_store.pending(T0 + 601) == []


def test_complete_closes_the_loop():
    rec = _add()
    assert nag_store.complete(rec["id"])["what"] == "flip the steaks"
    assert nag_store.claim_due(T0 + 601)[0] == []
    assert nag_store.complete(rec["id"]) is None     # already closed


def test_snooze_defers_without_consuming_a_repeat():
    rec = _add()
    nag_store.snooze(rec["id"], 30, now=T0 + 601)    # "ask me again in 30"
    assert nag_store.claim_due(T0 + 700)[0] == []
    due, _, _ = nag_store.claim_due(T0 + 601 + 31 * 60)
    assert due[0]["repeats"] == 1                    # first real resurface, not second


def test_find_matches_either_direction_case_insensitive():
    rec = _add(what="Dentist at 4:00 PM")
    assert nag_store.find("dentist")[0]["id"] == rec["id"]          # words ⊂ what
    assert nag_store.find("the dentist at 4:00 pm thing") != []     # what ⊂ words
    assert nag_store.find(rec["id"])[0]["id"] == rec["id"]          # exact id
    assert nag_store.find("") == [] and nag_store.find("steaks") == []


def test_corrupt_store_preserved_not_erased(tmp_path, monkeypatch):
    p = tmp_path / "nags.json"
    monkeypatch.setenv("EVE_NAG_FILE", str(p))
    p.write_text("{not json")
    assert nag_store.pending(T0) == []
    assert p.with_name("nags.json.corrupt").exists()   # evidence kept aside
