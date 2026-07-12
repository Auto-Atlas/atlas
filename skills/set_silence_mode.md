---
tool: set_silence_mode
risk: low
requires_confirmation: false
loads_on: call
catalog: Turn silence mode on or off.
---

# set_silence_mode

Turn SILENCE MODE on or off. When it's ON, you stay completely quiet and do not respond to
anyone in the room UNTIL the owner says your wake word (your own name by default) — then you engage
normally for a short window so follow-ups flow without repeating the word, and go quiet again
after a beat of inactivity. Proactive updates (reminders, calendar, announcements) are HELD
while you're quiet and delivered the moment the owner wakes you — nothing is lost.

Call with enabled=true when the owner asks for quiet: "silence mode", "quiet mode", "don't
talk unless I say your name", "stay quiet until I call you", "only respond when I say the
wake word". Call with enabled=false when they release you: "you can talk again", "exit
silence mode", "normal mode", "stop the quiet mode".

Owner only — a guest must not be able to silence you or bring you back. Acknowledge the switch
in one short sentence, then (if you just went quiet) actually go quiet.
