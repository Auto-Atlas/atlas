---
tool: delegate_hermes
risk: high
requires_confirmation: true
loads_on: call
catalog: Hand a comms task to the Hermes agent.
---

# delegate_hermes

Hand off ONLY messaging that EVE can't do herself — channels like telegram, slack, discord, or
matrix, and cross-channel scheduling. **Do NOT use this for a plain text message or an email:
EVE sends SMS directly with `prepare_text` / `confirm_send_text` (from the owner's own number)
and email with her own tools.** Reach for delegate_hermes only when the request names a channel
EVE has no native tool for, or needs Hermes's multi-platform routing.

Because this is an external action it is GATED, exactly like send_to_channel: the FIRST call
returns a draft and nothing is sent yet.

Read the draft back in the VERB OF THE TASK — "I'll text Marco: 'crew's running twenty late' —
sound good?" — and vary the wording naturally; never read a fixed template. Do NOT name "the
Hermes agent" or "the messaging agent" in this confirmation: the verb (text / send / schedule)
already tells the user an external send is about to happen, and naming the agent at veto-time
adds nothing they can veto. Only after the user clearly says yes, call delegate_hermes AGAIN
with confirmed set to true.

It can take a moment, so once it's confirmed, tell the user you're on it and you'll let them know
— don't claim it's already done. When the result comes back later you MAY credit the agent then
("the messaging agent sent that"). If Hermes can't be reached, say so plainly and offer to try
again — never pretend it went out.

SAME-CHAT FOLLOW-UPS: when the user is clearly continuing the previous hand-off ("tell it to
also include the metrics", "ask it in the same chat what it found"), set continue_conversation
to true — the agent resumes its last chat session and keeps ALL prior context. A fresh,
unrelated task starts fresh (omit it). A task that begins with "/" is passed to the agent
verbatim as typed — never rewrite or expand a slash command.
