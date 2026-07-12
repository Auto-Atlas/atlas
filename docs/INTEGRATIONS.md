# EVE integrations map — the one file that links everything

Unified on `main` (2026-07-03). Every client, every backend surface, every
transport, and where its spec/goal lives. If a new app or integration doesn't
fit this map, the map is wrong — fix the map in the same PR.

## The one contract

Every client speaks **HTTPS to a configurable base URL + pairing token**
(`approval_api.py`, systemd unit `atlas-approval-api.service`). No client may
assume tailnet/LAN/tunnel — transports are interchangeable
(`docs/eve-wireguard-transport.md`). Voice is WebRTC to `phone_bot.py`.
**Deploy lesson**: backend changes need
`systemctl --user restart atlas-approval-api` (and `atlas-sidecar.service` for
bot/voice-loop changes) — commits alone don't reload running services.

## Clients

| Client | Where | Status | Spec / goal |
|---|---|---|---|
| Desktop app (Tauri 2, React 19) | `app/frontend` (+`src-tauri`) | **shipped** | Sim tab next: `docs/prompts/2026-07-02-eve-sim-gui-GOAL.md` (+ audit `2026-07-03-sim-gui-spec-AUDIT.md`) |
| Android phone (Kotlin/Compose) | `eve-app/android:app` | **shipped** (130 tests) | backlog memory `eve-android-production-backlog` |
| Wear OS watch | `eve-app/android:wear` (to build) | goal | `docs/prompts/2026-07-03-eve-wearos-watch-GOAL.md` |
| iPhone + **iPad** (Swift/SwiftUI, universal) | `eve-app/ios` (to build) | goal | `docs/prompts/2026-07-03-eve-ios-swift-GOAL.md` |
| Apple Watch (watchOS) | `eve-app/ios` watch target (to build) | goal | `docs/prompts/2026-07-03-eve-watchos-watch-GOAL.md` |
| **Mac desktop** (same Swift app) | `eve-app/ios` — Designed-for-iPad first, native later | goal (rider on iOS goal) | iOS goal §platforms |
| Phone web (PWA-era) | `app/frontend` `phone.html` | shipped, superseded by native apps | — |

Shared rules for ALL clients: hold-to-approve (never one-tap approve, deny may
be one-tap + confirm) · trust line ("Requested by") · reduced-motion honored ·
demo mode is a labeled feature, not a mock · watches ride the phone gateway.

## Backend surfaces (what the apps consume)

| Surface | Endpoint / unit | Notes |
|---|---|---|
| Approvals | `/v1/approvals` (+`/approve`,`/deny`) | single source of truth; watch bridges relay, never store |
| Status / feed / today | `/v1/status`, `/v1/activity/feed`, `/v1/today`, `/v1/stream` (SSE) | initiative cards render visually (visual-first rule) |
| Skills | `/v1/skills`, `/v1/skills/{tool}/feed`, `/v1/skills/feed` | live per-request; feed modes: use-now / prime |
| Identity + voice enroll | `/v1/identity`, `/v1/enroll` | onboarding wizards (Android shipped; iOS goal §7) |
| Push | `/v1/push/register` | FCM shipped; APNs = iOS goal §8 (small sidecar change) |
| Voice | `phone_bot.py` WebRTC (:8788 loopback; TLS via serve) | Android `VoiceController` is the reference client |
| Memory | `/v1/memory` | wiki-backed (`JARVIS_MEMORY_PAGE`) |
| Health | `/v1/health` | installer/`eve doctor` gate |

## Agent + robot integrations (server side)

| Integration | Where | Doc |
|---|---|---|
| A2A fabric + talk-back (Hermes ↔ EVE) | `a2a_fabric.py`, `eve_talkback_mcp.py`, `:8787/agent/a2a/<token>` | `docs/agents/hermes.md`, talk-back spec |
| Standing agent link (Hermes → EVE, unsolicited + cron) | `a2a_fabric.handle_link` (same doorway, `link_key` shape), `message_eve` MCP tool; pair/rotate with `scripts/link_pair.py` | `.claude/skills/a2a-talkback/SKILL.md` §Standing link, `deploy/hermes-skills/eve-link/` |
| ACP → Claude Code (no `-p`, no API key) | `acp_claude_code.py` + `agent_bridge.py` "acp" brain (+`/goal` lock, talk-back) | `docs/acp-claude-code.md` |
| Brain chain | `agent_bridge.py` (acp → codex → hermes → local; glm/claude opt-in) | header comment is canonical |
| Embodiment (sim + camera + motion) | `~/eve-embodiment` (embody MCP/HTTP :8930), sidecar `embodiment_tool.py` (`EVE_EMBODIMENT=1`) | `eve-embodiment/README.md`, wireup handoff |
| OAK-D camera | Jetson host daemon :8090 → `EMBODY_OAKD_URL` | wireup handoff item 7 (staged; tailscale login pending) |
| Calendar (native OAuth read/write) | `google_calendar_native.py`, `calendar_watch.py` | `docs/google-calendar.md` |

## Transports (sellable vs in-house)

`docs/eve-wireguard-transport.md` — Topology A hosted relay
(VPS → WireGuard → customer's EVE box; app sees plain HTTPS), Topology B direct
WG profile; canonical VPS recipe = the `/setup-wireguard` command. Tailscale is
the owner's in-house transport only. Productization umbrella:
`docs/prompts/2026-07-02-eve-productization-HANDOFF.md`.

## Config

Every knob lives in `.env` (documented in `.env.example`). Product rule:
nothing owner-hardcoded — owner-specific values are settings with empty
defaults (`customer.yaml` is the productization vehicle).
