# Google Calendar — connect once, EVE writes events

EVE has a **built-in "Connect Google Calendar"**: you connect your Google
account once (a normal Google consent screen, like any app), and from then on
"put fireworks on my calendar for July 3rd at 9" just works. It is fully
self-contained in jarvis-sidecar — no extra services, no new dependencies.
Tokens are stored on **your own machine** (`~/.eve/google_calendar_token.json`,
owner-only file permissions), refreshed automatically, and revocable at any
time. Nothing goes through anyone else's servers. The scope requested is the
narrowest that writes: `calendar.events` (events only — EVE can't manage your
calendars or sharing).

Every calendar write is still confirm-gated: EVE reads the draft event back and
only creates it after you say yes.

## One-time setup (self-hosted — you own everything)

This install uses **your own** Google Cloud OAuth client, so your data never
depends on a third party's app registration, and there is no "unverified app"
warning for your own project. About 5 minutes, once.

### 1. Create the OAuth client (in your Google account)

1. Go to **console.cloud.google.com** → create a project (any name, e.g.
   "EVE"). *Already have a project — say a Firebase one? That works: Firebase
   projects ARE Google Cloud projects; just select it instead.*
2. **APIs & Services → OAuth consent screen**: choose **External**, fill in the
   app name + your email, and under **Test users** add your own Gmail address.
   (Test mode is fine forever for a personal install — no verification needed.)
3. **APIs & Services → Enabled APIs → + Enable APIs**: enable the
   **Google Calendar API**.
4. **APIs & Services → Credentials → + Create credentials → OAuth client ID**:
   application type **Desktop app**. Copy the **Client ID** and
   **Client secret**.

### 2. Put the client in `.env` and connect

```
EVE_GOOGLE_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
EVE_GOOGLE_CLIENT_SECRET=GOCSPX-...
```

Restart EVE, then say:

> "EVE, connect my Google Calendar."

The Google consent page opens in the browser on EVE's machine (the tool also
speaks/logs the link). Pick your account, click **Allow** once. Done.

### 3. Prove it

> "EVE, put Fireworks on my calendar for July 3rd at 9 PM."

EVE reads the draft back; say yes; the event appears in Google Calendar.

## Disconnect / revoke

- Delete `~/.eve/google_calendar_token.json` (EVE also revokes Google-side on
  a programmatic disconnect), or revoke from Google directly:
  **myaccount.google.com → Security → Third-party access** → remove the app.
- With nothing connected, EVE says so honestly and calendar reads fall back to
  the ICS feed if configured.

## How EVE picks a write path

1. **Built-in Google connection** (this doc, `EVE_GOOGLE_CLIENT_ID/SECRET`) —
   used automatically whenever it is connected.
2. **OpenJarvis gcalendar connector** — for installs running the OpenJarvis
   daemon alongside; probed when the native path isn't connected.
   `EVE_CAL_CONNECTOR=0` disables the probe.
3. **Legacy Apps Script webhook** (`docs/calendar-write.md`,
   `EVE_CAL_WRITE_URL`/`EVE_CAL_WRITE_TOKEN`) — last resort.

If a *connected* path fails the write, EVE reports the failure instead of
silently retrying via the next path (no duplicate events, no masked broken
connections).

## Deployment models (for operators)

| Model | Who owns the OAuth client | Consent UX | When |
|---|---|---|---|
| **Self-hosted (this doc, default)** | The customer (their own GCP/Firebase project, test mode) | One-time 5-min setup, then one click | Privacy-first installs — nothing leaves their box |
| Managed (verified app) | Acme Web — one Google-verified OAuth client | One click, no warning, no setup | Mass-market SaaS tier; requires one-time Google brand verification (privacy policy URL, questionnaire, demo video — no CASA audit for the calendar scope) |
| Workspace delegation | Service account + domain-wide delegation | Zero per-user consent | Google Workspace / enterprise deals only |

The calendar scope (`…/auth/calendar`) is Google-"sensitive", not
"restricted": a managed/verified app needs brand verification to drop the
unverified-app interstitial and pass 100 users, but no security audit.
