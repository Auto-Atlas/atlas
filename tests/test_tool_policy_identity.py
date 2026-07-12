"""Phase 2A / Spec 5 — identity-aware authorization (tool_policy.authz_allows
driven by identity.resolve_authz). Verifies the 5 spec-5 invariants end-to-end."""

from __future__ import annotations

import identity as I
import tool_policy as TP

OWNER_DEV = I.DevicePrincipal.owner("dev-1")
UNPAIRED = I.DevicePrincipal.unpaired()
OWNER_SPK = I.SpeakerPrincipal(tier="owner", score=0.9)
UNKNOWN_SPK = I.SpeakerPrincipal.unknown()


def _allows(tool, risk, device, speaker, reauth=False):
    eff, owner_unlocked = I.resolve_authz(device, speaker, reauth_active=reauth)
    return TP.authz_allows(tool, risk, eff, owner_unlocked)


def test_inv1_paired_device_no_voice_match():
    # low/medium tools succeed on device trust; high-risk denied; owner memory not recalled
    assert _allows("get_weather", "low", OWNER_DEV, UNKNOWN_SPK) is True
    assert _allows("search_knowledge", "medium", OWNER_DEV, UNKNOWN_SPK) is True
    assert _allows("create_invoice", "high", OWNER_DEV, UNKNOWN_SPK) is False
    assert _allows("recall", "medium", OWNER_DEV, UNKNOWN_SPK) is False  # owner memory gated


def test_inv2_low_risk_on_device_trust_alone():
    assert _allows("get_news", "low", OWNER_DEV, UNKNOWN_SPK) is True


def test_inv3_owner_elevation_needs_match_or_phrase():
    # real speaker match unlocks owner-gated
    assert _allows("create_invoice", "high", OWNER_DEV, OWNER_SPK) is True
    assert _allows("recall", "medium", OWNER_DEV, OWNER_SPK) is True
    assert _allows("jarvis_agent", "low", OWNER_DEV, OWNER_SPK) is True
    # short re-auth phrase also unlocks; nothing else does
    assert _allows("create_invoice", "high", OWNER_DEV, UNKNOWN_SPK, reauth=True) is True


def test_inv4_speaker_ttl_expiry_drops_to_device_trust():
    # after a match expires (speaker -> unknown), owner-gated drops but general chat/low tools hold
    assert _allows("create_invoice", "high", OWNER_DEV, UNKNOWN_SPK) is False
    assert _allows("get_weather", "low", OWNER_DEV, UNKNOWN_SPK) is True


def test_inv5_unpaired_device_denied_everything():
    assert _allows("get_weather", "low", UNPAIRED, OWNER_SPK) is False
    assert _allows("create_invoice", "high", UNPAIRED, OWNER_SPK) is False


def test_owner_only_tools_are_owner_gated():
    for tool in TP.OWNER_ONLY:
        assert _allows(tool, "low", OWNER_DEV, UNKNOWN_SPK) is False
        assert _allows(tool, "low", OWNER_DEV, OWNER_SPK) is True


def test_back_compat_tier_allows_unchanged():
    # the legacy single-tier gate must still behave exactly as before
    assert TP.tier_allows("create_invoice", "high", "owner") is True
    assert TP.tier_allows("create_invoice", "high", "known") is False
    assert TP.tier_allows("get_weather", "low", "known") is True
    assert TP.tier_allows("get_weather", "low", "unknown") is False


def test_effective_authz_v2_integration(monkeypatch):
    # EVE_IDENTITY_V2=1 routes the live gate through the device+speaker resolver
    import speaker_state as ss

    monkeypatch.setenv("EVE_IDENTITY_V2", "1")
    monkeypatch.setattr(ss, "_clock", lambda: 1000.0)
    ss.reset()
    ss.set_device(I.DevicePrincipal.owner("phone"))
    # paired phone, no voice match -> device trust ("known"): low ok, owner-gated denied
    assert TP._effective_authz("get_weather", "low") == (True, "known")
    assert TP._effective_authz("create_invoice", "high") == (False, "known")
    assert TP._effective_authz("recall", "medium") == (False, "known")
    # real speaker match -> owner unlocked
    ss.set_current("Owner", "owner", 0.9)
    assert TP._effective_authz("create_invoice", "high") == (True, "owner")
    ss.reset()


def test_effective_authz_legacy_is_default(monkeypatch):
    # flag OFF -> byte-for-byte the legacy current_tier()/tier_allows path
    import speaker_state as ss

    monkeypatch.delenv("EVE_IDENTITY_V2", raising=False)
    monkeypatch.setattr(ss, "_clock", lambda: 1000.0)
    ss.reset()
    ss.set_current("Owner", "owner", 1.0)
    ss.grant_owner_override(43200)
    assert TP._effective_authz("create_invoice", "high") == (True, "owner")
    ss.reset()
