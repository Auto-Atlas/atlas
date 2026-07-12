# Atlas documentation

Start with the repo-root **[README](../README.md)** and
**[SETUP-GUIDE](../SETUP-GUIDE.md)** — everything here is the layer below
them.

## Architecture & design

- **[DESIGN.md](DESIGN.md)** — how the whole assistant fits together: voice
  loops, tool gate, trust tiers, agent hub.
- **[seamless/specs/](seamless/specs/)** — the wire-level specs: approval
  model, event envelope, conversation, memory, identity, device credentials.
- **[glasses-endpoint-contract.md](glasses-endpoint-contract.md)** — the
  contract between the server and the smart-glasses bridge.

## Integrations & setup guides

- **[INTEGRATIONS.md](INTEGRATIONS.md)** — the full integration catalog.
- **[google-calendar.md](google-calendar.md)** /
  **[calendar-write.md](calendar-write.md)** — calendar read and write paths
  (native OAuth).
- **[acp-claude-code.md](acp-claude-code.md)** — delegating coding work to a
  Claude Code agent over ACP.
- **[mac-desktop-install.md](mac-desktop-install.md)** — the desktop app on
  macOS against a remote Atlas server.
- **[eve-wireguard-transport.md](eve-wireguard-transport.md)** — voice
  transport over WireGuard instead of Tailscale.

## Voice at scale

- **[EVE-VOICE-DAILY-INTEGRATION.md](EVE-VOICE-DAILY-INTEGRATION.md)**,
  **[EVE-VOICE-LIVEKIT-INTEGRATION.md](EVE-VOICE-LIVEKIT-INTEGRATION.md)**,
  **[EVE-VOICE-MASS-SCALE.md](EVE-VOICE-MASS-SCALE.md)** — opt-in
  LiveKit/Daily transports for multi-room, mass-scale voice.

## Agents

- **[agents/](agents/)** — one page per registered specialist agent (what it
  does, how talk-back works, how to add your own).

## Upstream

- **[OPENJARVIS.md](OPENJARVIS.md)** — notes on the vendored OpenJarvis fork
  under `app/` and how Atlas relates to upstream.

> Naming note: many docs and env vars still carry the assistant's previous
> working name (EVE) and its predecessor (JARVIS). New configuration should
> use the `ATLAS_*` env names — the legacy names keep working (see
> `atlas_env.py`).
