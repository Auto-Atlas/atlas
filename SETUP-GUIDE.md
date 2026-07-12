# Atlas Setup Guide (start here)

This gets you a voice assistant that runs 100% on your own PC — free, no
API keys, nothing leaves your machine. You talk, it talks back, and it has
real tools: reminders, memory, news, weather, your notes, music control,
and (optional) texting from your own phone number.

Time: about 20 minutes for the voice loop. The phone stuff is optional and
adds 15 more.

---

## Part 1 — What you need

- A Windows PC. An NVIDIA graphics card (8 GB+ VRAM) makes it fast; without
  one it still works, just slower (one extra step below).
- A microphone and speakers (laptop built-ins are fine).
- About 12 GB of free disk space (the AI models live on your machine).

## Part 2 — Install the voice loop (copy-paste each line)

1. Install these three things (each is a normal installer — click Next
   until done):
   - **Python 3.11** — https://www.python.org/downloads/release/python-3119/
     → "Windows installer (64-bit)". **CHECK the "Add python.exe to PATH"
     box** on the first screen.
   - **Git** — https://git-scm.com/download/win (defaults are fine)
   - **Ollama** — https://ollama.com/download/windows

2. Open **PowerShell** (Start menu → type "powershell" → Enter) and paste
   these lines ONE AT A TIME, pressing Enter after each:

   ```powershell
   git clone https://github.com/Auto-Atlas/atlas.git
   cd atlas
   py -3.11 -m venv .venv
   .venv\Scripts\pip install -r requirements.txt
   ollama pull qwen3:8b
   copy .env.example .env
   ```

   The `pip install` and `ollama pull` lines each take a few minutes —
   that's normal, it's downloading the AI.

3. **Only if you do NOT have an NVIDIA graphics card:** open the `.env`
   file in Notepad (`notepad .env`) and change these two lines:

   ```
   WHISPER_DEVICE=cpu
   WHISPER_COMPUTE=int8
   ```

4. Start it:

   ```powershell
   .\run.bat
   ```

   Wait ~20 seconds for it to load. It greets you out loud. Now just talk:

   - "What's the weather like?"
   - "Remind me in 10 minutes to check the oven."
   - "Remember that my favorite team is the Patriots."
   - "What's in the news?"
   - "Diagnose yourself." ← it tells you what it can and can't do
   - "Challenge me on my goals." ← coach mode, it pushes back

   To stop it: close the PowerShell window.

> Memory note: facts you ask it to remember are saved to a markdown file —
> by default `jarvis-memory.md` in your home folder. To keep it somewhere
> else, open `.env` and set `JARVIS_MEMORY_PAGE=C:\path\to\your-memory.md`.

---

## Part 3 (optional) — Text from YOUR phone number

This lets you say "Jarvis, text Mike I'm running late" and a real SMS goes
out from your own number, plus Jarvis announces incoming texts out loud.
Android only.

### 3a. The secure tunnel (Tailscale, free)

1. Install **Tailscale** on the PC (https://tailscale.com/download) and on
   your phone (Play Store). Sign in to BOTH with the same Google account.
2. On the PC, in PowerShell:

   ```powershell
   tailscale serve --bg http://localhost:8787
   ```

   It prints a URL like `https://your-pc.tailXXXX.ts.net`. Save it.

3. Get your secret webhook path — in PowerShell, from the atlas
   folder:

   ```powershell
   Get-Content webhook_token.txt
   ```

   Your full webhook URL is:
   `https://your-pc.tailXXXX.ts.net/hook/<that token>/sms`

### 3b. Sending (SMS Gate app)

1. Install **SMS Gate** on the phone (search "SMS Gate" by capcom6 on the
   Play Store), open it, turn on **Local Server** mode.
2. The app shows an IP, port, username, and password. In Tailscale on the
   phone, find the phone's Tailscale IP (starts with 100.).
3. On the PC, edit `.env` (`notepad .env`):

   ```
   JARVIS_OWNER_PHONE=+1XXXXXXXXXX        <- YOUR phone number
   JARVIS_SMS_GATEWAY_URL=http://100.x.x.x:8080   <- phone's Tailscale IP + SMS Gate port
   JARVIS_SMS_USER=sms                    <- from the SMS Gate screen
   JARVIS_SMS_PASS=xxxxxxxx               <- from the SMS Gate screen
   ```

4. Contacts: export your Google Contacts as **Google CSV**
   (contacts.google.com → Export) and save the file as
   `C:\Users\<you>\jarvis-inbox\contacts.csv`.
5. In SMS Gate, turn ON "Start on boot."

### 3c. Receiving — the MacroDroid bridge (~10 minutes)

This catches EVERY incoming message notification (RCS included) and feeds
it to Jarvis, who announces it out loud.

1. Play Store → install **MacroDroid** (by ArloSoft) → open, skip the
   intro. When it asks to disable battery optimization — allow it.
2. Tap **+ Add Macro**, then:
   - **Trigger** → Notifications → **Notification Received** → Select
     Application(s) → check **Messages** (Google Messages) → OK. Grant
     Notification Access when it asks (it takes you to Settings — toggle
     MacroDroid on).
   - **Actions** → Connectivity → **HTTP Request**:
     - Method: **POST**
     - URL: your full webhook URL from step 3a
     - Find the **Parameters / Form data** section → add two parameters:
       - name `message` → for the value, tap the magic-wand/{...} icon →
         Notification → **Notification text**
       - name `sender` → magic wand → Notification → **Notification title**
     - Leave everything else default → OK.
   - Constraints: none needed.
3. Name it **Jarvis message bridge** → save → make sure its toggle is ON.
4. In `.env` on the PC, set how your OWN name shows up in a Google Messages
   notification when you text yourself (so self-commands work):

   ```
   JARVIS_OWNER_NAMES=Your Name,You
   ```

5. Restart Jarvis (close the window, run `.\run.bat` again). Have someone
   text you — Jarvis announces it within a couple of seconds.

Bonus: text YOURSELF `jarvis note buy milk` and it saves a note. Text
`jarvis text mike: running late` and it sends a real SMS — from anywhere,
even when you're away from the PC. (Tailscale must stay on on the phone.)

---

## Part 4 (optional, 2 minutes each) — Calendar and Email

- **Calendar (read-only):** calendar.google.com → gear → Settings → click
  your calendar in the left sidebar → "Integrate calendar" → copy the
  **Secret address in iCal format** → paste into `.env` as
  `JARVIS_CALENDAR_ICS_URL=`. Then ask: "What do I have tomorrow?"
- **Email (read-only):** myaccount.google.com/apppasswords → create one
  named "Jarvis" → put your address in `GMAIL_USER=` and the 16-character
  code in `GMAIL_APP_PASSWORD=` in `.env`. Then ask: "Any new email?"

Restart Jarvis after editing `.env`.

---

## Part 5 (optional) — Jarvis in your ear, anywhere (the phone voice app)

Talk to Jarvis from your phone (and any earbuds connected to it) over your
private Tailscale network — the AI still runs on your PC.

1. On the PC, start the phone server (jarvis-up does this automatically) and
   expose it:

   ```powershell
   tailscale serve --bg --https=8444 http://localhost:8788
   ```

2. On the phone (Tailscale ON), open:
   `https://your-pc.tailXXXX.ts.net:8444`
3. Tap **Connect**, allow the microphone, and talk. Add to Home Screen for
   an app icon.

Notes: the first connect takes ~15 seconds (models loading) — after that
replies are fast. The phone session is its own conversation, but it shares
the same tools and long-term memory as the desktop loop.

---

## Part 6 (optional) — The desktop app (UI + transforming avatar)

The `app/` folder is the full OpenJarvis desktop app with our custom Jarvis
face: the particle avatar that morphs between smoke / brain / genie / walking
figure as he listens, thinks, speaks, and works — plus the voice-reactive
halo, the cinematic power-on stage page, and voice turns merged into the
typed chat timeline. The voice loop works fine without it; this is the
eye candy and the typed-chat brain.

1. Install Node 20+ (https://nodejs.org) and Rust (https://rustup.rs).
2. ```powershell
   cd app\frontend
   npm install
   npm run build:tauri
   cargo build --manifest-path src-tauri\Cargo.toml
   ```
3. Run `app\frontend\src-tauri\target\debug\openjarvis-desktop.exe` — it
   auto-connects to the voice sidecar and spawns it if it isn't running.
4. The cinematic full-screen stage lives at the `/stage` route (great for
   recording demos).

---

## If something breaks

- **It can't hear you** — Windows Settings → Privacy & security →
  Microphone → allow desktop apps. Check the right mic is the Windows
  default.
- **Crash on startup with a CUDA/cublas error** — you don't have an NVIDIA
  card or drivers; do step 3 of Part 2 (cpu mode).
- **It talks nonsense about its own tools** — say "diagnose yourself"; it
  audits itself for real and tells you what's actually broken.
- Anything else: the error log is `sidecar.err.log` in the atlas folder —
  attach it to a GitHub issue at https://github.com/Auto-Atlas/atlas/issues.
