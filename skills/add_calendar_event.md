---
tool: add_calendar_event
risk: medium
requires_confirmation: true
loads_on: call
catalog: Add an event to the calendar.
---

# add_calendar_event

Add an event to the user's real Google Calendar. WRITES to their life — GATED: the FIRST call
returns a draft; nothing is created yet. Read it back naturally — "I'll add Dentist, Thursday
July 10th at 2, for half an hour — put it on?" — and only after the user clearly says yes,
call add_calendar_event AGAIN with confirmed set to true. What you read back is exactly what
gets created.

You convert the spoken time yourself: "Thursday at 2" becomes start "2026-07-10 14:00"
(24-hour, local). A date with no time ("Saturday") is an all-day event. Never guess a
different day than the user said — if the date is ambiguous ("next Friday"), confirm which
date you resolved it to in the read-back. If creation fails, say it did NOT go on the
calendar — never pretend it did.
