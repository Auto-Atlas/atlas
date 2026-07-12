# Jarvis — the general agentic brain (DelegateRegistry target)

> **Status:** EXISTS today. EVE's voice loop already delegates to Jarvis via the
> `jarvis_agent` tool. This doc captures the interface contract so Jarvis can be
> folded into the uniform `DelegateRegistry` at **Phase 3** of the EVE Agent Hub
> (Hermes is Phase 1, Open Claw Phase 2). Jarvis is the **generalist** — contrast
> Hermes's messaging/comms specialty.

Source of truth in the repo:
- `agent_bridge.py` — the `jarvis_agent` FunctionSchema + tiered handler (READ).
- `openjarvis_client.py` / `openjarvis_capabilities.py` — HTTP client + capability map.
- `app/src/openjarvis/server/` — the live FastAPI server (`app.py`, `routes.py`, `api_routes.py`, `auth_middleware.py`).
- `app/src/openjarvis/a2a/` — a **standalone, currently-unmounted** A2A library.
- `tool_policy.py` — code-enforced trust/risk gating (`jarvis_agent` is in `OWNER_ONLY`).
- `skills/jarvis_agent.md` — skill guidance (sets `risk: medium`).
- `deploy/systemd/jarvis-server.service` — the OpenJarvis daemon unit (port 8000).

---

## 1. Transport + endpoint

There are **two** live surfaces today plus **one** unmounted library. They are not the same path.

### 1a. Today's delegation path — CLI subprocess, tiered (what `jarvis_agent` actually uses)

`handle_jarvis_agent` in `agent_bridge.py` does **not** call HTTP. It runs a
**tiered chain of brains** as subprocesses, tried in order until one returns a
non-empty result (`JARVIS_BRAIN_ORDER`, default `codex,glm,local`):

| Tier | Transport | Command (cwd = `JARVIS_WORKSPACE`, default `~/jarvis-workspace`) |
|------|-----------|------------------------------------------------------------------|
| `codex` | subprocess | `codex exec --skip-git-repo-check --sandbox workspace-write --output-last-message <f> <task>` |
| `glm` | subprocess | `claude -p <task> … --strict-mcp-config` with env pointed at Z.AI GLM-4.7 |
| `local` | subprocess | `jarvis ask --agent orchestrator --json <prompt>` → OpenJarvis orchestrator |
| `claude` | subprocess | `claude -p <task> …` (metered; opt-in only, NOT in default order) |

- Per-tier timeout `JARVIS_AGENT_TIMEOUT` (default **180s**). Worst case wall time
  is `len(BRAIN_ORDER) * timeout` (~9 min with 3 tiers) — the voice loop is
  expected to say "on it" first.
- Each tier counts as success **only** if it returned real text. Failures fall
  through with the reason logged; the result names which `brain` did the work.
  The `local` tier additionally injects identity (`SOUL.md`/`USER.md`) + an
  OpenJarvis capability map + task-relevant memory facts into the prompt.

### 1b. The OpenJarvis HTTP server — FastAPI on `:8000` (used today only for memory/channels side-calls)

`deploy/systemd/jarvis-server.service` runs `jarvis serve --port 8000 --agent
orchestrator` (`jarvis-server.service`, "OpenJarvis API server — local agent
brain"). Default base URL `JARVIS_AGENT_URL = http://127.0.0.1:8000`.
`create_app()` in `server/app.py` builds a **FastAPI** app. Relevant routes
(verified in `routes.py` + `api_routes.py`):

| Route | Method | Purpose |
|-------|--------|---------|
| `/v1/chat/completions` | POST | **OpenAI-compatible agent run.** With `agent` configured it calls `agent.run(input_text)` and runs the full internal tool loop. **This is the real HTTP equivalent of delegating a task.** |
| `/v1/agents` | GET/POST | List registered agent types / spawn a running agent |
| `/v1/agents/{id}/message` | POST | Send a message to a spawned agent (`{"message": "..."}`) |
| `/v1/agents/{id}` | DELETE | Kill a running agent |
| `/v1/memory/store`, `/v1/memory/search`, `/v1/memory/index` | POST | Persistent memory (used today by `openjarvis_client.py`) |
| `/v1/channels`, `/v1/channels/send` | GET/POST | Messaging channels |
| `/health`, `/v1/info` | GET | Liveness / metadata |

`openjarvis_client.py` only uses the memory + channels subset; it does **not**
drive task delegation over HTTP today.

### 1c. The A2A library — present but NOT wired in

`app/src/openjarvis/a2a/` implements the **Google A2A spec (JSON-RPC 2.0)**:
`A2AServer` exposes `GET /.well-known/agent.json` (discovery card) and
`POST /a2a/tasks` (`tasks/send` | `tasks/get` | `tasks/cancel`), with an
`A2AClient` + `A2AAgentTool` to call *external* A2A agents. **However, a grep of
`server/` and `cli/serve.py` shows `A2AServer` is never instantiated or mounted**
— it is dead-but-ready library code, not a live endpoint on `:8000`.

### Which transport for the uniform registry?

**Move to the HTTP surface (1b), specifically `POST /v1/chat/completions` against
`:8000`.** Rationale:
- The DelegateRegistry wants declarative `{transport, url, ...}` specs and a
  shared transport/timeout/retry/error-normalization layer. HTTP/JSON fits that;
  a bespoke per-tier subprocess chain does not.
- The CLI chain's value (multi-brain fallback, ~9-min blast time) is a *Jarvis
  internal concern* and should stay **inside** OpenJarvis behind the HTTP
  endpoint, not be re-implemented in the registry.
- A2A (1c) is the most spec-clean option (typed tasks, task ids, status polling)
  but is **not mounted** — adopting it is a real implementation task, not a
  config change. Recommendation: ship Phase 3 on `/v1/chat/completions`, and
  treat "mount A2AServer and switch the Jarvis spec to A2A" as a follow-up once
  the registry needs first-class task ids / async (see §5).

---

## 2. Auth scheme

- **CLI tiers (today):** no network auth — they are local subprocesses inheriting
  the parent env (the `glm` tier *strips* real-Anthropic creds and injects a Z.AI
  token from `~/.claude-local-glm/settings.json`).
- **HTTP `:8000` (today):** **localhost-trust by default.** `AuthMiddleware`
  (`auth_middleware.py`) only enforces `Authorization: Bearer <key>` on `/v1/*`
  and `/api/*` **when an `api_key` is configured** at app creation. `agent_bridge`
  / `openjarvis_client` read `JARVIS_AGENT_API_KEY` and send `Authorization:
  Bearer <key>` only if it is set; default empty ⇒ no auth, bound to `127.0.0.1`.
  `check_bind_safety()` refuses to bind a non-loopback host without a key.
- **A2A (unmounted):** `A2AServer(auth_token=...)` supports a constant-time
  bearer check (`secrets.compare_digest`) advertised on the agent card's
  `authentication: {"schemes": ["bearer"]}`. Unauthenticated when no token set.

**Registry value:** `auth: bearer`, token from `JARVIS_AGENT_API_KEY` (optional;
empty ⇒ localhost-trust). Recommend setting the key once the hub can hold a secret,
so the same connector works if `:8000` is ever exposed beyond loopback.

---

## 3. Task request contract

### `jarvis_agent` tool (what EVE delegates today)

```json
{
  "name": "jarvis_agent",
  "parameters": {
    "task": { "type": "string", "description": "stated fully and precisely in plain language" }
  },
  "required": ["task"]
}
```
Plain-language task in, single string. The handler returns:
```json
{ "ok": true, "brain": "codex|glm|local|claude",
  "result": "<text, clipped to JARVIS_AGENT_RESULT_CLIP=1500 chars>",
  "fallback_note": "earlier brains failed: …" }
```
or on total failure `{ "ok": false, "error": "every agent brain failed: …" }`.

### HTTP equivalent — `POST /v1/chat/completions`

OpenAI-shaped body (`{"model", "messages":[…], …}`); the server extracts the
input text and calls `agent.run(input_text)`. Response is an OpenAI
chat-completion object (`choices[0].message.content`, plus `usage`). This is the
route a registry connector should target.

### A2A task shape (`a2a/protocol.py`, if/when mounted)

`tasks/send` params:
```json
{ "message": { "role": "user", "parts": [{ "text": "<task>" }] } }
```
returns an `A2ATask` `{id, state, input, output, history, metadata}` where
`state ∈ {submitted, working, input-required, completed, canceled, failed}`.
A2A carries a real **`task_id`** — the missing piece in both the CLI and
`/v1/chat/completions` paths.

**Registry `request_schema`:** keep `{task: string}` as the uniform delegate
contract (the thin per-agent FunctionSchema EVE exposes), and have the Jarvis
connector adapt `{task}` → whichever transport body it targets
(`messages:[{role:"user",content:task}]` for `/v1/chat/completions`, or the A2A
`parts` form).

---

## 4. Specialty — GENERALIST

Jarvis is the catch-all agentic brain. Per the `jarvis_agent` schema and
`openjarvis_capabilities.py`, it can: **web search, read/write files, run shell
commands and Python code, git, HTTP requests, databases, persistent memory**,
plus schedule recurring/future tasks, run autonomous monitors, message
Telegram/Slack/Discord/Signal/WhatsApp, index/search documents, run workflows,
and connect Gmail/Obsidian/data sources. It also has its own internal sub-agent
registry (`/v1/agents`: `deep_research`, `morning_digest`, `monitor_operative`,
`orchestrator`, …).

**Routing rule for EVE:** delegate to Jarvis for *anything beyond quick
conversation* — research, lookups, file work, code, multi-step tasks, or anything
needing current/uncertain facts. Hermes is the narrow messaging specialist;
Jarvis is the fallback generalist. (Note: Jarvis *can* also message channels, so
the hub should prefer Hermes for pure comms to keep blast radius narrow.)

---

## 5. Callback capability — synchronous today

- **`jarvis_agent` today is strictly synchronous request/response.** The
  subprocess runs to completion (up to the per-tier timeout), the result is
  returned inline via `params.result_callback(...)`, clipped to ~1500 chars.
  **No task id, no push, no correlation — it blocks the tool call.**
- **`/v1/chat/completions`** is likewise synchronous (and has no correlation id).
- **`/v1/agents/{id}/message`** lets you address a long-lived spawned agent, but
  the reply is still the HTTP response — there's no server-initiated push.
- **A2A (`a2a/`)** is the closest thing to async: it assigns a **`task_id`** and
  exposes `tasks/get` for **polling** status. But in the bundled
  `_handle_task_send`, the handler runs **inline and synchronously** before the
  response returns (state goes `submitted → working → completed` within the one
  call). It publishes `A2A_TASK_RECEIVED` / `A2A_TASK_COMPLETED` on an internal
  `EventBus`, but **there is no outbound webhook / push-callback / correlation
  token** to a caller. So A2A gives you *task ids + poll*, not *push*.

**What EVE's connector-back needs vs. what exists:** the hub spec requires a
push "connector back" — `POST /agent/callback/<token>` on EVE's side, with a
**signed callback token tied to a correlation id** and a **total `announce()`**
that survives a callback arriving after the voice session ended. **None of that
exists on the Jarvis side.** To make Jarvis async-push:
1. EVE generates a correlation id + signed callback URL and passes it in the task.
2. Jarvis must learn to POST the result back to that URL on completion — this is
   **net-new work in OpenJarvis** (or a wrapper), not a config flip.

**Pragmatic Phase-3 recommendation:** fold Jarvis in as **synchronous** first
(`callback: false`) over `/v1/chat/completions`, since that's honestly what it is
today. Add async push later by either (a) teaching OpenJarvis to call EVE's
`/agent/callback/<token>`, or (b) mounting `A2AServer` and having the hub poll
`tasks/get` against a `task_id`. Do **not** claim async until one of those lands.

---

## 6. Trust tier

- Jarvis can run **shell/code/git locally** → **high local blast radius**, but it
  is **local/offline-capable** (the `local` tier needs no cloud).
- `tool_policy.py` already lists `jarvis_agent` in **`OWNER_ONLY`** — only the
  owner's recognized voice may invoke it, regardless of risk level
  (`tier_allows` short-circuits: non-owner ⇒ deny for any `OWNER_ONLY` tool).
- `skills/jarvis_agent.md` sets **`risk: medium`**, `requires_confirmation: false`.

**Reconciliation / recommendation:** these two are consistent and should be kept.
`OWNER_ONLY` is the real guard here (identity), and it dominates: a `medium` risk
on an owner-only tool means "the owner can run it without a per-call confirmation
prompt, but nobody else can run it at all." Given the high *local* blast radius
(arbitrary shell/code/git), do **not** drop below `medium`, and keep
`OWNER_ONLY`. If anything, the conservative move is `risk: high` — but that would
force confirmations on the owner for every delegation, which is poor UX for the
generalist fallback. **Keep `risk: medium` + `OWNER_ONLY`.** (Contrast Hermes,
which reaches the outside world and is high-trust on the *send* axis.)

---

## Integration recommendation — Phase 3 DelegateRegistry spec

Fold the existing `jarvis_agent` into the uniform `DelegateRegistry` with this
declarative spec (data, not code):

```python
{
  "name": "jarvis",
  "transport": "http",                       # move off the bespoke CLI chain
  "url": "http://127.0.0.1:8000/v1/chat/completions",
  "auth": "bearer",                          # JARVIS_AGENT_API_KEY; empty ⇒ localhost-trust
  "request_schema": {"task": "string"},      # thin uniform contract; connector adapts to OpenAI body
  "specialty": "generalist",                 # web/files/shell/code/git/http/db/memory/scheduling/channels
  "callback": False,                         # HONEST: synchronous today (see §5)
  "risk": "medium",                          # reconciles with skills/jarvis_agent.md + OWNER_ONLY
  "allow_redelegate": True                   # Jarvis has its own internal sub-agent registry (/v1/agents)
}
```

**Keep tiered-CLI or move to A2A/HTTP?**
- **Phase 3 (now): use HTTP (`/v1/chat/completions`).** It's already live,
  matches the registry's declarative-spec + shared-transport model, and the
  multi-brain CLI fallback stays *inside* OpenJarvis where it belongs (don't
  re-implement subprocess tiering in the hub).
- **Keep the CLI chain only as a fallback** behind the registry if the HTTP
  daemon is down — but prefer fixing the daemon's own resilience over carrying
  two delegation transports in the hub.
- **A2A is the right long-term home** (typed tasks, `task_id`, status). Adopt it
  only after `A2AServer` is actually mounted on `:8000` and (ideally) extended
  with a real outbound push to EVE's `/agent/callback/<token>`; at that point
  flip the spec to `transport: "a2a"`, `callback: True`, and carry a correlation
  id. Until then, `callback: False` is the honest value.
- `allow_redelegate: True` is justified — Jarvis legitimately spawns its own
  sub-agents (`deep_research`, `monitor_operative`, etc.) — but the hub should
  prefer routing pure-comms tasks to **Hermes** rather than letting the
  generalist message the outside world, to keep blast radius narrow.

---

## Talk-back (2026-07-01 — contract ready, `enabled=False`)

Registry row: `talkback="http"`. Jarvis's orchestrator talks back by POSTing **EVE-shape JSON**
to `POST http://127.0.0.1:8787/agent/a2a/<webhook_token>` with the per-task `callback_token`
EVE minted at delegation (`agent_tasks.create`). The three payloads (identical to Hermes's,
contract-tested in `tests/test_talkback_contract.py`):
```jsonc
{"correlation_id":"…","callback_token":"…","state":"completed","result":{"text":"…"}}
{"correlation_id":"…","callback_token":"…","state":"failed","result":{"text":"why"}}
{"correlation_id":"…","callback_token":"…","state":"input_required","question":"…"}
```
Questions STAGE a gated approval (`resume_jarvis`) — never execute. Alternatively, mounting the
in-repo `app/src/openjarvis/a2a/A2AServer` (or any native A2A push) works too: `handle_push`
accepts native `StreamResponse` JSON + `X-A2A-Notification-Token` header, and the
`talkback="a2a"` resume path is gated by `test_resume_a2a_input_required_contract`.
**To light up:** flip `REGISTRY["jarvis"].enabled=True` + add `skills/delegate_jarvis.md`
(risk parity asserted at boot), after verifying the OpenJarvis side posts the shape above.
