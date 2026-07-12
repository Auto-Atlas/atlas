# Screen: Approvals inbox (the hero)

The owner's remote approval console. Emotional core: a high-trust, high-stakes "should I
let this fire?" decision in under five seconds, without dread.

## States (all must be handled)

- **Loading** — first fetch.
- **Offline / off-tailnet** — *visually distinct from empty* (never look "all clear" while
  blind). Banner "Can't reach EVE — you're off the tailnet"; last-known queue greyed/stale;
  Approve/Deny **disabled** (not silently failing).
- **Empty** — "All clear — nothing waiting."
- **Items** — a list of `ApprovalCard`s.

## ApprovalCard

**Collapsed (and the ntfy/notification surface) — the four W's, always:**
1. **Amount** — big, JetBrains Mono `tnum`, **computed from frozen `args_json`** (never the
   `summary` string). Invoice → sum(qty×rate); channel → no amount, show target.
2. **Recipient / customer** (invoice) or **channel** (message).
3. **Who asked** — requester name + avatar + **TierChip** (indigo "Known", with text label).
4. **Time-left** — live TTL countdown; **amber under 60s**; on zero → expired state.

If a surface can't show all four (e.g. a terse notification), it offers **Review** (opens
the primed expanded card), not Approve.

**Expanded:**
- Invoice → line-item table (description × qty @ rate) + **computed total**; or
- Channel → target + the **full message body** (no truncation).
- A provenance line: "EVE staged this 4 min ago from a voice request."
- Actions: **HoldToApprove** (520ms commit fill → pop; release early = cancel; reduced-
  motion → static ring + haptic) and **Deny** (single tap; ≥48px; well separated).

**Per-card terminal states:**
- **Resolved / success** → green swap: "Sent — invoice #1043 created. I let Jordan know."
- **Approved-but-send-failed** (`release()` returned `ok:false`) → "Approved, but EVE
  couldn't reach the invoice service — **Retry**." (Never a false "Sent.")
- **Releasing-unverified** (crash mid-release; row stuck `releasing`) → "Approved — outcome
  unverified, check AutoInvoice."
- **Expired-while-open** → buttons swap to "Expired — ask Jordan to try again."
- **Resolved-on-another-device** (`approval_resolved` over WS, or a `409` on hold) →
  "Already handled — the owner approved this elsewhere"; hold disabled; lingering
  notification cancelled.
- **Denied** → "Denied — I let Jordan know." (symmetric with the approve confirmation)

## Notification behavior (ntfy)
- Action **Review** → deep-links the app to the primed expanded card. **Never** one-tap fire.
- Action **Deny** → may fire directly (the safe direction).
- On `approval_resolved`, the `StreamService` cancels the notification (no ghost buttons).

## Accessibility
- ≥44px targets; Approve & Deny ≥48px, separated.
- `prefers-reduced-motion` variant of hold-to-approve specified above.
- Color-not-sole-signal: TierChip text label; status text + icon shape.
- Screen-reader focus order front-loads the four W's; the Approve control's label speaks
  the consequence ("Hold to approve sending $1,200 invoice to the Browns").
