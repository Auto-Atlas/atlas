import speaker_state


def setup_function():
    speaker_state.reset()


def test_default_is_unknown():
    assert speaker_state.current_tier() == "unknown"
    assert speaker_state.current_speaker() is None


def test_set_and_get():
    speaker_state.set_current("Owner", "owner", 0.9)
    assert speaker_state.current_tier() == "owner"
    assert speaker_state.current_speaker() == "Owner"


def test_reset_returns_to_unknown():
    speaker_state.set_current("Alex", "known", 0.8)
    speaker_state.reset()
    assert speaker_state.current_tier() == "unknown"


def test_owner_override_forces_owner_then_lapses(monkeypatch):
    t = {"now": 500.0}
    monkeypatch.setattr(speaker_state, "_clock", lambda: t["now"])
    speaker_state.set_current(None, "unknown", 0.2)     # voice says unknown
    assert speaker_state.current_tier() == "unknown"
    speaker_state.grant_owner_override(120)
    assert speaker_state.current_tier() == "owner"      # phrase overrides
    t["now"] += 121
    assert speaker_state.current_tier() == "unknown"    # time-boxed -> lapses


def test_stale_tier_reads_unknown(monkeypatch):
    t = {"now": 1000.0}
    monkeypatch.setattr(speaker_state, "_clock", lambda: t["now"])
    speaker_state.set_current("Owner", "owner", 0.9)
    assert speaker_state.current_tier() == "owner"
    t["now"] += speaker_state._TIER_TTL_S + 1
    assert speaker_state.current_tier() == "unknown"   # fail-closed on staleness
