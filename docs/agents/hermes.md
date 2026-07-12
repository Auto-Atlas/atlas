# Hermes Agent — Integration Target for EVE Agent Hub

> Status: **researched + grounded against the running local install** (2026-06-21).
> Repo: [`nousresearch/hermes-agent`](https://github.com/NousResearch/hermes-agent) (the local checkout's `origin`/`upstream` both point here — the running install **is** this repo).
> Local install: `~/obsidian-vault-second-run/hermes-agent/` · Data home: `~/.hermes/`
> Running as systemd user service `hermes-gateway.service`:
> `…/hermes-agent/venv/bin/python -m hermes_cli.main gateway run` (WorkingDirectory `~/.hermes`, `HERMES_HOME=~/.hermes`, `Restart=always`).

Hermes is Nous Research's self-improving agent, but for EVE's purposes its relevant face is the **gateway**: a single long-running process that bridges an LLM agent to messaging platforms (Telegram, Discord, Slack, WhatsApp, Signal, SMS, …) and exposes optional HTTP surfaces. EVE will delegate a task and (ideally) receive an async result.

---

## 1. Transport + Endpoint — how you send Hermes a task

Hermes exposes **three** distinct surfaces. Which one applies depends entirely on what is *enabled* in `~/.hermes/config.yaml` + `~/.hermes/.env`.

### A. Messaging platforms (the only one ENABLED right now)
The gateway connects to chat platforms and treats inbound messages as agent prompts. **As currently running, only Telegram is connected.** Evidence:
- `~/.hermes/gateway_state.json`: `"platforms":{"telegram":{"state":"connected"}}` — Telegram is the *only* connected platform.
- `~/.hermes/.env` (4 lines total) defines only `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_USERS`, `TELEGRAM_HOME_CHANNEL`, `OBSIDIAN_VAULT_PATH`.
- `~/.hermes/channel_directory.json` lists ~25 platform buckets (discord, slack, signal, sms, email, matrix, teams, …) but **all are empty except telegram**, which has one DM peer: `{"id":"<telegram-user-id>","name":"the owner","type":"dm"}`.

To delegate via this path today: **send a Telegram message to the bot** (from an allowed user / the home channel). The message text is the task. Hermes runs the agent and replies in-thread.

### B. OpenAI-compatible HTTP API server (available, NOT currently bound)
Source: `gateway/platforms/api_server.py`. This is the cleanest programmatic surface and the one EVE should prefer if enabled.
- Default bind: **`127.0.0.1:8642`**, base path `/v1` (`DEFAULT_HOST="127.0.0.1"`, `DEFAULT_PORT=8642`).
- Endpoints:
  - `POST /v1/chat/completions` — OpenAI Chat Completions (stateless; optional session continuity via `X-Hermes-Session-Id`).
  - `POST /v1/responses` / `GET|DELETE /v1/responses/{id}` — OpenAI Responses API (stateful via `previous_response_id`).
  - **`POST /v1/runs` → returns `run_id` immediately with HTTP `202`** (async job start).
  - `GET /v1/runs/{run_id}` — poll run status.
  - `GET /v1/runs/{run_id}/events` — **SSE stream** of structured lifecycle events (`run.started`, `assistant.delta`, `tool.started/completed/failed`, `assistant.completed`, `run.completed`, `error`, `done`).
  - `POST /v1/runs/{run_id}/approval` · `POST /v1/runs/{run_id}/stop` — resolve approval / interrupt.
  - `GET /v1/models`, `GET /v1/capabilities`, `GET /health`, `GET /health/detailed`, plus `/api/sessions/*` CRUD + fork.
- **Not listening as of this audit.** `ss -tlnp` shows assorted local python ports but **no 8642 and no 8644**. The adapter exists in code but is not enabled in the running config (no `platforms.api_server` block / no `API_SERVER_KEY`). EVE cannot use it until it is turned on.

### C. Generic inbound webhook adapter (available, NOT currently bound)
Source: `gateway/platforms/webhook.py`. Default bind **`0.0.0.0:8644`**. Receives webhook POSTs (GitHub/GitLab/Stripe/monitoring/inter-agent pings), HMAC-validates them, turns the payload into an agent prompt, and **routes the response to a configured `deliver` target** (telegram, slack, github_comment, another platform, …). Configured under `platforms.webhook.extra.routes` in `config.yaml`. Also not currently bound.

> **Bottom line:** Today, the only live transport is **Telegram messaging**. The HTTP `/v1/runs` async API and the webhook adapter are first-class features in this exact codebase but must be explicitly enabled before EVE can call them.

---

## 2. Auth scheme

| Surface | Auth |
|---|---|
| **HTTP API server (8642)** | **Bearer token.** `Authorization: Bearer <API_SERVER_KEY>`, constant-time compared (`hmac.compare_digest`). The server **refuses to start without `API_SERVER_KEY`** set (env or `platforms.api_server.extra.key`). Optional headers `X-Hermes-Session-Id` (continue a session) and `X-Hermes-Session-Key` (long-term memory scope) are gated on the same key. CORS allows `Authorization, Content-Type, Idempotency-Key`. |
| **Webhook adapter (8644)** | **Per-route HMAC secret** (required at startup; `"INSECURE_NO_AUTH"` bypass exists for testing only). Signature validated against the request body. |
| **Messaging (Telegram, etc.)** | **Platform-native + allowlist.** Bot token authenticates Hermes to Telegram; inbound senders are filtered by `TELEGRAM_ALLOWED_USERS` / allowlist config. No EVE-presentable token; EVE would have to *be* an allowed Telegram peer. |
| **Pairing / auth.json** | `~/.hermes/pairing/` and `~/.hermes/platforms/pairing/` exist but are **empty** — no device-pairing in use. `~/.hermes/auth.json` holds **LLM provider OAuth creds** (openai-codex tokens), i.e. *outbound model auth*, **not** an inbound caller-auth scheme. Don't model EVE auth on it. |

Net: for programmatic delegation, auth is a **localhost bearer token** (API server) or **per-route HMAC** (webhook). Both are localhost/loopback-friendly.

---

## 3. Task request contract (what EVE sends)

### Preferred: `POST /v1/runs` (async, returns `run_id`)
```jsonc
// POST http://127.0.0.1:8642/v1/runs
// Headers: Authorization: Bearer <API_SERVER_KEY>
//          Content-Type: application/json
//          (optional) X-Hermes-Session-Id: <id to continue>
//          (optional) X-Hermes-Session-Key: <stable memory scope>
{
  "input": "Message the owner on Telegram that the deploy finished.",   // REQUIRED: string OR array of {role,content} msgs
  "instructions": "optional system/ephemeral prompt (string)",        // optional
  "previous_response_id": "resp_…",                                   // optional, continue prior response
  "conversation_history": [ {"role":"user","content":"…"} ],          // optional, explicit history (takes precedence)
  "session_id": "optional-caller-session-id"                          // optional; defaults to the generated run_id
}
// → 202 { "run_id": "run_<hex>", ... }   then stream GET /v1/runs/{run_id}/events  (or poll GET /v1/runs/{run_id})
```
Only `input` is strictly required. The server generates `run_id = "run_<uuid hex>"` and uses `session_id` (or the run_id) as the correlation/session handle.

### Synchronous alternative: `POST /v1/chat/completions`
Standard OpenAI Chat Completions body (`{"model":"hermes-agent","messages":[…]}`). Blocks until the agent finishes; simplest for short request/response delegations.

### Messaging path (today's only live option)
Send a chat message to the connected platform (Telegram bot). The **message text is the task**; reply comes back in the same chat/thread. Correlation = the chat/thread id (`channel_directory.json` keys peers by platform + id + thread_id).

### Webhook path
`POST http://<host>:8644/<route>` with an HMAC-signed JSON body; the route's `prompt` template renders the body into the agent prompt. `deliver_only: true` routes skip the LLM entirely and deliver the rendered template verbatim (sub-second, zero token cost) — useful for pings.

---

## 4. Specialty — what Hermes is BEST at

**Multi-platform messaging / comms gateway.** Confirmed by README ("Lives where you do: Telegram, Discord, Slack, WhatsApp, Signal, and CLI — all from a single gateway process") and by the platform adapters present in `gateway/platforms/`: telegram, discord(ish), slack, signal, sms, whatsapp, matrix, mattermost, email, dingtalk, feishu, wecom, weixin, bluebubbles, qqbot, msgraph_webhook, line, irc, ntfy, teams, google_chat, and the api_server/webhook adapters.

Secondary strengths relevant to routing: a **built-in cron scheduler** (`cron/scheduler.py`, `cron/jobs.py`) with delivery to any platform (daily reports, nightly jobs), a kanban/goals task system, skills, and a long-term memory model of the user.

**EVE routing implication:** route to Hermes when the task is *"send / deliver / notify / message someone on an external platform"* or *"schedule a recurring delivery."* It is the comms egress specialist. For pure local compute/coding, EVE should keep that itself or route elsewhere.

---

## 5. Callback capability — THE KEY QUESTION

**Yes, Hermes can push results back — but the push is to a *messaging/delivery target*, not a generic caller-supplied callback URL on the `/v1/runs` API.** Be precise about which surface:

- **Messaging + cron + webhook surfaces: genuine outbound push.** The gateway's whole design is "agent produces output → `deliver` it to a platform." `cron/jobs.py` takes a `deliver` field (`"origin"|"local"|"telegram"|…`) and pushes job output to that target unattended; it even tracks `last_delivery_error` separately from agent errors. The webhook adapter (`gateway/platforms/webhook.py`) routes each response to a configured `deliver` / `deliver_extra` (e.g. `chat_id`, `telegram`, `github_comment`). So Hermes **can** initiate an outbound message containing a result — including back into a chat EVE listens on. **Correlation id:** webhook sessions are keyed `webhook:{route}:{delivery_id}`; chat replies carry the originating chat/thread id.
- **`POST /v1/runs` async API: NOT a push-callback.** I found **no caller-supplied `callback_url` / `completion_webhook` / outbound POST-on-completion** in `api_server.py`. Async results are delivered by the caller **subscribing to `GET /v1/runs/{run_id}/events` (SSE)** or **polling `GET /v1/runs/{run_id}`**. The `add_done_callback` / `*_callback` symbols in that file are *in-process asyncio callbacks* feeding the SSE queue — not network callbacks to EVE. It **does** echo a stable correlation id (`run_id`, plus `session_id`) on every event, so correlation is solid.
- `~/.hermes/hooks/` exists but is **empty**; `hooks: {}` in config — no user-defined completion hooks wired today.

**Honest summary of the callback finding:** There is **no generic "POST my result to EVE's URL when done" mechanism on the HTTP run API.** Async result retrieval there is **SSE-subscribe or poll** (both carry `run_id`/`session_id` correlation). A *true* push-back is achievable only by (a) the webhook/cron `deliver` mechanism aimed at a channel EVE monitors, or (b) EVE giving Hermes a task whose completion *is* "message EVE on platform X." For a clean API-driven integration, **treat Hermes as poll/stream, not push.**

---

## 6. Trust tier — recommended tool_policy risk level

**Recommendation: HIGH risk.**

Rationale: Hermes's core competency is **sending messages to the outside world** (Telegram/Slack/SMS/email/etc.) and it can run **unattended on a schedule** (cron). Driven by EVE without a human in the loop, that is a direct **phishing / social-engineering / data-exfiltration vector** — a compromised or mis-prompted EVE could make Hermes message arbitrary contacts or leak content outbound. The blast radius is external and hard to recall once a message is sent. The local install already reflects caution: `approvals.mode: manual`, `cron_mode: deny`, `security.redact_secrets: true`, Tirith policy enabled, and a Telegram allowlist. EVE's `delegate_hermes` should inherit that posture: **require confirmation for any outbound-send task**, and constrain recipients to an allowlist.

---

## Integration recommendation for EVE

**Push vs poll:** Given the finding in §5, EVE's `delegate_hermes` should **NOT** assume push-callback on the HTTP API. Use one of:
1. **Preferred (if API server is enabled):** `POST /v1/runs` → get `run_id` → **subscribe to the `/v1/runs/{run_id}/events` SSE stream** for near-real-time async results, with **poll `GET /v1/runs/{run_id}` as the fallback** if the stream drops. Correlate on `run_id` (+ `session_id`).
2. **Fallback / today's reality:** the HTTP surfaces are **not currently bound** and only **Telegram** is live. Until `API_SERVER_KEY` + `platforms.api_server` are enabled, EVE can only delegate via a messaging channel and must **poll the reply channel** (or be an allowed peer that receives the reply). **Recommend poll-mode as the safe default.**

**Provisional `DelegateRegistry` spec for `delegate_hermes`:**
```jsonc
{
  "name": "hermes",
  "specialty": "multi-platform-messaging-and-comms-egress",   // telegram/slack/sms/email/discord/signal + scheduled delivery
  "transport": "http",                                         // POST http://127.0.0.1:8642/v1/runs  (when enabled)
  "auth": { "type": "bearer", "header": "Authorization", "secret_ref": "HERMES_API_SERVER_KEY" },
  "request_schema": {                                          // body for POST /v1/runs
    "input": "string (required) — the task / message",
    "instructions": "string (optional system prompt)",
    "session_id": "string (optional correlation/session id)"
  },
  "correlation_id_field": "run_id",                            // also session_id echoed on every event
  "callback": {
    "mode": "poll",                                            // NO caller-supplied callback URL on the run API
    "stream": "GET /v1/runs/{run_id}/events (SSE)",            // preferred when API server is up
    "poll": "GET /v1/runs/{run_id}",                           // fallback / default today
    "push_via_delivery": true                                  // alt: task Hermes to `deliver` result to a channel EVE watches
  },
  "risk": "high",                                              // outbound external messaging; require confirmation + recipient allowlist
  "enabled_today": false,                                      // only Telegram messaging is live; HTTP API/webhook not bound yet
  "enable_steps": "set API_SERVER_KEY + platforms.api_server.extra in ~/.hermes/config.yaml, restart hermes-gateway.service"
}
```

### Redaction note
All secrets are redacted here. `~/.hermes/config.yaml`, `~/.hermes/.env`, and `~/.hermes/auth.json` contain real tokens (Telegram bot token, provider OAuth) — only **key names and shapes** are reproduced above, never values.

---

## Talk-back (2026-07-01 — LIVE mechanism)

Hermes talks back to EVE mid-task via **EVE's Talkback MCP server** (`eve_talkback_mcp.py`,
registry row `talkback="mcp"`). Setup: `scripts/setup_hermes_talkback.sh` registers the server
in `~/.hermes/config.yaml` (`hermes mcp add eve …`); raise that server's `timeout:` to `900` so
`ask_eve` can block. Each delegation's task text carries a fenced header with the task's
`correlation_id` + `callback_token` (scoped to that one task, expires with it).

Tools Hermes calls during a run:
- `notify_eve(correlation_id, callback_token, kind, text)` — kind ∈ progress|result|blocker.
  **Non-terminal**: delivered/broadcast immediately, never resolves the task (the adapter's
  terminal A2A event is the single terminalizer).
- `ask_eve(correlation_id, callback_token, question)` — **blocks** until the owner answers
  through EVE's gate (voice `resume_delegate`) or `EVE_TALKBACK_ASK_WAIT_S` (840 s) elapses.
  The question is STAGED as a gated approval + actively notified — never executed.

Wire: both tools POST EVE-shape JSON to `POST :8787/agent/a2a/<webhook_token>`:
```jsonc
{"correlation_id": "…", "callback_token": "…", "state": "working",           // notify
 "kind": "progress|result|blocker", "result": {"text": "…"}}
{"correlation_id": "…", "callback_token": "…", "state": "input_required",    // ask
 "question": "…"}                       // -> {"ok":true,"staged":true,"question_id":"…"}
// answers: POST …/answer {"correlation_id","question_id"} + X-EVE-Callback-Token header
```
Terminal states arrive natively from the A2A adapter (`eve-a2a-hermes.service`, :8790,
`X-EVE-A2A-Key` auth): completed → EVE speaks the result; failed → EVE speaks the blocker.
