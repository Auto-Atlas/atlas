# Spec 6 — DTO + Credential / Security Contract

> Status: **DRAFT** (Phase 0). Frozen contract for Phases 1, 2A, 5. Codex ref #9.
> Grounded against the real tree on 2026-06-23.

## Purpose

Pin canonical JSON shapes (Android Kotlin + TS) so the wire and the DTOs can't drift, and define the
credential/security contract: per-device credentials, server-side hashing, constant-time compare on
**all** paths, at-rest encryption, rotation + revocation, and token redaction. Feeds Phase 1 DTO
fixes and Phase 5 codegen.

## Current state (grounded)

### DTOs (Android lives in `data/models/`, not `models/`)

- **`Approval.summary` already EXISTS** (`Approval.kt:34`, non-null `String`) and is on the wire
  (`approval_store._row_to_dict` emits `summary`). **Plan's "missing summary" is stale.** Design
  contract: the displayed amount is computed from `args` via `totalDollars`, never trusted from
  `summary`.
- **`MemoryAddResult.speaker` already EXISTS** (`Misc.kt:30`) — but is **non-nullable `String` with
  no default**, while the server returns `{"speaker": add.speaker}` where `add.speaker` is
  `str|None`. Owner writes send `"speaker": null` → **decode crash** (`MissingFieldValue`/null into
  non-null). **Real fix: `speaker: String? = null`.**
- **`ClearResult(ok, cleared)` is CONFIRMED MISSING.** `DELETE /v1/skills/feed/{tool}` returns
  `{"ok": true, "cleared": <int>}`, but `ApiClient.unprime()` decodes it into `FeedResult{ok, tool,
  mode, id}` — `tool`/`mode` are required and absent on the wire ⇒ **decode fails**; `cleared` is
  unmodeled. **Add a dedicated `ClearResult`.**

### Auth compares

- **`auth_middleware.py:36` (HTTP path)** uses non-constant-time `token != self._api_key`.
- **`auth_middleware.py:106` (WS path)** uses `secrets.compare_digest` (constant-time).
- The **separate** sidecar approval API (`approval_api.py:79-83`) already uses `hmac.compare_digest`
  (constant-time). **Plan correction:** the `!=` weakness is **OpenJarvis-server-specific**
  (`auth_middleware.py:36`), not the sidecar approval surface.

### Credentials

- **Single shared, long-lived bearer token** for every device. Stored **plaintext** in
  `approval_token.txt` (server, `pairing.app_token()`) and **plaintext DataStore** on Android
  (`Settings.kt:36`, `stringPreferencesKey("app_token")`; no Keystore/EncryptedSharedPreferences).
- **QR embeds the raw token** (`eve://connect?base=&token=`); the PNG persists in temp with no
  cleanup. **No rotation, revocation, expiry, or single-use.**
- Redaction is **architectural, not a scrubber**: tokens are kept out of URLs (sent via
  `Authorization: Bearer` / `Sec-WebSocket-Protocol: "bearer, <token>"` on both sidecar and Android,
  with explicit comments) — but there is **no log-string masking** of credentials anywhere.

## DTO contract (Phase 1 fixes + Phase 5 codegen source)

Canonical shapes are the single source the OpenAPI spec (Phase 5) generates from. Phase 1 fixes the
three concrete mismatches now:

```kotlin
// Misc.kt
@Serializable data class MemoryAddResult(
    val ok: Boolean,
    val speaker: String? = null,        // FIX: nullable (wire sends null for owner writes)
    val remembered: String,
)

// new — Skill.kt or Misc.kt
@Serializable data class ClearResult(    // FIX: dedicated type for DELETE /v1/skills/feed/{tool}
    val ok: Boolean,
    val cleared: Int,
)
```

- `ApiClient.unprime()` decodes `ClearResult`, not `FeedResult`.
- `Approval.summary` stays as-is (present, decoded, **not** trusted for the dollar amount).
- Android `Approval` adds `summary: String? = null` defensiveness only if a captured real response
  ever omits it; today it's always present, so no change required beyond the failing-decode test.
- The failing test (Phase 1 gate): Kotlin serialization decodes a **captured real** `/v1/approvals`,
  `/v1/skills/feed`, `/v1/memory` (owner write), and `DELETE /v1/skills/feed/{tool}` response — the
  last two currently throw.

JSON config stays `ignoreUnknownKeys=true, isLenient=true, explicitNulls=false, encodeDefaults=true`
(`ApiClient.kt:192-197`) so additive server fields don't break old clients.

## Credential / security contract

### Pairing → per-device credential

- One-time **bootstrap code** (short-lived, single-use) replaces handing out the long-lived token.
  Scanning/entering it mints a **per-device credential** (unique per device).
- Server stores the credential **hashed** (e.g. salted SHA-256 / argon2), never plaintext. The
  current plaintext `approval_token.txt` is replaced by a hashed device registry.
- QR carries the **bootstrap code**, not the durable credential; the PNG is written to a private
  path and deleted after pairing (no lingering token image in temp).

### Compare on all paths

- Fix `auth_middleware.py:36` to `secrets.compare_digest`. Audit every credential compare; constant
  time everywhere (WS path and approval API already are).

### At rest (Android)

- Store the per-device credential in **Android Keystore-backed EncryptedSharedPreferences** (or
  `EncryptedFile`), not plaintext DataStore. Read fresh per request as today.

### Rotation + revocation

- Each credential has an id, issued-at, and revoked flag. A revoked device fails auth immediately
  (Phase 2A verify gate). Rotation = issue new, revoke old, with a grace overlap.
- QR-scan host+token replacement (`ConnectScreen.kt` replaces both on a valid scan and persists
  immediately) gains a **confirmation step** before overwriting an existing working credential.

### Redaction

- Keep the URL-free token discipline (already in place). Add a **log/transcript scrubber** that masks
  any bearer/credential-shaped string before it reaches logs or the transcript JSONL (sidecar +
  Android), so a future code path can't leak a token into `transcripts/*.jsonl`.

## OAuth (re-scoped, Codex #9)

- The callback state guard is **already** one-time + httponly + browser-bound + constant-time
  (`connectors_router.py:441-504`) — **do not rebuild it** (non-goal).
- Fix only: the `window.open` popup can't carry the bearer (`SourceConnectFlow.tsx`) →
  authenticated POST → one-time expiring launch URL. Add **PKCE** for public-client providers. Do
  **not** broadly exempt `oauth/start`.

## Invariants (VERIFY targets)

1. Captured real backend JSON decodes cleanly into the Kotlin DTOs for every changed endpoint
   (owner memory write + unprime currently throw → must pass).
2. Unauthenticated `/api/offer` rejected; revoked device blocked; forged/expired token rejected;
   replayed bootstrap code rejected (Phase 2A).
3. No credential-shaped string appears in logs or transcripts.
4. All credential compares are constant-time.
5. OAuth: authenticated start → one-time URL → existing callback state validation intact.

## Open questions for review

- **Q1.** Hash choice for the device registry — argon2id (stronger, adds a dep) vs salted SHA-256
  (stdlib `hashlib`)? Proposed: argon2id if a dep is acceptable, else PBKDF2-HMAC-SHA256 via stdlib.
- **Q2.** Bootstrap-code TTL + single-use enforcement window (proposed 10 min, single-use, rate-limited).
- **Q3.** Keep a legacy shared-token path during migration (Phase 2A transition) or hard-cut? Proposed:
  short dual-accept window where a paired device can upgrade its shared token to a per-device
  credential, then the shared token is revoked.
