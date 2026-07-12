"""Identity resolver — Phase 2A (frozen Spec 5).

Splits the old single always-owner global into two independent principals and
resolves them into an effective authorization. PURE: no imports of
``speaker_state`` or ``tool_policy`` so BOTH can import this without a circular
dependency (Spec 5, Q4 — resolved: new ``identity.py``).

Two principals, resolved per turn:
  device_principal   := from the paired per-device credential (Spec 6):
                          owner-device | known-device:<id> | unpaired/unknown
  speaker_principal  := from voice speaker-ID within TTL:
                          owner | known:<name> | kid:<name> | unknown
                        (``unknown`` is the default until a match lands)

Effective authorization is the PAIR, not a single tier:
  resolve_authz(device, speaker, reauth_active) -> (effective_tier, owner_unlocked)

  owner_unlocked = (speaker is owner) OR (a short re-auth phrase override is active)
  effective_tier:
    owner-device  + owner speaker          -> owner   (owner_unlocked=True)
    owner-device  + unknown/known speaker  -> known   (owner_unlocked=False)  # device-trusted
    known-device                           -> known | kid  (per device grant)
    unpaired/unknown device                -> unknown (deny all tools)

Phase 2A issues ONLY owner-device (Spec 5 Q2 / REVIEW): known-device and the
``kid`` grant are modeled in the schema but not minted yet.
"""

from __future__ import annotations

from dataclasses import dataclass

# --- device principal kinds ------------------------------------------------
DEVICE_OWNER = "owner-device"
DEVICE_KNOWN = "known-device"   # modeled now; not issued until a later phase
DEVICE_UNPAIRED = "unpaired"    # also the value for an unknown/forged credential

# --- speaker tiers (match the existing tool_policy/​speaker_state vocab) -----
SPEAKER_OWNER = "owner"
SPEAKER_KNOWN = "known"
SPEAKER_KID = "kid"
SPEAKER_UNKNOWN = "unknown"

_VALID_DEVICE_GRANTS = (SPEAKER_KNOWN, SPEAKER_KID)  # a known-device may be granted known or kid


@dataclass(frozen=True)
class DevicePrincipal:
    """Who the *device* is, from the paired per-device credential (Spec 6)."""

    kind: str = DEVICE_UNPAIRED          # owner-device | known-device | unpaired
    device_id: str | None = None
    grant: str = SPEAKER_KNOWN           # only meaningful for known-device

    @classmethod
    def unpaired(cls) -> "DevicePrincipal":
        return cls(kind=DEVICE_UNPAIRED)

    @classmethod
    def owner(cls, device_id: str | None = None) -> "DevicePrincipal":
        return cls(kind=DEVICE_OWNER, device_id=device_id)


@dataclass(frozen=True)
class SpeakerPrincipal:
    """Who is *speaking*, from voice speaker-ID within TTL. Defaults to unknown."""

    tier: str = SPEAKER_UNKNOWN          # owner | known | kid | unknown
    name: str | None = None
    score: float = 0.0                   # confidence; previously discarded (Spec 5)

    @classmethod
    def unknown(cls) -> "SpeakerPrincipal":
        return cls(tier=SPEAKER_UNKNOWN)


def resolve_authz(
    device: DevicePrincipal,
    speaker: SpeakerPrincipal,
    *,
    reauth_active: bool = False,
) -> tuple[str, bool]:
    """Resolve the (device, speaker) pair into (effective_tier, owner_unlocked).

    ``reauth_active`` is the short (<=120s) ``EVE_OWNER_PHRASE`` override — passed
    in (not imported) so this stays dependency-free. It NEVER comes from the old
    session-long 12h blanket; that path is removed in Phase 2A.

    owner_unlocked gates owner-private capabilities (high-risk, OWNER_ONLY, and
    owner-namespace memory) in tool_policy — enforced there, not here.
    """
    owner_unlocked = (speaker.tier == SPEAKER_OWNER) or bool(reauth_active)

    if device.kind == DEVICE_OWNER:
        # Device trust alone grants `known`; owner elevation needs a real speaker
        # match (or the short re-auth phrase) — never device trust by itself.
        return (SPEAKER_OWNER if owner_unlocked else SPEAKER_KNOWN), owner_unlocked

    if device.kind == DEVICE_KNOWN:
        grant = device.grant if device.grant in _VALID_DEVICE_GRANTS else SPEAKER_KNOWN
        # A known device is never owner-unlocked regardless of who speaks.
        return grant, False

    # unpaired / unknown / forged device -> the device itself isn't trusted.
    return SPEAKER_UNKNOWN, False
