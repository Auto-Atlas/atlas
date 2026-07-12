# Tests for thinking_state — the manual on/off thinking toggle (Epic T).
import importlib
import os
import tempfile

import pytest


@pytest.fixture
def ts(monkeypatch):
    d = tempfile.mkdtemp()
    db = os.path.join(d, "approvals.db")
    import approval_store
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    approval_store.set_db_path(db)
    import thinking_state
    importlib.reload(thinking_state)
    return thinking_state


def test_default_is_off(ts):
    assert ts.enabled() is False


def test_set_enabled_roundtrip(ts):
    ts.set_enabled(True)
    assert ts.enabled() is True
    ts.set_enabled(False)
    assert ts.enabled() is False


def test_read_is_cached_then_refreshes(ts, monkeypatch):
    monkeypatch.setenv("EVE_THINKING_CACHE_TTL_S", "999")
    importlib.reload(ts)
    ts.set_enabled(True)          # set_enabled invalidates, so next read is True
    assert ts.enabled() is True
    # Change the underlying store directly (bypassing set_enabled's invalidate):
    import approval_store
    approval_store.set_setting("thinking_enabled", "false")
    assert ts.enabled() is True   # cached value within the (huge) TTL
    ts._invalidate()
    assert ts.enabled() is False  # refreshes after invalidate


def test_no_db_created_when_never_set(ts):
    # Reading the toggle on a fresh box must not CREATE the approvals.db (hot-path guard).
    import approval_store
    db = approval_store._db_path()
    if os.path.exists(db):
        os.remove(db)
    assert ts.enabled() is False
    assert not os.path.exists(db)
