# Spec 4 — Memory Namespace Model

> Status: **DRAFT** (Phase 0). Frozen contract for Phase 4.
> Grounded against the real tree on 2026-06-23.

## Purpose

One speaker-namespaced memory with **filter-aware retrieval**, so a family member's facts are never
recalled for another speaker, and so owner-private memory is gated on a speaker match. Unify the two
parallel stores (markdown + canonical SQLite) without losing the markdown's existing per-speaker
isolation.

## Current state (grounded)

Two **parallel, unconnected** stores:

| | Markdown (`jarvis-memory.md`) | Canonical SQLite (`memory.db`) |
|---|---|---|
| Owner | `memory_tool.py` (root) | OpenJarvis daemon via `app/.../storage/sqlite.py` → Rust |
| Voice tools | `remember` / `recall` | `search_knowledge` (read-only) |
| Boot injection | `memory_pack()` → context | not injected |
| Access | direct file read/write | HTTP via `OpenJarvisClient` |
| Speaker scoping | **yes** (per-file `_page_for`) | **no** |
| Search | naive word-overlap | FTS5 BM25, **filter-blind** |

- **`SQLiteMemory.retrieve()` is filter-blind at both layers** (confirmed): the Python wrapper
  (`sqlite.py:99-101`) calls `self._rust_impl.retrieve(query, top_k)` — `**kwargs` (any `metadata=`
  filter) is **dropped** before Rust. The Rust SQL (`sqlite.rs:158-166`) is `... WHERE documents_fts
  MATCH ?1 ... LIMIT ?2` — **no metadata predicate**. `metadata` is stored (`sqlite.rs:117`) and
  returned but never used to filter.
- Schema: `documents(id, content, source, metadata TEXT, created_at)` + `documents_fts(content,
  source)`. **No `speaker` column, no `embedding` column.** FTS covers content+source only.
- The markdown layer **is** speaker-namespaced: `_page_for(name, tier)` routes owner→main page,
  known/kid→`eve-memory-<slug>.md`; `remember`/`recall` go through
  `speaker_state.current_speaker()/current_tier()`. `memory_pack()` returns the **owner page only**.
- **The sidecar reads canonical memory over HTTP (`search_knowledge`) but does NOT write it** —
  `OpenJarvisClient.memory_store` exists but has no caller; all writes go to markdown. The sidecar
  never opens `memory.db` directly (confirmed: only `approval_store`/`conversation_archive` SQLite
  connections exist in the sidecar).
- **No dedup, no deletion tool, no export, no offline-write queue** on either store. Daemon `/store`
  is fire-and-forget; if the daemon is down the write is lost (raises `RuntimeError`).

## Canonical memory model

Canonical = OpenJarvis `memory.db`; OpenJarvis is sole writer (locked). The sidecar reads/writes
**only** via `OpenJarvisClient` HTTP. Markdown is demoted to an **optional export mirror**.

### Namespace = speaker principal

Add a first-class **`speaker_namespace`** dimension (not a buried metadata key):

- Schema change: add an indexed `namespace TEXT NOT NULL` column to `documents` (values:
  `owner`, `known:<slug>`, `kid:<slug>`). Backfill from migration. `metadata` stays for free-form
  attributes (source surface, confidence, ts).
- Every `store(content, *, namespace, source, metadata)` requires a `namespace`.
- Optionally a `visibility` flag (`private|shared`) for facts an owner explicitly shares across
  namespaces; default `private`.

### Filter-aware retrieval (fixes the blind `retrieve`)

`retrieve(query, *, namespace, top_k)` becomes namespace-scoped end-to-end:

```sql
SELECT d.content, d.source, d.metadata, d.namespace,
       bm25(documents_fts, 1.0, 0.5) * -1 AS score
FROM documents_fts f
JOIN documents d ON d.rowid = f.rowid
WHERE documents_fts MATCH :query
  AND d.namespace = :namespace            -- the missing predicate
ORDER BY bm25(documents_fts, 1.0, 0.5)
LIMIT :top_k;
```

Both layers change: the Python wrapper must **forward** `namespace` to Rust (today it drops kwargs);
the Rust query must bind it. The daemon `/search` route must pass the caller's namespace (today it
calls `backend.retrieve(query, top_k)` with no filter).

**Namespace is derived server-side from the authenticated principal where possible** — a caller
cannot retrieve `owner` namespace without an owner-matched `speaker_principal` (Spec 5). A
device-only principal gets its device/shared namespace, never owner-private facts.

### `memory_pack()` / hydration

`memory_pack()` becomes namespace-aware: it returns the owner namespace **only when the live speaker
is owner-matched**; for an unmatched speaker it returns shared/non-private facts or nothing. This
plugs the current gap where boot always injects the owner page regardless of who is speaking.

### Dedup / deletion / export / offline

- **Dedup:** on `store`, near-duplicate detection (normalized-text hash + FTS similarity threshold)
  within the same namespace; duplicates update `last_seen`/confidence instead of inserting.
- **Deletion:** expose `memory_delete(doc_id)` (Rust `delete` already exists) as a voice + API tool,
  namespace-scoped; emits `memory_deleted` (Spec 2).
- **Export:** namespaced export endpoint; markdown remains a generated mirror (owner namespace →
  `jarvis-memory.md`, others → `eve-memory-<slug>.md`) for human readability and git history.
- **Offline writes:** sidecar buffers `memory_store` calls when the daemon is unreachable and
  replays them (idempotent by a client-supplied write id) — no silent loss.

## Migration

- Parse each markdown page (`jarvis-memory.md` + `eve-memory-<slug>.md`) into namespaced canonical
  rows (owner / known:<slug> / kid:<slug>), preserving the `[YYYY-MM-DD]` date into `metadata.ts`.
- Dedup on import. Keep markdown as the export mirror (one-way canonical→markdown thereafter).
- Wire the sidecar `remember`/`recall` to write/read canonical via `OpenJarvisClient` (markdown
  becomes a mirror, not the source of truth). `search_knowledge` stays read-only over canonical.

## Invariants (VERIFY targets)

1. One speaker's facts are **never** recalled for another (namespace predicate enforced in SQL).
2. Owner-private memory requires an owner-matched speaker; a device-only principal can't read it.
3. The sidecar never opens `memory.db` directly — all access via OpenJarvis API.
4. Daemon-down writes are queued and replayed, not lost.

## Open questions for review

- **Q1.** Rolling conversation-summary into memory: **deferred** until retention/privacy/attribution/
  dedup rules are signed off (per plan). Confirm deferral.
- **Q2.** Should `kid:`/`known:` namespaces be visible to the owner on demand (owner can read all),
  or strictly isolated? Proposed: owner may **list/audit** any namespace explicitly but boot
  hydration never auto-mixes namespaces.
- **Q3.** Embeddings: schema has no embedding column and retrieval is pure FTS5. Add a vector column
  now (future semantic recall) or keep FTS-only for Phase 4? Proposed: FTS-only now; leave a
  migration seam.
