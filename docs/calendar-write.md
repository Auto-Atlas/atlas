# Calendar WRITE (LEGACY) — Apps Script webhook fallback

> **This is the legacy fallback.** The preferred path is the OpenJarvis
> "Connect Google Calendar" OAuth connector — one consent click, no script to
> paste — see **docs/google-calendar.md**. EVE uses the connector automatically
> whenever it's connected; the webhook below is only consulted when it isn't.

Reading already works (secret ICS URL). Writing uses a **Google Apps Script webhook** you own —
no OAuth dance, no Google Cloud project, no new Python deps (same philosophy as the ICS read
and the SMTP app-password email send). ~5 minutes, once.

## 1. Create the script (in your Google account)

1. Go to **script.google.com** → **New project**.
2. Replace the contents with this (the shared secret is NOT hardcoded — it's read from the
   project's Script Properties, so no key ever lives in the source):

```javascript
function doPost(e) {
  var out = ContentService.createTextOutput().setMimeType(ContentService.MimeType.JSON);
  try {
    var TOKEN = PropertiesService.getScriptProperties().getProperty("EVE_CAL_WRITE_TOKEN");
    var p = JSON.parse(e.postData.contents);
    if (!TOKEN || p.token !== TOKEN) {
      return out.setContent(JSON.stringify({ok: false, error: "forbidden"}));
    }
    var cal = CalendarApp.getDefaultCalendar();
    var start = new Date(p.start);
    var ev;
    if (p.all_day) {
      ev = cal.createAllDayEvent(p.title, start);
    } else {
      var mins = p.duration_min || 60;
      ev = cal.createEvent(p.title, start, new Date(start.getTime() + mins * 60000));
    }
    return out.setContent(JSON.stringify({ok: true, id: ev.getId()}));
  } catch (err) {
    return out.setContent(JSON.stringify({ok: false, error: String(err)}));
  }
}
```

3. **Project Settings (⚙) → Script Properties → Add script property**:
   name `EVE_CAL_WRITE_TOKEN`, value = the exact `EVE_CAL_WRITE_TOKEN` from your `.env`.
   (This is why the token only lives in two places — your `.env` and this private setting —
   never in code or docs.)
4. **Deploy → New deployment → Web app**: *Execute as* **Me**, *Who has access*
   **Anyone with the link** (the token in the body is the real gate; the URL is also
   unguessable). Copy the **web app URL** (ends in `/exec`).

## 2. Wire EVE (`.env`)

```
EVE_CAL_WRITE_URL=https://script.google.com/macros/s/…/exec
EVE_CAL_WRITE_TOKEN=the same long random string as TOKEN above
```

Restart `jarvis-sidecar` — the `add_calendar_event` tool is already registered and gated.

## 3. Use it

> *You:* "Put the dentist on my calendar Thursday at 2."
> *EVE:* "I'll add Dentist, Thursday July 10th at 2pm, for an hour — put it on?"
> *You:* "Yes." → it's on the calendar (and the watcher will remind you 15 minutes before).

Safety: confirm-gated like every side-effecting tool (the read-back IS the event that gets
created); a failed create is reported as NOT created, never papered over; the webhook URL is
scrubbed from any spoken error, like the ICS URL.
