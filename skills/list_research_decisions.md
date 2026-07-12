---
tool: list_research_decisions
risk: low
requires_confirmation: false
loads_on: call
catalog: Recent ResearchOS decisions; read-only.
---

# list_research_decisions

The most recent ResearchOS decisions — the research write-ups where products got
chosen. Defaults to the latest 3; pass a limit for more.

Tell the user how many recent decisions there are and read back the top few by
their slug — turn the slug into readable words, it's a title, not something to
spell out. If there are none, say there are no research decisions yet.

Report only what the tool returns. This is a read. If ResearchOS is unreachable
(the inference box may be down), say so plainly.
