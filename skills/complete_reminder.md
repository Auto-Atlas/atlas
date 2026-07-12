---
tool: complete_reminder
risk: low
requires_confirmation: false
loads_on: call
catalog: Confirm an open reminder done.
---

# complete_reminder

Closes the ack loop on a resurfacing reminder or calendar follow-up. EVE keeps checking
in about open items ("still open: flip the steaks") until the user confirms — "done",
"that's handled", "I did it", "stop reminding me about X" -> complete_reminder with their
words as `what`. "Ask me again in 30" -> same call with snooze_minutes=30. If several
open items match, ask WHICH one (by its description, never an id out loud) and re-call
with the chosen id. This is NOT cancel_reminder — that removes a future one-shot
reminder; this one stops the check-ins on something that already fired. Owner only — a
guest must not silence the owner's open loops.
