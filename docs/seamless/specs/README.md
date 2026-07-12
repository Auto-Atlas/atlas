# Seamless Integration — Phase 0 Contract Specs

These are the six frozen contracts that every later phase implements against. They exist so the
three tiers (Python voice **sidecar**, **OpenJarvis** Tauri/`:8000`, native **Android** `eve-app`)
stop keeping separate sources of truth and separate conversations.

Source of truth for the overall effort: `../../EVE-JARVIS-MASTERPLAN.md` and the reconciled
Master Plan v4 (`~/.claude/plans/this-is-what-i-transient-garden.md`).

## The hard rule

`ARCH` freezes each contract **before** any implementer touches the seam. No cross-seam coding
without a locked contract — this is the single rule that prevents a third integration layer.

Phase sequence is a hard gate, not a suggestion:

```
0  →  1 (parallel, independent)  →  2A (device auth, hard gate)  →  2B  →  3  →  4  →  5
```

No canonical-state or transport work before 2A passes.

## The six specs

| # | Spec | Codex ref | Primarily implemented in |
|---|------|-----------|--------------------------|
| 1 | [Approval domain model](spec-1-approval-model.md) | #3, #4 | Phase 2B, 3 |
| 2 | [Event envelope + delivery](spec-2-event-envelope.md) | #2 | Phase 2B, 5 |
| 3 | [Conversation schema + hydration](spec-3-conversation.md) | — | Phase 4 |
| 4 | [Memory namespace model](spec-4-memory.md) | — | Phase 4 |
| 5 | [Identity model](spec-5-identity.md) | #1 | Phase 2A |
| 6 | [DTO + credential/security contract](spec-6-dto-credential.md) | #9 | Phase 1, 2A, 5 |

## Phase-0 gate

All **six** specs must be Codex-reviewed and frozen before any phase that depends on them closes.
Review status is tracked in [REVIEW.md](REVIEW.md).

## Locked decisions these specs encode

- Canonical store = OpenJarvis `:8000`; OpenJarvis is the **sole writer** of canonical state. The
  sidecar never opens the canonical SQLite directly — all canonical writes go through OpenJarvis APIs.
- Device auth is a hard gate (Phase 2A) before any further authority centralization.
- Native-only phone surface; the hosted PWA / `phone_gateway.py` (`:8795`/`:8445`) is retired.
- Single supervisor = interactive current-user Scheduled Task (`JarvisVoiceLoop`); Tauri connects only.
- Shared conversation spans phone voice + desktop voice + desktop typed chat only. Android stays
  voice-first.
- Phone authorization is **tiered**: a paired device grants general chat + low/medium-risk tools;
  owner-private memory and high-risk/owner-only actions additionally require a speaker-ID match (or a
  short re-auth phrase).
- Canonical conversation = `history.db`; `build_context()` hydrates live voice context from it.
