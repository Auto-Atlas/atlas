---
tool: research_status
risk: low
requires_confirmation: false
loads_on: call
catalog: Latest ResearchOS session's progress; read-only.
---

# research_status

Progress of the MOST RECENT ResearchOS session — how far along it is and whether
it's done. Takes no arguments; it always reports the latest session.

Give the user the session's goal and how it's going: the job status (searching,
evaluating, synthesizing, complete) and how many needs are done out of the
total. If it errored, say so plainly. If there are no sessions yet, say that. If
the latest session hasn't started its pipeline, say which stage it's in.

Keep it to a sentence or two. Report only what the tool returns. This is a read.
If ResearchOS is unreachable (the inference box may be down), say so plainly — don't
invent progress.
