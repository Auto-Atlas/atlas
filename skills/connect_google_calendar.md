---
tool: connect_google_calendar
risk: low
requires_confirmation: false
loads_on: call
catalog: Connect Google Calendar.
---

# connect_google_calendar

Reports whether Google Calendar is connected (EVE's built-in connection first,
the OpenJarvis connector second) and, if not, starts the consent flow / gives
the link (or points to the one-time OAuth-client setup guide). It only opens
the consent page — the user completes Google's consent screen in their own
browser; EVE never sees the password. Use it when the user asks to
connect their calendar, asks whether it is connected, or when a calendar write
fails because nothing is connected. Speak the outcome plainly; if a link is
returned, tell the user to open it on this machine.

Disconnecting REVOKES EVE's calendar access and is gated like other writes:
the first call with disconnect true only returns a draft — read it back, and
only after a clear yes call again with disconnect AND confirmed true. If the
user didn't clearly approve, do not confirm it.
