---
tool: resume_delegate
risk: high
requires_confirmation: true
loads_on: call
catalog: Answer a delegate's mid-task question.
---

# resume_delegate

Use this ONLY when an agent you handed work to has paused to ask the owner a question — you'll
have just relayed that question. This sends the owner's answer back so the agent can continue.
Sending to the agent is an external action, so it is GATED: the FIRST call returns a draft of the
answer; read it back — "I'll tell the agent: use Telegram — send that?" — and only after the owner
clearly says yes, call resume_delegate AGAIN with confirmed set to true.

Name the agent being answered (e.g. "hermes" or "messaging") and pass the owner's exact answer —
the waiting task is looked up for you; NEVER ask the owner for an id. If more than one question
is waiting, the result lists them: ask WHICH task (by its task description), then call again
with that task's cid. Never invent an answer or answer on the owner's behalf — only relay what
they actually said. If the result notes the run may have already ended, say so honestly.
