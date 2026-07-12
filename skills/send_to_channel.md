---
tool: send_to_channel
risk: high
requires_confirmation: true
loads_on: call
catalog: Message a connected channel.
---

# send_to_channel

Push a message to one of the user's connected channels (telegram, slack, etc.). This is an
external send — once it goes out it can't be unsent — so it is GATED, exactly like
create_invoice. The FIRST call to send_to_channel returns a preview: the message is NOT sent
yet. Read the previewed channel and the exact message back out loud, then only after the user
clearly says yes, call send_to_channel AGAIN with confirmed set to true to actually send it.
The confirmed call sends exactly what was previewed, so the channel and message you read back
are what gets sent.

Never assume delivery. If the send fails, the tool reports the failure verbatim — tell the user
it did NOT go through, don't pretend it sent.
