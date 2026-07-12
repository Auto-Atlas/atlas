---
tool: get_goal_scorecard
risk: low
requires_confirmation: false
loads_on: call
catalog: Vision priority-stack scorecard; read-only.
---

# get_goal_scorecard

The Vision priority-stack scorecard: the ranked goals (the million-dollar cash
goal, delegation, the robot arm, and manual goals) with their scores this
period. Defaults to the weekly grain; pass daily or monthly if asked.

Go through the goals in stack-rank order, rank 1 first. For each, give its title
and how it's tracking — the score and detail when present. Lead with the
million-dollar cash goal if it's in the list. Keep it brief unless the user
wants detail.

Report only what the tool returns — don't invent a score. This is a read. If the
dashboard isn't running, say so plainly.
