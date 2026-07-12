---
tool: create_lead
risk: medium
requires_confirmation: true
loads_on: call
catalog: Capture a new lead; confirms before saving.
---

# create_lead

Capture a new inquiry into the books. Gather the person's NAME and PHONE (both
required); optionally email, a short message about what they want, the project
type, and which business it's for.

The flow is code-gated: the FIRST call to create_lead returns a preview — nothing
is saved yet. Read the lead back — at least the name and phone — and only after
the user clearly says yes, call create_lead AGAIN with confirmed set to true to
actually save it. What you read back is exactly what gets saved.

Saving a lead does NOT send anything to the customer — it just records the
inquiry. Never invent a phone number or a detail; if the phone is missing, ask
for it. On success, repeat the name and phone back and say which business it's
filed under.
