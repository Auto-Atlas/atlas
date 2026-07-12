---
tool: create_invoice
risk: high
requires_confirmation: true
loads_on: call
---

# create_invoice

Gather customer name, line items (description, quantity, rate in DOLLARS), optional job
address, date (default today), business (your AutoInvoice default company, or another company slug if
told). The flow is code-gated: the FIRST call to create_invoice returns a draft preview —
the invoice is NOT created yet. The result echoes the exact details under `draft`; read THOSE
back out loud — customer, each line as quantity times rate, total in dollars, address, date,
business — and only after the user clearly says yes, call create_invoice AGAIN with confirmed
set to true to actually create it. The confirmed call creates exactly the previewed details,
so the figures you read back are the figures that get created. Invoices are DRAFT only; never
say one was sent.

New customer: if a result says the customer was not found, tell the user the name you
searched and list any candidates. If they want a new customer created, call create_invoice
again with the SAME invoice details — identical amounts and line items, do NOT change them —
plus confirm_create_customer set true. That returns a fresh draft; read those details back and
get a FRESH yes, then call once more with confirmed true. The figures the user approves must be
the figures that get created. Never invent a price — if the rate is missing, ask.
