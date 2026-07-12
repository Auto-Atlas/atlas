# Spec 1 — Approval Domain Model

> Status: **DRAFT** (Phase 0). Frozen contract for Phases 2B & 3. Codex ref #3, #4.
> Grounded against the real tree on 2026-06-23.

## Purpose

Define one approval lifecycle, one claim protocol, and one execution-dispatch model so the sidecar
and OpenJarvis stop keeping two incompatible approval stores. Preserve at-most-once execution and
make every terminal state honest (no "failure marked executed", no orphaned `releasing` rows).

## Current state (grounded)

Two **incompatible** stores exist today:

| | Sidecar `approval_store.py` (root) | OpenJarvis `app/src/openjarvis/tools/approval_store.py` |
|---|---|---|
| Form | module-level functions | `ApprovalStore` class |
| States | `pending → releasing → consumed`; `denied`; read-time `expired` (never persisted) | `pending → approved → executed`; `denied`; `expired` (persisted) |
| Approve semantics | `consume()` = atomic CAS **and runs the handler synchronously** | `approve` only sets `approved`; execution is a separate later batch |
| Expiry | read-time only; **no `expire_*` method** | `expire_stale()` mutates `pending→expired` |
| Timestamps | REAL epoch | TEXT ISO-8601 |
| Permission memory | none | `permission_memory` table (decision/tier) |
| Dispatch | `RELEASABLE_HANDLERS` dict in `release.py` (2 entries: `create_invoice`, `send_to_channel`) | ad-hoc `if/elif` in `proactive_tools._run_action` (email/sms/calendar) |

Confirmed bugs to fix:
1. **Claim-then-check** — `approval_api.py:367` `consume()` flips `pending→releasing` **before** the
   tier/risk re-assertion at `:372`. A mismatch raises 409 and strands the row in `releasing` forever
   (no revert, never re-fires, never finishes).
2. **Unconditional executed** — `proactive_tools.py:381` `store.update_status(action.id,
   STATUS_EXECUTED)` runs regardless of `success`; a failed action is persisted as `executed`.
3. **Reconciliation is log-only** — `approval_api.py:247` reads `list_releasing()` orphans and only
   **logs** them (surfaced via `/v1/health.releasing_orphans`); it never drains/reconciles them.
4. **No `approvals`-clear endpoint** — the DELETE at `approval_api.py:328-332` is the *skill-feed*
   clear (`/v1/skills/feed/{tool}`), not approvals. (Plan's "~:328-332 unprime" referred to skills.)

> **Plan correction:** Phase 0's "reuse the existing startup reconciliation" assumed `:247` already
> reconciles. It does not — it only logs. The canonical implementation must **extend it into a real
> reconciler**, not merely inherit it.

## Canonical state machine

OpenJarvis `:8000` is the **sole writer** (locked decision). The sidecar reaches it only via API.

```
                 ┌─────────┐
   stage ───────▶│ pending │
                 └────┬────┘
        deny / expire │ claim (atomic CAS, embeds authz predicate + TTL)
        ┌─────────────┼──────────────┐
        ▼             ▼               │
    ┌────────┐   ┌─────────┐         │ (claim fails predicate → stays pending, NO transition)
    │ denied │   │ claimed │
    └────────┘   └────┬────┘
                      │ begin execution (fencing token minted)
                      ▼
                 ┌───────────┐
                 │ executing │
                 └─────┬─────┘
        ┌──────────────┼───────────────┐
        ▼              ▼               ▼
 ┌────────────────┐ ┌────────────────┐ ┌──────────────────┐
 │consumed_success│ │consumed_failure│ │ outcome_unknown  │
 └────────────────┘ └────────────────┘ └────────┬─────────┘
                                                 │ reconcile (never auto-retry)
                                                 ▼
                                    consumed_success | consumed_failure
```

**Canonical states:** `pending, claimed, executing, denied, expired, consumed_success,
consumed_failure, outcome_unknown`.

State table (every transition is a single SQL UPDATE guarded by the prior state + fencing):

| From | Event | To | Guard |
|---|---|---|---|
| pending | claim | claimed | CAS on `status='pending'` AND `expires_at>now` AND authz predicate (tier/risk) — **all in the WHERE clause** |
| pending | deny | denied | CAS on `status='pending'` |
| pending | expire | expired | CAS on `status='pending'` AND `expires_at<=now` (atomic `expire_pending()` — the missing fn) |
| claimed | begin | executing | CAS on `status='claimed'` AND matching `claim_token`; mints `fencing_token` |
| executing | side-effect ok + persisted | consumed_success | CAS on `fencing_token` |
| executing | side-effect failed (before any external effect) | consumed_failure | CAS on `fencing_token` |
| executing | crash after external side-effect, before persistence | outcome_unknown | recovered by reconciler |
| outcome_unknown | reconcile | consumed_success / consumed_failure | reconciler only; **never auto-retry** |

## Claim protocol (kills claim-then-check)

The authz predicate is part of the atomic claim, **never a post-claim check**:

```sql
UPDATE approvals
   SET status='claimed', claim_token=:tok, claimed_at=:now, claimed_by=:principal
 WHERE id=:id
   AND status='pending'
   AND expires_at > :now
   AND requester_tier = :required_tier      -- authz predicate inside the CAS
   AND risk_level     = :required_risk;
-- rowcount==1 ⇒ this caller is the unique winner AND passed authz. rowcount==0 ⇒ not claimable
-- (gone, expired, OR predicate failed) — the row is untouched, still pending. No orphan.
```

A failed predicate now leaves the row **pending** (re-claimable / expirable), never stranded in a
half-claimed state.

## Fencing & idempotency

- **`fencing_token`** — minted at `claimed→executing`. Every execution-adapter call carries it;
  the store rejects any status write whose token doesn't match the current one (guards a slow
  duplicate executor from clobbering a newer claim).
- **Per-adapter `idempotency_key`** — deterministic from `(approval_id, action_type, args_hash)`.
  Adapters that hit external systems (invoice, channel send, email) MUST pass it so a retried call
  is deduped at the boundary. `outcome_unknown` reconciliation queries by this key, never re-sends
  blind.

## Execution-adapter registry (Codex #4)

One registry keyed by `action_type`. OpenJarvis dispatches; it **never imports sidecar code**.

```
ADAPTERS: dict[action_type] -> Adapter
  Adapter.execute(approval_row, *, fencing_token, idempotency_key) -> ExecutionResult
```

Two adapter kinds:
- **Sidecar tools** (`create_invoice`, `send_to_channel`): `HttpCallbackAdapter` → authenticated
  HTTP POST to the sidecar's release endpoint (wraps today's `release.RELEASABLE_HANDLERS`). The
  sidecar executes headless and returns `ExecutionResult`. OpenJarvis owns the status write.
- **OpenJarvis actions** (`email_delete`, `email_archive`, `sms_send`, `calendar_accept/decline`):
  `InProcessAdapter` → calls the existing connector in-process (today's `_exec_*`). Replaces the
  `if/elif` chain with a registry lookup.

`ExecutionResult = {ok: bool, external_effect: bool, detail: str, result: dict|None}`.
`external_effect=True` is what forces `outcome_unknown` (not `consumed_failure`) on a crash between
effect and persistence.

## Reconciler (extends `approval_api.py:247`)

On startup AND on a periodic tick:
- Drain `claimed`/`executing` rows older than a lease (crashed mid-flight) → re-evaluate by
  `idempotency_key`: external effect found ⇒ `consumed_success`; provably not done ⇒ back to
  `pending` (if still within TTL) or `consumed_failure`; ambiguous ⇒ `outcome_unknown`.
- `outcome_unknown` rows are surfaced (health + event), never auto-retried.
- Run `expire_pending()` to transition lapsed `pending→expired` (atomic; returns transitioned IDs
  for event emission — see Spec 2).

## Compatibility surface (so Android/desktop don't break)

The canonical store is reached through **compat routes** that keep the sidecar's exact wire shapes
during Phase 3 cutover (see Spec 6 for DTOs):
- `GET /v1/approvals?status=pending` → `{"approvals":[...]}` (only `status=pending` today; canonical
  may add `status=` filters but must keep `pending` working unchanged).
- `POST /v1/approvals/{id}/approve` → `{"ok", "released_tool", "result"}`.
- `POST /v1/approvals/{id}/deny` → `{"ok": true, "denied": true}`.
- Status projection for legacy clients: `claimed|executing → "releasing"`, `consumed_* →
  "consumed"`, `expired → "expired"` (effective). New `consumed_failure`/`outcome_unknown` MUST NOT
  leak as `consumed_success` to a legacy client — project `consumed_failure → consumed` with
  `ok:false`, `outcome_unknown → releasing`-equivalent "unverified".

## Invariants (VERIFY targets)

1. A tool executes **at most once** even if Android + desktop approve simultaneously (atomic claim).
2. A claim that fails the authz predicate leaves the row pending — never a `releasing`/`claimed` orphan.
3. A failed execution is **never** `consumed_success`.
4. Crash after external side-effect but before persistence ⇒ `outcome_unknown` + reconciliation,
   no double-send.
5. Expiry is a real persisted transition (`expire_pending`), emitted as an event, not read-time only.
6. The sidecar never opens the canonical SQLite directly.

## Migration (detailed in Phase 3)

Merge, not copy: preserve OJS `pending_actions`+`permission_memory` AND sidecar
`approvals`+`skill_feed`. Explicit ID + status map (`releasing/consumed ↔ approved/executed`; add
`expired`). Dry-run report, backups, checksums/counts, write freeze, cutover, rollback,
post-cutover ownership tests.

## Open questions for review

- **Q1.** `permission_memory` (always_approve/always_deny per `permission_key`) is OJS-only. Carry it
  into canonical as-is, or fold "remembered decisions" into the identity/policy layer (Spec 5)?
- **Q2.** Should `consumed_failure` be retryable by an explicit *human* re-approval (new `pending`
  clone), or terminal? Proposed: terminal; human re-stages a fresh approval.
- **Q3.** Lease duration for `claimed`/`executing` before the reconciler reclaims (proposed 60s
  claimed, 300s executing).
