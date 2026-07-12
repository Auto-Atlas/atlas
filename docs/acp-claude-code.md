# Claude Code over ACP — no headless `claude -p`, no API key

The owner's directive (2026-07-03): wire ACP/acpx to Claude Code **without using
headless claude or the API**. This doc records what shipped, why the two
off-the-shelf adapters were rejected, and how to operate it.

## What shipped

```
EVE voice loop (jarvis_agent tool)
  └─ agent_bridge.py "acp" tier            (leads the default coding chain)
       └─ acpx --format quiet --agent …    (headless ACP client, npm i -g acpx)
            └─ acp_claude_code.py          (our ACP server, stdlib-only python)
                 └─ claude --bg …          (interactive-class BACKGROUND session,
                                            subscription OAuth, Max plan)
```

- `acp_claude_code.py` — ACP (Agent Client Protocol v1) server over stdio.
  Maps one ACP session to a chain of Claude Code background sessions:
  each turn is `claude --bg [-n eve-acp] [--resume <head>] "<prompt>"`;
  completion is polled from `claude agents --json --all` (state `done`);
  the reply is read from the session transcript JSONL under
  `~/.claude/projects/` (last real user message → trailing assistant text);
  cancel is `claude stop <id>`. `--resume` forks a new session id carrying
  full history — the adapter tracks the head. Probed live on Claude Code
  2.1.199 before building.
- `agent_bridge.py` — new `acp` brain. Default intent routing now leads coding
  tasks with `acp` (then codex, hermes, local); general queries still lead with
  hermes. `JARVIS_BRAIN_ORDER` pins the chain as before.
- Any ACP client works, not just acpx (Zed, JetBrains, …):
  `acpx --agent "python3 /path/to/acp_claude_code.py" "fix the tests"`.

## Why not the existing adapters

- `@agentclientprotocol/claude-agent-acp` (acpx's built-in `claude` agent):
  built on the Claude Agent SDK — **requires `ANTHROPIC_API_KEY`**, rejects
  Max/Pro OAuth (openclaw/openclaw#53456). Violates "no API".
- `harukitosa/claude-code-acp`: bridges the CLI but **shells out to
  `claude -p` one-shots** (`src/claude-runner.ts`) — the metered headless path
  (metered against the paid credit pool since 2026-06-15). Violates "no
  headless".

Background sessions (`--bg`) are interactive-class: they bill to the CLI's
logged-in subscription like a terminal session, and the adapter additionally
**strips `ANTHROPIC_API_KEY` from the child env** so billing can never
silently flip to the API (opt out with `EVE_ACP_ALLOW_API_KEY=1`).

## Operate

```bash
# one-time
npm install -g acpx

# direct (any repo/workspace)
acpx --format quiet --approve-all --cwd ~/jarvis-workspace \
  --agent "python3 ~/jarvis-sidecar/acp_claude_code.py" \
  sessions new --name mywork
acpx --format quiet --approve-all --cwd ~/jarvis-workspace \
  --agent "python3 ~/jarvis-sidecar/acp_claude_code.py" \
  prompt -s mywork "refactor the auth module"

# EVE: nothing to do — the acp tier is in the default coding chain.
# Pin/reorder: JARVIS_BRAIN_ORDER=acp,codex,local
# All knobs: see .env.example ("agent brains" section).
```

EVE's sessions are visible to the human as `eve-acp` in `claude agents`
(and stoppable there — that's the e-stop).

## Proven (2026-07-03)

- Suite green: 565 passed (baseline 555 + ACP coverage). Adapter tests drive
  the real adapter process over stdio against a fake `claude` shim (hermetic);
  they assert the resume chain, the `-p`-never-used contract, and the API-key
  strip.
- Live E2E: `acpx … prompt -s eve-e2e "…ACP_E2E_OK…"` → `ACP_E2E_OK`;
  a second acpx invocation recalled turn 1's content — cross-invocation
  session continuity (acpx keeps the adapter alive as queue owner; the
  adapter chains `--resume`).

## Talk-back + goal lock (added same day, the owner's asks)

**Claude talks back to EVE mid-task** — same contract as Hermes (spec §4.1):
`scripts/setup_acp_talkback.sh` writes `acp-talkback.mcp.json` (gitignored — it
embeds the webhook token) and prints the `JARVIS_ACP_TALKBACK_CLAUDE_ARGS` line
for `.env`. With it set, every delegation mints a per-task `correlation_id` +
`callback_token` (`agent_tasks.create`), the Claude session gets EVE's talkback
MCP server (`notify_eve` / `ask_eve` → live gate on `:8787/agent/a2a/<token>`),
and the bridge closes the row after the run (resolved+delivered on success,
failed+delivered on error — never replay noise). Proven live: the session
called `notify_eve` against the running bot mid-task.

**EVE gives Claude a /goal** — `JARVIS_ACP_GOAL_PREFIX=/goal` sends each task
as `/goal <task>`, so the session's goal Stop-hook blocks stopping until the
condition holds (Claude can't half-finish). The talk-back header trails the
task in this mode (a slash command must be the first token). Off by default —
it depends on the user's own `/goal` command (nothing owner-hardcoded); enabled
in the owner's `.env`. Goal-locked runs (Stop hooks included) run long: the tier
has its own `JARVIS_ACP_TIMEOUT` (default 900 s) and the adapter honors
`EVE_ACP_TURN_TIMEOUT_S` (cancels the turn with an honest timeout note).
Proven live: `/goal` + notify_eve + `GOAL_DONE` round-trip through the live bot.

Hard-won flag lore: `--allowedTools` is VARIADIC — space-separated tool names
swallow the following positional prompt (the session starts with an empty
prompt box). Use the comma form, and the adapter now places the prompt BEFORE
extra args as belt-and-braces.

## Known limitations

- `loadSession` is not implemented: if the adapter process dies (reboot), the
  next acpx call starts a fresh ACP session. The Claude-side history still
  exists under `~/.claude/projects/`; wiring `session/load` + persisted head
  ids is the natural next increment.
- One in-flight prompt per ACP session (acpx queues client-side anyway).
- `claude agents --json` is polled (default 1.5s) — no push signal exists yet.
