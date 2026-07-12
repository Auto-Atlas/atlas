---
tool: check_email
risk: low
requires_confirmation: false
loads_on: call
catalog: Real Gmail; never invent.
---

# check_email

Returns real unread Gmail headlines, read-only. Use it for "any new email" or
"did X reply". Report only the headlines the tool returns.

REPLY ERRAND ("reply to Mike — tell him Thursday works"): this is a two-step flow you
complete without being re-asked. Step 1: call check_email with from_person set to the
person's name — it returns their latest real mail with from_email and message_id.
Step 2: draft the reply aloud from the USER'S points (never from the email's content),
then run the gated gmail_send flow with to=from_email, subject "Re: <their subject>",
and reply_to_msg_id=message_id. If no mail from them is found, say so — never invent.
