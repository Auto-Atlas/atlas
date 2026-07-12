---
name: eve-link
description: "Talk to EVE (the house voice agent) anytime via the standing link: report news, finished work, or blockers to the owner without an active delegation."
version: 1.0.0
platforms: [linux]
metadata:
  hermes:
    tags: [eve, a2a, messaging, owner, notify]
    related_skills: []
---

# EVE Link: message the house agent anytime

EVE is the always-listening voice agent on this box. She relays what you send to the owner
through the right channel for the moment: spoken aloud if he's at the mic; if he's
away or it's night you get a push notification (ntfy → Telegram) as a heads-up AND EVE still
speaks the message once he's back / quiet hours end — an unsolicited message is never
considered delivered until she has actually said it. **Your message is relayed verbatim and
never executed.**

## The tool

`message_eve(text, kind?)` — from the `eve` MCP server. No correlation_id or callback_token
needed; the standing link key is pre-configured. This is different from `notify_eve`/`ask_eve`,
which only work DURING a task EVE delegated to you.

- `kind: "message"` (default) — news, status, finished work, anything worth hearing.
- `kind: "blocker"` — urgent problems only; it's framed to the owner as something that
  needs attention.
- `session_id` (optional but valuable) — if you know your own chat session id (check your
  system prompt), pass it. It lets the owner say "reply to hermes in the same chat" and EVE
  resumes THIS conversation with all your context intact.

## When to use it

- You finished meaningful work the owner should know about (a sale, a deploy, a report).
- A scheduled/cron run produced something noteworthy.
- Something urgent happened and no EVE delegation is active.

## Rules

1. **Consolidate.** One well-written message beats five fragments — the link rate-limits
   (HTTP 429) rapid-fire sends. Batch your news into a single `message_eve` call.
2. **Write for the ear.** EVE may speak it aloud: short sentences, no markdown, no URLs
   unless essential, lead with the headline.
3. **Never send secrets.** The message may be spoken in a room, pushed to a phone, and
   stored for replay. No keys, tokens, passwords, or full card numbers.
4. If the tool errors "no standing link is paired", tell the owner to run
   `scripts/link_pair.py` in `~/jarvis-sidecar` — don't retry in a loop.

## Cron pattern (scheduled pings to EVE)

To have a scheduled hermes run report to EVE, end the cron job's prompt with an explicit
instruction, e.g.:

> …do the task. When finished, use the message_eve tool ONCE to send EVE a spoken-friendly
> summary of what happened (lead with the outcome, keep it under 3 sentences).

Create it with `hermes cron` like any other job — the `eve` MCP server is available in
headless runs, so `message_eve` works from cron.
