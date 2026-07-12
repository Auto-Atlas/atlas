# Spec 5 — Identity Model

> Status: **DRAFT** (Phase 0). Frozen contract for Phase 2A (HARD GATE). Codex ref #1.
> Grounded against the real tree on 2026-06-23.

## Purpose

Split the single always-owner global into two principals — **`device_principal`** (from pairing) and
**`speaker_principal`** (from voice) — and define a tiered policy: a paired device grants general
chat + low/medium-risk tools; owner-private memory and high-risk/owner-only actions additionally
require a speaker match (or a short re-auth phrase). This honors Codex #1 (device ≠ speaker) while
keeping the soft-gate UX, and replaces the blanket 12h override at `phone_bot.py:338`.

## Current state (grounded)

- **No principal abstraction exists.** Identity is one process-global "current speaker tier" string
  in `speaker_state.py`: `_name`, `_tier`, `_at`, `_override_until`. There is no device identity.
- **`phone_bot.py:325-338`** forces owner unconditionally on connect:
  `set_current(_OWNER,"owner",1.0)` (fake score 1.0, no voice matched) + `grant_owner_override(43200)`
  = **12h** session-long override. The override is checked **first** in `current_tier()`
  (`speaker_state.py:36-37`), so the per-utterance speaker-ID result is **computed but ignored** on
  the phone.
- **`speaker_state.current_tier()`** returns `owner` while override active; else `unknown` if unset
  or stale (30s TTL), else `_tier`. `set_current(name, tier, score)` — **`score` is accepted but
  discarded** (a natural hook for a speaker-confidence dimension).
- **`tool_policy.tier_allows(tool_name, risk_level, tier)`** is the single authz chokepoint. It takes
  a **tier string, not a principal**. `tier=="owner"` → unconditional allow.
  - `TIER_MAX_RISK = {owner:high, known:medium, kid:low, unknown:None}`
  - `OWNER_ONLY = {jarvis_agent, open_on_pc}`
  - `KID_DENY = {check_email, check_inbox, get_calendar, search_notes, review_conversations}`
- **`pairing.py`** = a single **shared** long-lived bearer token handed to every device (no
  per-device credential, no device identity, no revocation — see Spec 6).
- **`speaker_stt.py` `SpeakerIDMixin`** writes `speaker_state.set_current(m.name, m.tier, m.score)`
  per utterance (threshold `EVE_SPEAKER_THRESHOLD` default 0.75). The owner-phrase override
  (`EVE_OWNER_PHRASE`) grants a **short** 120s window — the legitimate use the phone path abuses with
  43200s.

> **Plan correction:** owner-private memory recall (`recall`) is **risk=medium** today, so it is
> currently allowed to the `known` tier, not owner-gated. Making owner memory owner-gated is a
> deliberate policy change this spec defines (below), not just a matter of removing the override.

## Identity model

Two independent principals, resolved per turn:

```
device_principal  := from the paired per-device credential (Spec 6). One of:
                       owner-device | known-device:<id> | unpaired/unknown
speaker_principal  := from voice speaker-ID (resemblyzer match) within TTL. One of:
                       owner | known:<name> | kid:<name> | unknown
```

`speaker_principal=unknown` is the **default** until a match lands (missing/failed recognition,
resemblyzer absent, or TTL lapsed).

### Effective authorization = the pair, not a single tier

`tier_allows` evolves from `(tool, risk, tier)` to `(tool, risk, device_principal,
speaker_principal)` — or, to minimize blast radius, a small resolver computes an **effective tier**
plus an **owner-gate flag**:

```
resolve_authz(device_principal, speaker_principal) -> (effective_tier, owner_unlocked: bool)

  owner_unlocked = (speaker_principal == owner) OR (active short re-auth phrase override)
  effective_tier:
    device owner-paired  + speaker owner-matched   -> owner   (owner_unlocked=True)
    device owner-paired  + speaker unknown/known   -> known   (owner_unlocked=False)  # device-trusted
    device known-paired                            -> known or kid per device grant
    device unpaired/unknown                        -> unknown (deny all tools)
```

### Policy gate

`tier_allows` keeps its risk math but adds an **owner-gate** for owner-private capabilities:

- General chat + **low/medium**-risk tools: allowed on **device trust** (effective_tier ≥ known),
  no speaker match required.
- **High-risk** tools (`create_invoice`, `send_to_channel`, …), **`OWNER_ONLY`** tools, and
  **owner-private memory** (the `recall`/owner-namespace path, Spec 4): require `owner_unlocked`
  (speaker match OR short re-auth phrase). This is enforced regardless of the tool's nominal risk
  level — i.e. owner memory is promoted to an owner-gated capability even though its risk is
  `medium`.
- `unknown` device ⇒ deny all tools (the device itself isn't trusted).

The short re-auth phrase reuses the existing `EVE_OWNER_PHRASE` → `grant_owner_override` mechanism
but **time-boxed short** (the intended ≤120s window), never the 12h blanket. `current_tier()` must
no longer let a session-long override masquerade as a voice match for owner-gated capabilities.

### What changes at the seams

- **`phone_bot.py:325-338`:** remove `grant_owner_override(43200)`. Set `device_principal =
  owner-device` from the paired credential; leave `speaker_principal = unknown` until `speaker_id`
  matches. Stop the fake `set_current(owner, 1.0)`.
- **`speaker_state.py`:** introduce explicit `device_principal` state alongside the speaker state;
  start **using** the `score` (confidence) that's currently discarded; keep the 30s speaker TTL but
  decouple it from device trust (device trust persists for the paired session; speaker match is what
  expires).
- **`tool_policy.py`:** `tier_allows` consumes the resolver output (effective_tier + owner_unlocked);
  owner-gated set = high-risk ∪ `OWNER_ONLY` ∪ owner-namespace memory.

## Invariants (VERIFY targets)

1. Paired device + **no** voice match → low/medium tools succeed; **high-risk denied**; owner memory
   **not recalled**.
2. A low-risk tool succeeds on device trust alone.
3. Owner elevation requires a real speaker match or the short re-auth phrase — never a session-long
   override.
4. Speaker match TTL expiry drops owner_unlocked back to device-trust tier mid-conversation without
   killing general chat.
5. Unpaired/unknown device is denied all tools.

## Open questions for review

- **Q1.** Re-auth phrase TTL: confirm ≤120s (proposed). After expiry, the next owner-gated action
  re-prompts for the phrase or a voice match.
- **Q2.** Should `kid` device-principals exist (a paired family tablet), or is "device" always
  owner-device for now? Proposed: model the field generally but only owner-device is issued in
  Phase 2A.
- **Q3.** `EVE_UNSAFE_TREAT_ALL_AS_OWNER` escape hatch (`speaker_state.boot_default_tier`): keep for
  dev only, hard-disabled when any profile is enrolled (current behavior) — confirm we don't widen it.
- **Q4.** Where does the resolver live — in `speaker_state` (so `current_tier()` callers get it free)
  or a new `identity.py`? Proposed: new `identity.py` that both `speaker_state` and `tool_policy`
  import, to avoid a circular dependency.
