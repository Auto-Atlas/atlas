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

## Try it

With the shipped example in place, restart the voice loop and say
"roll two dice". Check what loaded (and what got rejected, loudly) with:

```bash
journalctl --user -u atlas-sidecar -b | grep -i plugin
```

Test your plugin the way core tools are tested — a file in `tests/` driving
the handler with a fake params object; run `.venv/bin/python -m pytest tests/ -q`.
`tests/test_plugin_loader.py` shows the pattern end to end.
