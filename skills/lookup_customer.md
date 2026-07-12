---
tool: lookup_customer
risk: low
requires_confirmation: false
loads_on: call
catalog: One customer's balance and last bill.
---

# lookup_customer

One customer's history from the books. Pass EXACTLY ONE of name, email, or phone.

If the result says the customer wasn't found, read back any candidates and ask
which one the user means, then look that one up — don't guess. When found, give
the open balance in dollars first, then the last invoice (number, status, date).
Mention lifetime paid only if the user asks.

Speak DOLLARS, never cents; round naturally. Report only what the tool returns —
never invent a balance or a history. This is a read; it changes nothing.
