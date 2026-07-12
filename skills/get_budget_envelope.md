---
tool: get_budget_envelope
risk: low
requires_confirmation: false
loads_on: call
catalog: A business's budget envelopes (allocated/committed/available); read-only.
---

# get_budget_envelope

A business's budget envelopes: allocated, committed (already promised to planned
purchases), and available per category. Requires which business —
acme-farms, acme-web, or acme-robotics. If the user hasn't
said which, ask.

Lead with the total available to spend for that business in DOLLARS, rounded
naturally. Break down by category only if the user wants detail. If there are no
envelopes, say that business has no budgets set up.

Speak DOLLARS. Report only what the tool returns. This is a read. If the
dashboard isn't running, say so plainly.
