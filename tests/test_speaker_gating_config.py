import speaker_state


def test_hatch_inert_when_profiles_present(monkeypatch):
    monkeypatch.setenv("EVE_UNSAFE_TREAT_ALL_AS_OWNER", "1")
    assert speaker_state.boot_default_tier(profiles_present=True) == "unknown"


def test_hatch_active_only_when_no_profiles(monkeypatch):
    monkeypatch.setenv("EVE_UNSAFE_TREAT_ALL_AS_OWNER", "1")
    assert speaker_state.boot_default_tier(profiles_present=False) == "owner"


def test_default_is_unknown_without_hatch(monkeypatch):
    monkeypatch.delenv("EVE_UNSAFE_TREAT_ALL_AS_OWNER", raising=False)
    assert speaker_state.boot_default_tier(profiles_present=False) == "unknown"
