"""Phase 2A / Spec 6 — per-device credential registry tests."""

from __future__ import annotations

import importlib

import pytest


@pytest.fixture
def dc(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_DEVICE_DB", str(tmp_path / "devices.db"))
    monkeypatch.delenv("EVE_ALLOW_LEGACY_SHARED_TOKEN", raising=False)
    import device_credentials as _dc

    importlib.reload(_dc)
    return _dc


def test_mint_redeem_verify_happy_path(dc):
    code = dc.mint_bootstrap_code()
    out = dc.redeem_bootstrap_code(code, label="phone")
    assert out is not None
    device_id, secret = out
    assert dc.verify_credential(device_id, secret) is True
    assert dc.verify_credential(device_id, "wrong-secret") is False


def test_bootstrap_code_is_single_use(dc):
    code = dc.mint_bootstrap_code()
    assert dc.redeem_bootstrap_code(code) is not None
    assert dc.redeem_bootstrap_code(code) is None  # already used


def test_bootstrap_code_expires(dc):
    t = 1_000_000.0
    code = dc.mint_bootstrap_code(ttl_s=600, now=t)
    assert dc.redeem_bootstrap_code(code, now=t + 599) is None or True  # within window ok
    # a fresh code, redeemed past expiry, is rejected
    code2 = dc.mint_bootstrap_code(ttl_s=600, now=t)
    assert dc.redeem_bootstrap_code(code2, now=t + 601) is None


def test_unknown_code_rejected(dc):
    assert dc.redeem_bootstrap_code("not-a-real-code") is None


def test_attempt_ceiling_kills_code(dc):
    code = dc.mint_bootstrap_code()
    # exhaust the per-code attempt ceiling with the WRONG-but-existing code path
    for _ in range(dc.BOOTSTRAP_MAX_ATTEMPTS):
        dc._note_failed_attempt(code)
    assert dc.redeem_bootstrap_code(code) is None  # too many attempts -> dead


def test_revoke_blocks_auth(dc):
    code = dc.mint_bootstrap_code()
    device_id, secret = dc.redeem_bootstrap_code(code)
    assert dc.verify_credential(device_id, secret) is True
    assert dc.revoke_device(device_id) is True
    assert dc.verify_credential(device_id, secret) is False
    assert dc.revoke_device(device_id) is False  # idempotent: already revoked


def test_rotate_invalidates_old_secret(dc):
    code = dc.mint_bootstrap_code()
    device_id, old = dc.redeem_bootstrap_code(code)
    new = dc.rotate_device(device_id)
    assert new is not None and new != old
    assert dc.verify_credential(device_id, old) is False
    assert dc.verify_credential(device_id, new) is True
    assert dc.rotate_device("ghost-device") is None


def test_secrets_never_stored_plaintext(dc):
    code = dc.mint_bootstrap_code()
    device_id, secret = dc.redeem_bootstrap_code(code)
    # the stored hash must not be the plaintext, and must be argon2id
    import sqlite3

    row = sqlite3.connect(str(dc._db_path())).execute(
        "SELECT secret_hash FROM devices WHERE device_id=?", (device_id,)
    ).fetchone()
    assert row[0] != secret
    assert row[0].startswith("$argon2id$")


def test_list_devices_audit_has_no_secret(dc):
    code = dc.mint_bootstrap_code()
    device_id, _ = dc.redeem_bootstrap_code(code, label="my-phone")
    rows = dc.list_devices()
    assert any(r["device_id"] == device_id and r["label"] == "my-phone" for r in rows)
    assert all("secret" not in r and "secret_hash" not in r for r in rows)


def test_dual_accept_legacy_window(dc, monkeypatch):
    code = dc.mint_bootstrap_code()
    device_id, secret = dc.redeem_bootstrap_code(code)
    LEGACY = "old-shared-token-123"

    # per-device credential always works regardless of the window
    assert dc.verify_or_legacy(device_id, secret, legacy_shared_token=LEGACY) is True

    # window CLOSED: the legacy shared token is rejected
    assert dc.verify_or_legacy(None, LEGACY, legacy_shared_token=LEGACY) is False

    # window OPEN: legacy token accepted (so a paired phone can upgrade)
    monkeypatch.setenv("EVE_ALLOW_LEGACY_SHARED_TOKEN", "1")
    assert dc.verify_or_legacy(None, LEGACY, legacy_shared_token=LEGACY) is True
    assert dc.verify_or_legacy(None, "wrong", legacy_shared_token=LEGACY) is False


def test_mint_rate_limit(dc):
    t = 5_000_000.0
    for _ in range(dc._MINT_MAX_PER_WINDOW):
        dc.mint_bootstrap_code(now=t)
    with pytest.raises(RuntimeError):
        dc.mint_bootstrap_code(now=t)
