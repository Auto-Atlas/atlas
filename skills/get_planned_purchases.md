---
tool: get_planned_purchases
risk: low
requires_confirmation: false
loads_on: call
catalog: Planned (researched-but-unspent) purchases; read-only.
---

# get_planned_purchases

Purchases ResearchOS researched and staged to buy but that haven't been spent
yet — committed money. Optionally filter to one business (acme-farms,
acme-web, acme-robotics).

Say how many are planned and the total planned spend in DOLLARS, rounded
naturally. Name the top few by cost — which business and what it's for — unless
the user asks for all. If nothing's planned, just say so.

Report only what the tool returns; don't invent a cost or an item. This is a
read. If the dashboard isn't running, say so plainly.
