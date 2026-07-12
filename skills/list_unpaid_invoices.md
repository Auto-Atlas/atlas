---
tool: list_unpaid_invoices
risk: low
requires_confirmation: false
loads_on: call
catalog: Unpaid invoices, overdue first.
---

# list_unpaid_invoices

Outstanding invoices from the books. Optionally filter to one business
(company_id) or cap the count (limit).

Lead with OVERDUE: how many are overdue, the total overdue in dollars, and the
oldest one (customer and how many days late). Then the remaining unpaid total.
Name only the top 3 by amount unless the user asks for all of them.

Speak DOLLARS, never cents; round naturally. Report only what the tool returns —
don't invent an amount or a customer. If nothing is unpaid, just say so. This is
a read; it doesn't send reminders or change anything.
