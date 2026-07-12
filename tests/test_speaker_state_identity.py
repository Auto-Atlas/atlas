"""Phase 2A / Spec 5 — speaker_state device principal, score, and resolve()."""

from __future__ import annotations

import identity as I
import pytest
import speaker_state as ss


@pytest.fixture
def clock(monkeypatch):
    t = {"now": 1000.0}
    monkeypatch.setattr(ss, "_clock", lambda: t["now"])
    ss.reset()
    yield t
    ss.reset()


def test_device_principal_set_and_get(clock):
    assert ss.current_device().kind == I.DEVICE_UNPAIRED
    ss.set_device(I.DevicePrincipal.owner("dev-1"))
    assert ss.current_device().kind == I.DEVICE_OWNER


def test_score_is_stored_and_expires(clock):
    ss.set_current("Owner", "owner", 0.88)
    assert ss.current_score() == pytest.approx(0.88)
    clock["now"] += ss._TIER_TTL_S + 1
    assert ss.current_score() == 0.0  # stale -> 0


def test_resolve_owner_device_owner_speaker(clock):
    ss.set_device(I.DevicePrincipal.owner("dev-1"))
    ss.set_current("Owner", "owner", 0.9)
    assert ss.resolve() == ("owner", True)


def test_resolve_owner_device_no_speaker_is_device_trust(clock):
    ss.set_device(I.DevicePrincipal.owner("dev-1"))
    # no speaker match yet
    assert ss.resolve() == ("known", False)


def test_inv4_speaker_ttl_decoupled_from_device_trust(clock):
    ss.set_device(I.DevicePrincipal.owner("dev-1"))
    ss.set_current("Owner", "owner", 0.9)
    assert ss.resolve() == ("owner", True)
    # speaker match expires; DEVICE trust must persist (still known), owner drops
    clock["now"] += ss._TIER_TTL_S + 1
    assert ss.resolve() == ("known", False)


def test_reauth_override_unlocks_owner(clock):
    ss.set_device(I.DevicePrincipal.owner("dev-1"))
    ss.grant_owner_override(120)
    assert ss.reauth_active() is True
    assert ss.resolve() == ("owner", True)
    clock["now"] += 121
    assert ss.reauth_active() is False
    assert ss.resolve() == ("known", False)


def test_reset_clears_device_and_score(clock):
    ss.set_device(I.DevicePrincipal.owner("dev-1"))
    ss.set_current("Owner", "owner", 0.9)
    ss.reset()
    assert ss.current_device().kind == I.DEVICE_UNPAIRED
    assert ss.current_score() == 0.0


def test_legacy_current_tier_unchanged(clock):
    # legacy single-tier path must behave exactly as before
    ss.set_current("Owner", "owner", 0.9)
    assert ss.current_tier() == "owner"
    assert ss.current_speaker() == "Owner"
    clock["now"] += ss._TIER_TTL_S + 1
    assert ss.current_tier() == "unknown"  # stale -> fail closed
