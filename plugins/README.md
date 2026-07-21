# Atlas plugins

Drop a directory in here and Atlas gains a tool — no core changes, no fork.
Every body (desktop, phone, watch) sees it, and it runs behind the exact same
safety gates as Atlas's built-in tools: duplicate-call suppression, trust
tiers, risk policy, spoken confirmation for risky actions, remote approval.

The shipped [`dice/`](dice/) plugin is the working reference — copy it.

## Layout — one directory, one tool

```
plugins/
  your_tool_name/
    plugin.py    ← the code: TOOLS = [plugin_tool(...)]
    skill.md     ← the manifest: policy frontmatter + guidance body
```

Ship a second directory for a second tool. The loader enforces one tool per
directory so the policy manifest can never drift from the code it governs.

## skill.md — the manifest (mandatory)

```markdown
---
tool: your_tool_name            # must equal TOOLS[0].name
risk: low                       # low | medium | high — MANDATORY, no default
requires_confirmation: false    # mandatory to state when risk: high
catalog: one line the model sees in every prompt — keep it short
---

Full behavioral guidance. Injected the first time the model uses the tool:
when to use it, argument semantics, how to phrase results aloud.
```

Rules the loader enforces (a violation rejects the plugin with a logged error;
Atlas still boots and other plugins still load):

- `risk:` must be explicit. Plugins never inherit a permissive default —
  no manifest, no gate declaration, no load.
- `risk: high` must also state `requires_confirmation:` explicitly. High-risk
  tools (money, messages, anything outward-facing) get a spoken draft
  read-back and a confirmed re-call before the handler ever runs, plus
  owner-tier and remote-approval gating from core policy.
- The tool name must be lowercase snake_case and must not collide with any
  existing tool, delegate, or other plugin — the loader refuses to shadow.
- A plugin's skill can add a tool, never redefine an existing skill.

## plugin.py — the code

```python
from plugin_loader import plugin_tool

async def handle_your_tool(params):
    value = params.arguments.get("some_arg")
    try:
        result = do_the_real_work(value)
    except SomeSpecificError as e:
        await params.result_callback({"ok": False, "error": f"real reason: {e}"})
        return
    await params.result_callback({"ok": True, "result": result})

TOOLS = [
    plugin_tool(
        name="your_tool_name",
        description="What the model reads to decide when to call this.",
        properties={"some_arg": {"type": "string", "description": "..."}},
        required=["some_arg"],
        handler=handle_your_tool,
        # requires_fields=("some_arg",)  # structural gate: policy blocks the
        #                                 # call before your handler if missing
    )
]
```

The contract:

- The handler is `async def`, receives pipecat `FunctionCallParams`, and calls
  `await params.result_callback({...})` **exactly once**. There is no return
  value — the dict is the tool's entire output.
- Success is `{"ok": True, ...}`; failure is `{"ok": False, "error": "why"}`.
  The model speaks errors aloud, so make them human sentences. Never return an
  empty success when the real work failed — Atlas's house rule is fail LOUD.
- Read configuration lazily inside the handler (`os.getenv` at call time, not
  import time), and when a key is missing, fail with an error that names it:
  `{"ok": False, "error": "MY_SERVICE_TOKEN not set in .env"}`.
- Keep heavy imports inside the handler function. Your module is imported at
  boot on every body, including ones that will never call your tool.
- Talking to a service on this machine with a secret token? Refuse non-loopback
  URLs like `invoice_tool.py` does — never send a local bearer off-box.

## Two plugin shapes

**Tool-only** — the common case: `plugin.py` + `skill.md`, nothing else.
Atlas gains one gated tool. [`dice/`](dice/) is the reference.

**Tool + service** — for a capability that needs its own long-running
process (a phone line, a webhook listener, a bridge to an external system).
[`phone_agent/`](phone_agent/) is the reference. The extra rules:

- The service ships as `service.py` in the plugin directory and runs as its
  **own systemd unit** (template in `deploy/systemd/atlas-<name>.service`) —
  never inside the voice loop. A crashing service must not take Atlas down,
  and restarting Atlas must not drop calls/requests mid-flight.
- Config and secrets live **outside the repo** (`~/.config/atlas-<name>/`,
  chmod 600), loaded via `EnvironmentFile`. Ship a fully commented
  `*.example.*` copy in the plugin directory with obviously-fake values.
- The service is **fail-closed at boot**: missing or half-valid config means
  `exit 1` with the reason in the journal — never a guessed default, never a
  service that answers customers with a broken setup.
- The plugin's tool is the **owner's window into the service** — status and
  reads by default. It probes over loopback only, and its errors are human
  sentences that name the fix (`systemctl --user status atlas-<name>`).
  A tool that *commands* the service is `risk: high` + confirmation.
- **The sandbox rule (the big one):** anything the service exposes to the
  outside world — callers, webhook senders, strangers — gets NO Atlas tools
  and NO resident persona. Build its prompt and knowledge self-contained
  from the service's own config. The resident persona carries the owner's
  private context, and the tool registry is for the verified owner only.
  (The phone agent's first live call leaked the owner's nickname and
  role-played having tools before this rule existed. Don't relearn it.)
- Fail LOUD end to end: an outward-facing failure is spoken/returned to the
  user AND lands in the journal at ERROR; a captured message or request is
  never dropped silently — on processing failure, write a fallback record
  pointing at the raw data.
- Tests cover both halves: import `service.py` in-process with a stubbed
  env to unit-test its pure logic, prove the fail-closed boot contract in a
  subprocess, and drive the tool handler against a real local server —
  `tests/test_phone_agent_plugin.py` shows all three patterns.

## Author checklist

1. Copy `dice/` (tool-only) or `phone_agent/` (tool + service).
2. `skill.md`: explicit `risk:`; `requires_confirmation:` stated if high;
   one short `catalog:` line; body says when to use it and how to speak
   results.
3. `plugin.py`: one `plugin_tool()`, `async` handler, `result_callback`
   exactly once, `{"ok": False, "error": "human sentence"}` on failure,
   config read lazily, heavy imports inside the handler, loopback-only for
   local secrets.
4. Service (if any): rules above, plus a README in the plugin directory
   covering setup, operating, and the safety design.
5. Tests in `tests/`, then `.venv/bin/python -m pytest tests/ -q`.
6. Restart the voice loop and check
   `journalctl --user -u atlas-sidecar -b | grep -i plugin` — your plugin
   loaded, or was rejected with the reason.

## Try it

With the shipped example in place, restart the voice loop and say
"roll two dice". Check what loaded (and what got rejected, loudly) with:

```bash
journalctl --user -u atlas-sidecar -b | grep -i plugin
```

Test your plugin the way core tools are tested — a file in `tests/` driving
the handler with a fake params object; run `.venv/bin/python -m pytest tests/ -q`.
`tests/test_plugin_loader.py` shows the pattern end to end.
