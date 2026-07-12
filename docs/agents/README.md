# EVE Agent Hub — delegate agent registry (living docs)

EVE is the **hub / chief-of-staff**. She keeps her own local skills AND can hand
work to a registry of specialist agents. This directory is the living
documentation index of every agent EVE can delegate to: its GitHub, its
interface contract, and the `DelegateRegistry` spec values it carries.

Each agent doc captures the 6-point integration contract EVE needs:
transport + endpoint · auth scheme · task-request contract · specialty (drives
routing) · **callback capability** (push vs poll — the "connector back") · trust
tier (→ tool_policy risk level).

| Agent | GitHub | Specialty | Transport | Callback | Risk | Phase | Status |
|-------|--------|-----------|-----------|----------|------|-------|--------|
| [Jarvis](jarvis.md) | local / OpenJarvis | **Generalist** agentic brain (web, files, shell/code, git, memory) | HTTP `/v1/chat/completions` :8000 (+ tiered CLI fallback) | **poll** (sync today; no push) | medium · owner-only | 3 | ✅ wired today as `jarvis_agent` |
| [Hermes](hermes.md) | [nousresearch/hermes-agent](https://github.com/nousresearch/hermes-agent) | **Messaging / comms** egress (telegram, slack, sms, email, discord, signal, …) + cron delivery | HTTP API `:8642/v1` (poll/SSE) — *not bound today*; only Telegram live | **poll / SSE** (no push on run API) | **high** | 1 | ⚠️ infra-ready; live transport needs API server enabled |
| [Open Claw](open_claw.md) | [openclaw/openclaw](https://github.com/openclaw/openclaw) | **Breadth** — 20+ live consumer channels + personal-AI hub w/ multi-agent routing | WebSocket gateway `ws://127.0.0.1:18789` | **push** (bidirectional WS, echoes `id`) | high | 2 | ⏸️ **deferred** (2026-06-22) — only install is `openclaw@2026.4.2` on the tailnet host, stale; BLOCKED slot until a current install |

## The connector-back model (why one inbound seam serves all three)

Only **Open Claw** can natively *push* a result back. Hermes and Jarvis are
**poll/SSE**. So the inbound seam `POST /agent/callback/<token>` is universal:

- **push-capable agents** (Open Claw) call it directly when a task finishes;
- **poll-only agents** (Hermes, Jarvis) are driven by a small EVE-side poller
  that, on completion, resolves the same correlation id through the same path.

Either way there is **one** code path for "a result came home" → correlation
store lookup → `try_announce` (total: speaks if a session is live, else
`QUEUED_NO_SESSION` + replay on next session) → Activity reply-feed.

See `docs/superpowers/specs/` for the full design and `docs/superpowers/plans/`
for the implementation plan.
