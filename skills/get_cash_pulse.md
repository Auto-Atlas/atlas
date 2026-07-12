---
tool: get_cash_pulse
risk: low
requires_confirmation: false
loads_on: call
catalog: Weekly cash pulse per business; read-only.
---

# get_cash_pulse

The weekly money picture from the books. No arguments = the current week; pass a
week number and/or year for a specific one.

Speak DOLLARS, never cents — the tool already converts. Give the net cash per
business, rounded naturally ("about seven hundred thirty dollars", not
"$734.00"), then the year-to-date net. Only bring up the gap to a million dollars
if the user asks about the goal.

Report only what the tool returns. If a number isn't in the result, don't make
one up — say you don't have it. Nothing here sends or changes anything; it's a
read.
