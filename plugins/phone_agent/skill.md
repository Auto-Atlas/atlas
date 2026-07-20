---
tool: phone_line_status
risk: low
requires_confirmation: false
catalog: business phone line status — "is the phone line up?", "check the phone"
---

# phone_line_status

Checks the health of the phone agent (the Twilio bridge answering the business
phone numbers). Use when the owner asks whether the phone line is working, who
is answering it, or after they report a caller heard an error. No arguments.

Speak the result plainly: "The phone line is up — Atlas is answering for
Business Builders" or, on failure, read the error sentence back exactly — it
says what is broken and what command shows the details. A DOWN line means real
callers are hearing Twilio's "application error" right now; treat that as
urgent, not as small talk.
