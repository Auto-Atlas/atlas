# Phase 0 — Spec Review Tracker

The Phase-0 gate: all six specs reviewed and **frozen** before any dependent phase closes.
Review mode: **a collaborator reviewed directly (no Codex).** **FROZEN 2026-06-23.**

| # | Spec | Drafted | Review (a collaborator) | Frozen |
|---|------|---------|--------------|--------|
| 1 | Approval domain model | ✅ | ✅ | ✅ |
| 2 | Event envelope + delivery | ✅ | ✅ | ✅ |
| 3 | Conversation schema + hydration | ✅ | ✅ | ✅ |
| 4 | Memory namespace model | ✅ | ✅ | ✅ |
| 5 | Identity model | ✅ | ✅ | ✅ |
| 6 | DTO + credential/security | ✅ | ✅ | ✅ |

Once frozen, a spec changes only by an explicit ARCH amendment recorded in the Review log below.

## Resolved decisions (2026-06-23)

**Spec 1 — Approval**
- permission_memory (remembered always-approve/deny): carry into canonical **as-is** for Phase 3; no scope expansion.
- `consumed_failure`: **terminal** — a human re-stages a fresh approval; no silent retry.
- Reconciler leases: **60s claimed / 300s executing** before reclaim.

**Spec 2 — Events**
- Topology: **one socket per app surface**, carrying all authorized families, server-filtered by `audience` + client allowlist.
- Outbox retention: **7 days** (weekend-offline phone can still replay).
- High-frequency voice metrics (`mic_level`, `token`): **ephemeral lane** (no outbox/sequence); durable outbox only for state-meaning events.

**Spec 3 — Conversation**
- Hydration window: **hybrid — last 20 messages OR ~2000 tokens**, whichever first.
- Rolling summary: **deferred** (hard-trim, no summary) until Spec 4 retention rules land.
- Desktop typed + desktop voice: **same `conversation_id`** when same owner & within the session gap; user can branch explicitly. *(fork)*

**Spec 4 — Memory**
- Conversation-summary-into-memory: **deferred** until retention/privacy/attribution/dedup rules written.
- Cross-namespace visibility: owner may **list/audit** any namespace explicitly; boot hydration **never auto-mixes**.
- Embeddings: **FTS-only now**, leave a migration seam (no vector column yet).

**Spec 5 — Identity**
- Re-auth phrase TTL: **≤120s** (reuses `EVE_OWNER_PHRASE`; never the 12h blanket).
- `EVE_UNSAFE_TREAT_ALL_AS_OWNER`: **dev-only, hard-disabled once any profile is enrolled**; do not widen.
- Resolver location: **new `identity.py`** imported by both `speaker_state` and `tool_policy` (avoids circular dep).
- Family scope: **model the device/speaker fields generally (known/kid supported in schema), but Phase 2A issues owner-device only.** *(fork)*

**Spec 6 — DTO + Credential**
- Bootstrap-code: **10 min TTL, single-use, rate-limited.**
- Credential hashing: **argon2id** (adds argon2-cffi dependency). *(fork)*
- Existing-device migration: **dual-accept upgrade window** — accept old shared token AND new per-device creds; paired phone silently upgrades; then the shared token is revoked. *(fork)*

## Plan corrections surfaced during grounding (decision-grade)

1. **Spec 1 / reconciliation.** `approval_api.py:247` only **logs** `releasing` orphans — it does NOT
   reconcile them. The canonical reconciler must be *built*, not merely "reused".
2. **Spec 1 / unprime endpoint.** The DELETE at `approval_api.py:328-332` is the **skill-feed** clear,
   not an approvals endpoint. There is no approvals-clear endpoint today.
3. **Spec 5 / owner memory.** `recall` is **risk=medium**, so owner-private memory is currently allowed
   to the `known` tier — NOT owner-gated today. Owner-gating it is a deliberate Spec 5 policy change.
4. **Spec 6 / DTOs.** `Approval.summary` and `MemoryAddResult.speaker` already EXIST. Real bugs:
   `MemoryAddResult.speaker` non-nullable vs `null` wire → decode crash (make nullable); and
   `ClearResult(ok, cleared)` is missing → `unprime` decode fails.
5. **Spec 6 / auth compare.** The non-constant-time `!=` is **OpenJarvis-server-specific**
   (`auth_middleware.py:36`). The sidecar approval API already uses `hmac.compare_digest`.

## Review log

- **2026-06-23** — All six specs frozen after a collaborator's walkthrough review; 19 open questions resolved
  (defaults + 4 product/security forks) and recorded above. No Codex pass (a collaborator's call). Phase 1 cleared
  to begin.
