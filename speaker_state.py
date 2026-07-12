# speaker_state.py
#
# Process-global "who is speaking right now", fail-closed. The STT hook (and the
# non-voice inject paths) WRITE it; the policy wrapper READS it (incl. the
# confirmed=true re-call, which flows through the same wrapper). Single active
# speaker at any instant (desktop = one always-on session; phone = one session at
# a time), so this is intentionally NOT keyed per-context like tool_policy's
# _staged. The TTL is a short stale-voice backstop only — actor attribution for
# non-voice turns is done explicitly by the bodies (spec 2.7).
#
import os
import time

import identity

_TIER_TTL_S = 30
_clock = time.monotonic        # patchable in tests

_name: str | None = None
_tier: str = "unknown"
_at: float = 0.0
_score: float = 0.0                # speaker-match confidence (Spec 5: was discarded)
_override_until: float = 0.0       # owner-phrase (re-auth) override active until this time
# Device trust is SEPARATE from the speaker match: it persists for the paired
# session, while the speaker match expires on the 30s TTL (Spec 5).
_device: identity.DevicePrincipal = identity.DevicePrincipal.unpaired()


def set_current(name, tier, score):
    global _name, _tier, _at, _score
    _name, _tier, _at, _score = name, tier, _clock(), score


def grant_owner_override(seconds: float) -> None:
    """Spoken owner-phrase recovery: force `owner` for a short, time-boxed window
    regardless of the voice match. Overhearable by design — keep the window short."""
    global _override_until
    _override_until = _clock() + seconds


def current_tier() -> str:
    if _clock() < _override_until:
        return "owner"             # owner-phrase override (time-boxed)
    # Single-user escape hatch: when the unsafe "treat all as owner" flag is set AND
    # boot already granted owner (only happens with NO profiles enrolled — see
    # boot_default_tier), keep owner permanently instead of letting the 30s speaker
    # TTL revert it to guest mid-conversation. Does NOT grant owner where boot didn't.
    if os.getenv("EVE_UNSAFE_TREAT_ALL_AS_OWNER") == "1" and _tier == "owner":
        return "owner"
    if _name is None:
        return "unknown"
    if _clock() - _at > _TIER_TTL_S:
        return "unknown"           # stale -> fail closed
    return _tier


def current_speaker():
    if _name is None or _clock() - _at > _TIER_TTL_S:
        return None
    return _name


def reset():
    global _name, _tier, _at, _override_until, _score, _device
    _name, _tier, _at, _override_until, _score = None, "unknown", 0.0, 0.0, 0.0
    _device = identity.DevicePrincipal.unpaired()


# --- Spec-5 (Phase 2A) device principal + identity resolution ----------------
# These are ADDITIVE: current_tier()/current_speaker() above keep their exact
# legacy behavior so the live voice path is unchanged until the v2 gate is flipped.

def set_device(device: identity.DevicePrincipal) -> None:
    """Set the device principal from the paired per-device credential (Spec 6).
    Persists for the session — NOT subject to the speaker TTL."""
    global _device
    _device = device


def current_device() -> identity.DevicePrincipal:
    return _device


def current_score() -> float:
    """Confidence of the active speaker match (0.0 if none/stale)."""
    if _name is None or _clock() - _at > _TIER_TTL_S:
        return 0.0
    return _score


def reauth_active() -> bool:
    """True while the short owner-phrase re-auth override is live (Spec 5: <=120s)."""
    return _clock() < _override_until


def _speaker_principal() -> identity.SpeakerPrincipal:
    """The speaker match within TTL, else unknown. Ignores the re-auth override
    (that's a separate dimension fed into resolve as reauth_active)."""
    if _name is None or _clock() - _at > _TIER_TTL_S:
        return identity.SpeakerPrincipal.unknown()
    return identity.SpeakerPrincipal(tier=_tier, name=_name, score=_score)


def resolve() -> tuple[str, bool]:
    """Spec-5 v2 authorization: resolve the (device, speaker) pair into
    (effective_tier, owner_unlocked). Device trust survives the speaker TTL;
    only the speaker match (and thus owner unlock) expires."""
    return identity.resolve_authz(
        _device, _speaker_principal(), reauth_active=reauth_active()
    )


def boot_default_tier(profiles_present: bool) -> str:
    """The self-closing escape hatch: treat everyone as owner ONLY while the unsafe
    flag is set AND nobody is enrolled yet. The moment a profile exists, gating
    engages no matter the flag."""
    if os.getenv("EVE_UNSAFE_TREAT_ALL_AS_OWNER") == "1" and not profiles_present:
        return "owner"
    return "unknown"
