# Tests for silence_mode — EVE's "quiet unless you say the wake word" toggle.
# The settings module (mirrors thinking_state): cached reads on the shared approval_store
# settings table, honest defaults, and FAIL-OPEN on a store error (she keeps talking).
import importlib
import os
import tempfile

import pytest


@pytest.fixture
def sm(monkeypatch):
    d = tempfile.mkdtemp()
    db = os.path.join(d, "approvals.db")
    import approval_store
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    approval_store.set_db_path(db)
    import silence_mode
    importlib.reload(silence_mode)
    # Pin the assistant's name: the default wake phrase DERIVES from it (never a literal),
    # so the "eve"-utterance tests below stay meaningful on any machine/env.
    import persona
    monkeypatch.setattr(persona, "ASSISTANT_NAME", "Eve")
    return silence_mode


# ---- honest defaults --------------------------------------------------------

def test_default_disabled(sm):
    assert sm.enabled() is False


def test_default_wake_phrases_is_the_assistants_configured_name(sm, monkeypatch):
    # Derived from JARVIS_ASSISTANT_NAME, never a literal — a "Jarvis" install must not
    # wake only to "eve" (nothing owner- or deployment-specific baked in).
    import persona
    assert sm.wake_phrases() == ["eve"]          # fixture pins ASSISTANT_NAME="Eve"
    monkeypatch.setattr(persona, "ASSISTANT_NAME", "Jarvis")
    sm._invalidate()
    assert sm.wake_phrases() == ["jarvis"]


def test_default_window_is_15s(sm):
    assert sm.window_s() == 15.0


def test_no_db_created_when_never_set(sm):
    # Reading the toggle on a fresh box must not CREATE the approvals.db (hot-path guard).
    import approval_store
    db = approval_store._db_path()
    if os.path.exists(db):
        os.remove(db)
    assert sm.enabled() is False
    assert not os.path.exists(db)


# ---- round-trip -------------------------------------------------------------

def test_set_enabled_roundtrip(sm):
    sm.set_enabled(True)
    assert sm.enabled() is True
    sm.set_enabled(False)
    assert sm.enabled() is False


def test_custom_wake_phrases_roundtrip(sm):
    import approval_store
    approval_store.set_setting("wake_phrases", '["hey eve", "computer"]')
    sm._invalidate()
    assert sm.wake_phrases() == ["hey eve", "computer"]


def test_custom_window_roundtrip(sm):
    import approval_store
    approval_store.set_setting("silence_wake_window_s", "30")
    sm._invalidate()
    assert sm.window_s() == 30.0


# ---- wake matching: case-insensitive, punctuation-tolerant, contained -------

def test_wake_requires_owner_tier(sm):
    sm.set_enabled(True)
    assert sm.is_wake("eve are you there", "owner") is True
    assert sm.is_wake("eve are you there", "known") is False
    assert sm.is_wake("eve are you there", "kid") is False
    assert sm.is_wake("eve are you there", "unknown") is False


def test_wake_case_insensitive_and_punctuation_tolerant(sm):
    sm.set_enabled(True)
    assert sm.is_wake("EVE, help me out here!", "owner") is True
    assert sm.is_wake("...Eve?", "owner") is True


def test_wake_phrase_contained_in_utterance(sm):
    sm.set_enabled(True)
    assert sm.is_wake("okay so eve what's the weather", "owner") is True


def test_non_wake_utterance_is_not_wake(sm):
    sm.set_enabled(True)
    assert sm.is_wake("tell everyone the meeting moved", "owner") is False
    # substring of a bigger word must NOT match (word-boundary via normalize+split)
    assert sm.is_wake("the evening plans changed", "owner") is False


def test_multi_word_wake_phrase(sm):
    import approval_store
    approval_store.set_setting("wake_phrases", '["hey eve"]')
    sm._invalidate()
    sm.set_enabled(True)
    assert sm.is_wake("hey eve, what's up", "owner") is True
    assert sm.is_wake("eve what's up", "owner") is False  # only "hey eve" configured


# ---- caching + live refresh -------------------------------------------------

def test_read_is_cached_then_refreshes(sm, monkeypatch):
    monkeypatch.setenv("EVE_SILENCE_CACHE_TTL_S", "999")
    importlib.reload(sm)
    sm.set_enabled(True)              # set_enabled invalidates -> next read True
    assert sm.enabled() is True
    import approval_store
    approval_store.set_setting("silence_mode_enabled", "false")
    assert sm.enabled() is True       # cached within the (huge) TTL
    sm._invalidate()
    assert sm.enabled() is False      # refreshes after invalidate


# ---- FAIL-OPEN on a store error (honesty spine: never brick her voice) -------

def test_fail_open_on_store_error(sm, monkeypatch, caplog):
    sm.set_enabled(True)
    assert sm.enabled() is True
    sm._invalidate()

    def boom(*a, **k):
        raise RuntimeError("disk gone")

    import approval_store
    monkeypatch.setattr(approval_store, "get_setting", boom)
    # Store unreadable -> silence treated OFF (she keeps talking), loudly.
    assert sm.enabled() is False


def test_bad_wake_phrases_json_falls_back_loudly(sm, monkeypatch):
    import approval_store
    approval_store.set_setting("wake_phrases", "not-json[[[")
    sm._invalidate()
    assert sm.wake_phrases() == ["eve"]   # honest default, not a crash
