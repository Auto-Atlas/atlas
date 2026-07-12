# Spec 3 — Conversation Schema + Hydration

> Status: **DRAFT** (Phase 0). Frozen contract for Phase 4.
> Grounded against the real tree on 2026-06-23.

## Purpose

Elevate `history.db` to the canonical conversation store and make `build_context()` hydrate the live
voice context from it, so one conversation spans phone voice + desktop voice + desktop typed chat.
Android stays voice-first (no new typed-chat screen — locked decision).

## Current state (grounded)

- **`conversation_archive.py:39`** — `history.db` at `JARVIS_BRAIN_DB` (default
  `~/.openjarvis/history.db`). Schema:
  - `conversations(id, source, title, started_at, ended_at, msg_count, tool_count, total_tokens,
    meta)` — ids: `voice:local:<ms>`, `voice:phone:<ms>`, `typed:<frontendId>`; source:
    `desktop-voice|phone-voice|typed-chat`.
  - `messages(id, conv_id, seq, role, ts, text, meta)` — roles `user|assistant|sms|delegation|tool`;
    tool/delegation rows carry empty `text` + structured `meta`.
  - optional `msg_fts(conv_id, source, title, body)` FTS5.
- **Write path is out-of-band.** Voice loops do **not** write `history.db` live — they append
  per-day JSONL via `TranscriptLogger` (`bridge.py`), tagged `src=local|phone`.
  `ingest_transcripts()` (`:307`) lazily converts JSONL → `history.db` (idempotent `INSERT OR
  REPLACE` per `voice:<src>:<startMs>`), called only on demand (`search_history`, the hub history
  route, or the CLI).
- **`build_context()` (`jarvis_core.py:234-252`) hydrates from NO conversation store** — it seeds
  only: `SYSTEM_PROMPT` (persona) + `memory_pack()` (durable facts) + primed `skill_feed` messages.
  Past turns are reachable only reactively via the `search_history`/`review_conversations` tools.
- **Desktop typed chat** lives in `localStorage` (`store.ts`, key `openjarvis-conversations`),
  client-generated ids, fire-and-forget debounced sync to `POST /v1/history/sync` →
  `upsert_typed_conversation` → `typed:<id>` rows. localStorage stays authoritative.
- **No cross-surface `conversation_id`, no turn ownership, no session locking, no phone↔desktop
  handoff** anywhere in the live path. "Sessions" = 30-min-gap voice segmentation only. The two
  `session_store` DBs (`sessions.db` channel messaging, learning `spec_search`) are unrelated to
  `history.db`.
- A `'system'` role is declared on the frontend type but **no writer emits one** today.

## Canonical conversation model

`history.db` is canonical; OpenJarvis is its sole writer (locked). The sidecar reads/writes it only
via OpenJarvis APIs.

### Conversation identity (the missing primitive)

Introduce a **server-allocated `conversation_id`** that any surface can join:

- `conversation_id` = `conv_<uuid>` (new canonical id; legacy `voice:*`/`typed:*` ids map onto it
  during migration).
- A conversation has `participants[]` of `{surface, principal}` and a current `active_turn_owner`
  (see locking).
- Surface bootstrap: phone voice / desktop voice / desktop typed each call
  `POST /v1/conversations/attach` with `{surface, device_principal, speaker_principal?,
  resume_id?}` and receive a `conversation_id` (resumed or freshly allocated).

### Message schema (superset of today)

```jsonc
{
  "id": "<conversation_id>:<seq>",
  "conversation_id": "conv_...",
  "seq": 17,                       // monotonic per conversation (ties to Spec 2 sequence)
  "role": "user|assistant|tool|system|sms|delegation",
  "surface": "phone-voice|desktop-voice|typed-chat",
  "author_principal": "owner|known:<name>|device|unknown",  // Spec 5
  "ts": 1750700000123,
  "text": "...",
  "meta": { /* tool/delegation structured fields as today */ }
}
```

`system` and `tool` messages are first-class (today `system` is never written; this spec mandates
writing it so hydration is faithful).

### Hydration policy (`build_context()` rewrite)

`build_context()` gains a conversation hydration step **after** persona/memory/skills and before
`protected_head` is fixed:

1. Resolve the live `conversation_id` for this surface/session (from attach).
2. Pull the last **N turns** (proposed: last 20 messages OR ~2000 tokens, whichever first), newest
   contiguous, excluding high-frequency non-content events.
3. Trim/summarize older history per a **bounded budget** (proposed: a single rolling summary system
   message for everything older than the window — but the summary feature itself is gated on Spec 4's
   retention/privacy rules; until then, hard-trim with no summary).
4. Tool/delegation messages hydrate as compact `meta` recaps, not full payloads.
5. Owner-private content is included only when `speaker_principal` is owner-matched (Spec 5);
   otherwise the hydrated window is filtered to device-trustable content.

Hydration is **read via the OpenJarvis API** (the sidecar never opens `history.db` directly).

### Turn ownership, locking, handoff

- `active_turn_owner = {surface, principal, lease_until}`. A surface acquires the turn lock before
  emitting an assistant turn; the lock auto-expires on a lease so a dropped surface can't wedge it.
- **Handoff** (phone↔desktop): a surface may request `POST /v1/conversations/{id}/handoff`; the
  current owner's lease is released and the requester acquires it. Emits a `conversation_handoff`
  event (Spec 2). An in-flight turn completes or is cancelled cleanly — no double-turn.
- Offline writes (phone loses backend): buffered locally with client `seq` hints; on reconnect they
  are appended in order; conflict rule = **append-only, server assigns final `seq`** (no edits of
  prior turns, so no merge conflicts).

### Conversation selection

- Default: resume the most recent conversation for the owner within the session gap; else allocate a
  new one. Desktop typed chat keeps its explicit conversation list (localStorage) but each entry now
  carries a canonical `conversation_id` after first sync.

## localStorage migration

- On first run post-cutover, each localStorage `typed:<id>` conversation is registered with the
  server, receives a canonical `conversation_id`, and localStorage stores the mapping. localStorage
  remains the **typed-chat cache** (offline-readable), synced up; canonical is authoritative.
- Voice `voice:local:*` / `voice:phone:*` archive rows are mapped to canonical ids during the
  ingest migration; `ingest_transcripts` keeps running as the JSONL→canonical importer but now
  allocates/links canonical ids instead of bare `voice:*` ids.

## Invariants (VERIFY targets)

1. A turn handed off phone↔desktop mid-conversation continues with full context, no lost or doubled
   turn.
2. `build_context()` on any surface hydrates the same canonical conversation.
3. Offline phone writes replay in order with no edits to prior turns.
4. Owner-private turns are not hydrated for an unmatched speaker.

## Open questions for review

- **Q1.** Window size & trimming: last-20-msgs vs token-budget vs hybrid (proposed hybrid above).
- **Q2.** Should desktop typed chat and desktop *voice* auto-join the **same** conversation when
  interleaved, or stay separate conversations that share memory? Proposed: same conversation if
  within the session gap and same owner; the user can branch explicitly.
- **Q3.** Rolling-summary: defer entirely to Spec 4 sign-off (proposed), or allow a non-persisted
  ephemeral summary in-context now?
