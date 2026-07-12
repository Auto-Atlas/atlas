---
tool: get_wealth_summary
risk: low
requires_confirmation: false
loads_on: call
catalog: Wealth OS cash pulse — YTD net, per-business, $1M gap; read-only.
---

# get_wealth_summary

The money picture from the Wealth OS dashboard. No arguments = the current week;
pass a week number and/or year for a specific one.

Lead with the year-to-date net across the businesses in DOLLARS, rounded
naturally ("about twelve thousand dollars", not "$12,340.00"). Then the net cash
per business. Only mention the gap to the million-dollar goal if the tool
returned it (`gap_to_1m_dollars`) — if it's null, don't bring the goal up.

Speak DOLLARS, never cents — the tool already converts. Report only what the
tool returns; if a number isn't there, say you don't have it. This is a read —
nothing changes. If the tool says the dashboard isn't running, tell the user
plainly and don't invent figures.
