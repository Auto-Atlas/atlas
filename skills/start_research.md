---
tool: start_research
risk: medium
requires_confirmation: true
loads_on: call
catalog: Start a ResearchOS session by voice; confirms first.
---

# start_research

Kick off a ResearchOS research session on the inference box. Gather the GOAL (what to
research or buy) and optionally a budget in dollars.

The flow is code-gated: the FIRST call to start_research returns a preview —
nothing runs yet. Read the goal (and budget) back to the user, and only after
they clearly say yes, call start_research AGAIN with confirmed set to true to
actually start it. What you read back is exactly what gets researched.

Starting a session spends compute on the inference box — it's an action, not a read.
Never invent a goal; if it's vague, ask what to research. On success, repeat the
goal back and say results will be ready shortly and they can ask for the status
any time.

If the tool says ResearchOS is unreachable (the inference box may be down), tell the user
plainly — don't pretend it started.
