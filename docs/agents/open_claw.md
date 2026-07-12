# Open Claw — Agent Integration Target (EVE Agent Hub)

> **DECISION 2026-06-22 — DEFERRED (Phase 2 on hold).** Open Claw is not installed on the EVE box;
> the only install is on `the tailnet host` and is **`openclaw@2026.4.2`** (~2 months old, behind upstream).
> Open Claw's gateway protocol moves fast, so pinning the contract against this stale version would
> likely need redoing at integration time. The hub does not depend on it: it stays a **BLOCKED
> registry slot** (`delegate_registry.REGISTRY['open_claw'].enabled=False`), and wiring it later is
> one `DelegateSpec` row + a `skills/delegate_open_claw.md`, pinned against whatever version is live
> then (verify the frames against `packages/gateway-protocol/src/schema/frames.ts` on that version).
>
> **Status: SPEC'D, NOT WIRED.** Phase 2 integration target, AFTER Hermes.
> The contract below is **web-research only** (public repo + docs), so it is **pinnable** but not yet
> verified against a running instance. See [Integration recommendation / readiness](#integration-recommendation--readiness).

- **Repo:** https://github.com/openclaw/openclaw  (Node.js/TypeScript monorepo, pnpm)
- **Docs:** https://docs.openclaw.ai (protocol, gateway, configuration)
- **Researched:** 2026-06-21 (web-only; current public sources, not training memory)
- **One-liner:** Local-first, self-hosted personal-AI platform. A single long-running
  `openclaw gateway` process owns all channel connections and exposes one WebSocket control plane.

Legend used throughout: **[DOCUMENTED]** = stated in the repo/official docs; **[INFERRED]** =
typical-for-this-pattern / reasoned from the protocol, not explicitly confirmed. Do not treat
[INFERRED] items as a wiring contract until verified against a running instance.

---

## 1. Transport + endpoint

**[DOCUMENTED]**
- **Transport:** WebSocket. **Text frames carrying JSON payloads.** This is the single control
  plane *and* node transport — every client (CLI, web UI, macOS app, iOS/Android nodes, headless
  nodes) connects the same way and declares its role + scopes at handshake.
- **Default endpoint:** `ws://127.0.0.1:18789` (localhost-only by default).
- **Also present:**
  - **CLI** — `openclaw` binary, including `openclaw gateway` (runs the process) and
    `openclaw agent --message "..."` (sends a task; see §3). The CLI is itself a gateway WS client.
  - **HTTP surface** — hook endpoints exist (e.g. accept `x-openclaw-token` header; query-string
    tokens are explicitly rejected). The HTTP surface is **secondary**; the agent/task path is the
    WS gateway, not a REST "run agent" endpoint. **[INFERRED]** A full REST task API should not be
    assumed — treat WS as the integration path.

**How EVE connects + sends a task (the happy path):**
1. Open a WS connection to the gateway URL.
2. Receive a `connect.challenge` event (nonce).
3. Send a `connect` request declaring `role`, `scopes`, and `auth` (see §2).
4. On `hello-ok`, send an `agent` (or `chat.send`) request frame with a unique `id` (see §3).
5. Receive an immediate `accepted` ack, then stream events, then a final completion (see §5).

---

## 2. Auth scheme

**[DOCUMENTED]**
- **Handshake is challenge–response.** Gateway pushes `connect.challenge` `{ nonce, ts }`; client
  replies with a `connect` request carrying its credentials.
- **Token / bearer:** `gateway.auth.token` — bearer token, also accepted via
  `Authorization: Bearer ...`. Hook endpoints accept `x-openclaw-token`. Query-string tokens rejected.
- **Password:** `gateway.auth.password` (basic auth variant).
- **Device pairing / device-link:** the `connect.params.device` object supports
  `{ id, publicKey, signature, signedAt, nonce }` — i.e. signed device identity. On success the
  gateway returns a `deviceToken`. iOS uses App Attest + registration tokens bound to a specific
  gateway identity (prevents cross-gateway reuse).
- **Scopes (operator role):** `operator.read`, `operator.write`, `operator.admin`,
  `operator.approvals`, `operator.pairing`, `operator.talk.secrets`. EVE would need at least
  `operator.write` to send tasks (likely also `operator.read` to receive results).
- **TLS / pinning:** `gateway.tls` (server TLS), `gateway.remote.tlsFingerprint` (client-side
  fingerprint pinning for remote gateways). No explicit **mTLS** requirement documented; device
  signature provides client identity at the protocol layer.
- **Binding / external reachability:** **defaults to `127.0.0.1` (localhost-only)**. Binding to
  `0.0.0.0` / external is possible but requires explicit config; there is no open-by-default
  external bind. Control-plane writes are rate-limited (~3 req / 60s per `deviceId+clientIp`).

**Implication for EVE callback auth:** because Open Claw *can* be configured externally-reachable,
the token EVE uses is not protected by localhost trust alone. EVE must hold the gateway token as a
secret and, if the gateway is remote, pin `tlsFingerprint`. Treat the connection as crossing a
trust boundary even though Open Claw markets as local-first.

---

## 3. Task request contract (delegate a task over the gateway)

Generic envelope **[DOCUMENTED]**:
```json
{ "type": "req", "id": "<correlation-id>", "method": "<method>", "params": { } }
```
- `id` is the **client-chosen correlation id**, echoed back on the matching `res` (and used to tie
  async results to the request — this is the key field for EVE's async delegation).

**Primary method to delegate a task — `agent` [DOCUMENTED]:**
```json
{
  "type": "req",
  "id": "eve-req-001",
  "method": "agent",
  "params": {
    "agentId": "<optional target agent>",
    "sessionKey": "<optional explicit session>",
    "input": "<the task / prompt text>",
    "deliver": false,
    "bestEffortDeliver": false,
    "idempotencyKey": "<required for side-effecting retries>"
  }
}
```
- `input` is the task text. `deliver: true` makes Open Claw also push the reply out to a chat
  channel; for EVE-internal delegation, keep `deliver: false` and read the result off the socket.
- **Idempotency key is required** for side-effecting methods (`agent`, `send`) so retries dedupe;
  the gateway keeps a short-lived dedupe cache.

**Alternatives:** `chat.send` `{ sessionKey, text, idempotencyKey }` and `sessions.send`
`{ sessionKey, ... }` for session-scoped messaging. `tasks.cancel` exists to cancel a run.

**CLI equivalent [DOCUMENTED]:** `openclaw agent --message "<text>" [--agent <id>]
[--session-key <key>] [--deliver] [--json]`. With `--json` the CLI returns structured output
(including delivery status). Useful as a fallback transport if EVE shells out instead of speaking WS.

---

## 4. Specialty (Open Claw vs Hermes — drives EVE routing)

Both Open Claw and Hermes do multi-channel messaging, so that is **not** the discriminator.
What distinguishes Open Claw:

- **Breadth of channels (its standout strength):** WhatsApp, Telegram, Slack, Discord, Google Chat,
  Signal, iMessage, IRC, Microsoft Teams, Matrix, Feishu, LINE, Mattermost, Nextcloud Talk, Nostr,
  Synology Chat, Tlon, Twitch, Zalo, WeChat, QQ, WebChat — far wider than a typical messaging agent.
- **Personal-assistant platform breadth:** owns sessions, channels, tools, multi-agent routing, a
  live "Canvas" UI, and speak/listen on macOS/iOS/Android — a full personal-AI hub, not a single task tool.
- **Multi-agent routing + sandboxing:** can route to multiple configured agents (`agentId`) with
  session isolation.

**Routing heuristic for EVE:** delegate to Open Claw when the task is *"reach me / reach someone on
a real-world consumer messaging channel"* or *"act as a broad personal assistant across my devices
and channels."* Reserve Hermes for what Hermes is specialized at (Phase 1). When both could serve a
generic messaging task, prefer Hermes in Phase 1 (Open Claw isn't wired yet).

---

## 5. Callback capability (async connector-back) — KEY

**Answer: YES — the gateway is bidirectional and pushes results back over the same socket, and the
final response echoes the request `id` (correlation preserved). [DOCUMENTED]**

- **Two-stage agent result [DOCUMENTED]:**
  1. **Immediate ack** — `res`/event with `status: "accepted"`.
  2. **Streamed events** in between — `event` frames (see below).
  3. **Final completion** — `status: "ok" | "error"`, returned on the matching `res` frame
     (`{ "type":"res", "id":"eve-req-001", "ok":true, "payload":{...} }`).
- **Event (server-push) frame [DOCUMENTED]:**
  ```json
  { "type": "event", "event": "<name>", "payload": { }, "seq": 0, "stateVersion": 0 }
  ```
  Relevant event families: `agent`, `chat` (carries `deltaText` in protocol v4 + cumulative
  `message`), `session.message`, `session.operation`, `session.tool`. `seq` is monotonic per socket.
- **Correlation:** the **`id`** ties the final `res` to EVE's original request. For streamed
  events, the **`sessionKey`** identifies the owning session (use it to group streaming output to a
  delegated task). If EVE passes its own `sessionKey`, it can correlate streamed events too.
- **Delivery status:** when `deliver` was requested, results may include `result.deliveryStatus`
  ∈ `sent | suppressed | partial_failed | failed`.

This is true async connector-back: EVE sends a task, can drop the request, and collect the pushed
result/stream on the persistent socket keyed by `id`/`sessionKey`. **[INFERRED]** exact final-payload
field names (e.g. where the answer text lands) are not fully quoted in public docs — verify against a
running gateway / `packages/gateway-protocol/src/schema/frames.ts` before relying on field paths.

---

## 6. Trust tier (tool_policy recommendation)

**Recommendation: `risk: high`, `requires_confirmation: true`** (matching this repo's existing
high-risk convention used by `send_to_channel`, `confirm_send_text`, `create_invoice`).

Rationale:
- **Can message the outside world.** Open Claw can send to 20+ live consumer channels (WhatsApp,
  Signal, iMessage, Slack, …). A delegated task can produce real outbound messages to real people —
  the same reason `send_to_channel` is `high` here.
- **Externally reachable surface.** Although local-first by default, the gateway *can* bind beyond
  localhost, so the trust boundary is not guaranteed to be the local machine; the token is the only
  thing standing between a caller and outbound-message capability.
- **Broad agent + tool execution.** It runs agents with tools/sandboxing; the blast radius of a
  bad delegation is large.

Mitigations to encode in the registry entry: require confirmation before any `deliver: true`
delegation; default EVE→Open Claw calls to `deliver: false` (read result internally, let EVE decide
whether to surface/forward); store the gateway token as a secret; pin `tlsFingerprint` if remote.

---

## Integration recommendation / readiness

**Verdict: contract is PINNABLE → Open Claw can be a candidate-ready Phase 2 registry slot, NOT a
hard BLOCKED slot.** It does **not** block Phase 1 (Hermes) either way — it is explicitly Phase 2,
after Hermes.

Evidence the contract is pinnable from public sources:
- Transport, default endpoint, and handshake are documented (`ws://127.0.0.1:18789`,
  `connect.challenge` → `connect` → `hello-ok`).
- A concrete task-delegation method (`agent`) with params and an idempotency requirement is documented.
- Async connector-back is documented: bidirectional socket, two-stage ack→completion, `id`
  correlation echoed on the `res`, plus streaming `event` frames keyed by `sessionKey`.
- Auth (token/bearer, device-signature pairing, scopes, TLS pinning) is documented.

Remaining unknowns to close **before wiring** (mark these as the Phase-2 entry criteria, not Phase-1
blockers):
- Exact final-completion payload field paths for the `agent` method (verify vs
  `packages/gateway-protocol/src/schema/frames.ts` on a pinned version).
- Whether EVE connects as `operator` and the minimal scope set (`operator.write` + `operator.read`).
- Protocol version to pin (`hello-ok.protocol` was 4 in researched sources) — pin it; the protocol
  carries `minProtocol/maxProtocol` negotiation.
- Confirm behavior of `deliver:false` returns full result on-socket (so EVE never needs the HTTP/CLI path).

**Registry guidance:** spec the slot now with the §1–§6 contract above and a `status: candidate`
(or `pending-verify`) flag plus `risk: high`. Do **not** wire live calls until the four unknowns are
verified against a pinned local install. This keeps Hermes (Phase 1) shipping with zero dependency on
Open Claw, while Open Claw is ready to light up in Phase 2 without re-research.

---

### Sources
- https://github.com/openclaw/openclaw
- https://github.com/openclaw/openclaw/blob/main/docs/gateway/protocol.md
- https://github.com/openclaw/openclaw/blob/main/docs/gateway/configuration.md
- https://docs.openclaw.ai/gateway/protocol
- https://docs.openclaw.ai/tools/agent-send
- https://docs.openclaw.ai/gateway

---

## Talk-back (2026-07-01 — contract ready, `enabled=False`, install still Phase-2 blocked)

Registry row: `talkback="http"`. When OpenClaw lands on this box, a thin bridge (acpx CLI or a
ws client on `ws://127.0.0.1:18789`) relays its events as **EVE-shape JSON** to
`POST :8787/agent/a2a/<webhook_token>` — the same three payloads and gate as Jarvis (see
`docs/agents/jarvis.md` §Talk-back; contract-tested for the `open_claw` row in
`tests/test_talkback_contract.py`). Questions stage `resume_open_claw` approvals — never
execute. **To light up:** install OpenClaw, build the bridge against the then-current gateway
protocol, flip `REGISTRY["open_claw"].enabled=True` + `skills/delegate_open_claw.md`.
