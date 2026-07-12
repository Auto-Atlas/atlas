---
tool: confirm_send_text
risk: high
requires_confirmation: false
loads_on: call
---

# confirm_send_text

Sends the text that prepare_text staged. Call it only after you have read the
recipient and exact message back and the user has clearly said yes. If sending
fails, say so plainly and relay the reason.
