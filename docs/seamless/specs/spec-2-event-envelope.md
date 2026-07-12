# Spec 2 — Event Envelope + Delivery

> Status: **DRAFT** (Phase 0). Frozen contract for Phases 2B & 5. Codex ref #2.
> Grounded against the real tree on 2026-06-23.

## Purpose

One event envelope and one delivery contract across all surfaces, with at-least-once delivery,
idempotent consumer dedup, and durable replay — so approvals, voice states, memory updates, the
skill feed, and conversation messages stop being best-effort fan-outs that vanish when a client is
disconnected.

## Current state (grounded)

Three independent in-memory broadcasters + a de-facto lossy file "outbox", **no ids anywhere**:

| Surface | Transport | Endpoint | Envelope | id | seq | cursor | dedup |
|---|---|---|---|---|---|---|---|
| Sidecar stream | WS | `/v1/stream` (:8799) | flat `{type, ...}` | only approval `id` | – | – | none |
| Phone metrics | WS (`websockets`) | :8766 | flat `{type, ...}` | – | – | – | frame-id only |
| OpenJarvis agents | WS | `/v1/agents/events` | `{type, timestamp, data}` | – | – | – | none |
| Transcript log | file | `transcripts/*.jsonl` | `{ts, src, type, ...}` | – | – | EOF-seek | none |
| FE chat/research | SSE (fetch) | `/v1/chat/completions`, `/api/research` | `{event, data}` / `{type,...}` | – | – | – | none |

Key findings:
- **`approval_pending` is never broadcast.** `tool_policy.py:132` stages a pending approval as a pure
  DB write; no event fires. Android *parses* `approval_pending` but in practice discovers approvals
  by **polling** `GET /v1/approvals` (`ApprovalsViewModel.refresh()`). The socket is only a
  "something changed, re-poll" hint + resolved/expired notification cancel.
- The sidecar `/v1/stream` forwarder (`approval_api.py:148-227`) **tails the JSONL file** and
  re-broadcasts `src=="phone"` lines. On restart it seeks to EOF → events written while down are
  **lost** (no replay).
- OJS `ws_bridge` gives each client a bounded `asyncio.Queue(maxsize=100)`; on `QueueFull` the event
  is **silently dropped**.
- The only correlation-ish key in existence is `deleg_id` (delegation events only).

> Confirmed: zero `event_id`/sequence/cursor/outbox/idempotency anywhere. The plan's premise holds.

## Canonical envelope

Every event on every surface is wrapped:

```jsonc
{
  "event_id":       "evt_<uuid>",      // globally unique; the ONLY dedup key
  "aggregate_id":   "<entity id>",      // e.g. approval id, conversation id, device id
  "type":           "approval_pending", // dotted/namespaced discriminator
  "version":        1,                   // schema version of `data` for this type
  "sequence":       42,                  // monotonic per aggregate_id (gap-detectable)
  "ts":             1750700000123,       // epoch ms
  "correlation_id": "corr_<uuid>",       // ties a causal chain across surfaces
  "causation_id":   "evt_<uuid>|null",   // the event that caused this one
  "audience":       "owner|device|all",  // who may receive it (see redaction, Spec 6)
  "data":           { /* type-specific payload, shape pinned by version */ }
}
```

Rules:
- **Dedup by `event_id` only** — never by `aggregate_id` (a verify gate). Consumers keep a durable
  seen-set / cursor and drop a repeat `event_id`.
- **`sequence` is per `aggregate_id`** and monotonic; a consumer can detect gaps and request replay.
- `audience` gates delivery; `owner`-audience events (e.g. owner memory updates) are not delivered to
  a device that only holds `device_principal` without a speaker match (Spec 5).

## Transactional outbox (Codex #2)

The event row is written **in the same DB transaction** as the state change that produced it. Since
OpenJarvis is the sole writer of canonical state, the outbox lives in OpenJarvis.

```sql
BEGIN;
  UPDATE approvals SET status='claimed' WHERE id=:id AND ...;   -- state change
  INSERT INTO outbox(event_id, aggregate_id, type, version, sequence, ts,
                     correlation_id, causation_id, audience, data, delivered)
         VALUES (..., 0);                                        -- event, same txn
COMMIT;
```

A relay loop reads undelivered outbox rows in `sequence` order, fans out to live subscribers, and
marks `delivered=1` once accepted by the durable broker step (not per-client). Delivery is
**at-least-once**; the consumer's `event_id` dedup makes it effectively-once.

For events produced inside the voice loop (a separate process from OpenJarvis), the producer writes
to the canonical store via the OpenJarvis API, which performs the outbox insert — keeping the
single-writer invariant. The JSONL transcript stays as a human/debug artifact, **demoted** from its
current role as the cross-process bridge.

## Delivery & cursors

- Each consumer (Android socket, desktop socket, desktop SSE) holds a **durable per-consumer cursor**
  = last contiguously-acked `sequence` per `aggregate_id` (or a global watermark for fan-in streams).
- On reconnect the consumer sends its cursor; the relay replays outbox rows after it. No more
  EOF-seek data loss.
- Backpressure: bounded per-client queue, but overflow triggers a **resync-from-cursor** signal, not
  a silent drop.

## Event catalog (v1)

Covered families (each a pinned `data` schema, versioned independently):
- **Approval lifecycle:** `approval_pending`, `approval_claimed`, `approval_resolved`
  (`ok`/`denied`), `approval_expired`, `approval_outcome_unknown`. (Spec 1 states map here.)
- **Voice states:** `voice_user_speaking`, `voice_thinking`, `voice_bot_speaking`,
  `voice_transcript` (user/bot/interim), `voice_tool_call`, `voice_tool_result`. (From :8766 today.)
- **Memory updates:** `memory_added`, `memory_deleted` (audience=owner or speaker-namespaced).
- **Skill feed:** `skill_fed`, `skill_unprimed`.
- **Conversation messages:** `conversation_message_appended`, `conversation_handoff`. (Spec 3.)
- **Execution status:** `execution_started`, `execution_finished`. (Spec 1 adapters.)

## Migration of the three broadcasters

- Sidecar `/v1/stream`: keep the endpoint + WS auth (already header-based, constant-time), but emit
  the canonical envelope; stop tailing JSONL for delivery — subscribe to the outbox relay instead.
- Phone metrics `:8766`: re-expressed as `voice_*` envelopes; `approval_api` subscribes and
  re-broadcasts deduped (removing the duplicate transcript `thinking` path — Phase 2B).
- OJS `/v1/agents/events`: already `{type,timestamp,data}` — extend to the full envelope; agent
  events become one family among many. Frontend `useAgentEvents.ts` already expects `{type,
  timestamp, data}` and needs only the added fields.

## Invariants (VERIFY targets)

1. Pending + expired approval events are not duplicated or lost across an API restart (cursor replay;
   dedup by `event_id`, **never** `aggregate_id`).
2. A disconnected client that reconnects receives every event after its cursor, in `sequence` order.
3. An event row is never committed without its state change, and vice versa (same txn).
4. `audience=owner` events never reach a device-only principal.

## Open questions for review

- **Q1.** Single global event stream vs per-family streams? Proposed: one socket per app surface
  carrying all families the client is authorized for, filtered server-side by `audience` + a
  client subscription allowlist (matches today's `useAgentEvents` allowlist pattern).
- **Q2.** Outbox retention / compaction window before pruning delivered rows (proposed 7 days, so a
  phone offline over a weekend can still replay).
- **Q3.** Do voice-state events (high frequency: `mic_level`, `token`) go through the durable outbox,
  or stay ephemeral best-effort (no outbox row) since they're worthless to replay? Proposed:
  **ephemeral lane** for level/token metrics (no outbox, no sequence), durable lane for everything
  with state meaning. This keeps the outbox small.
