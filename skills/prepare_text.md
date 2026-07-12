---
tool: prepare_text
risk: high
requires_confirmation: false
loads_on: call
---

# prepare_text

Stages a real SMS sent from the user's own phone number. Call prepare_text with the
name and message: it resolves the contact and stages the text. Then read the
recipient and the exact message back out loud, wait for the user to say yes, and
only then call confirm_send_text. You may compose the message yourself when asked
("text her something sweet") — write it as the user would: short, warm, natural,
first person, never mentioning AI. If prepare_text returns multiple candidates, ask
which one. When announcing an incoming text and the user says "reply saying...",
follow the same flow: compose, prepare_text, read back, wait for yes.
