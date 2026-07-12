# eve-app — EVE native mobile companion

The owner's **remote control + approval console** for EVE, over the tailnet (self-hosted,
no cloud account system). Closes the deferred Phase 2 of the speaker-ID trust tiers: a
*known* family member's high-risk request is staged and reaches the owner for remote
approve/deny; the sidecar releases the frozen draft via the existing `confirmed=true`
single-fire path. Fail-closed.

```
eve-app/
  design/        the design source of truth (tokens.json + brand + screen specs)
  android/       native Kotlin + Jetpack Compose (this run)
  ios/           native SwiftUI (Phase 2 — second run)
```

The **sidecar approval backend is NOT here** — it lives beside `jarvis_core.py` in the
Python repo root: `approval_store.py`, `release.py`, `approval_push.py`, `approval_api.py`,
plus the block→stage branch in `tool_policy.py`.

---

## Backend — the sidecar approval API

Runs as its own process beside the voice loops (never inside `bot.py`/`phone_bot.py`).

```bash
# from the repo root, in the project venv
export EVE_APP_TOKEN="$(head -c 32 /dev/urandom | base64)"   # or put it in approval_token.txt (chmod 600)
export EVE_REMOTE_APPROVAL=disabled         # default; flip to 'enabled' (or via the app toggle) to activate
.venv/bin/python -m approval_api            # binds 127.0.0.1:8799

# expose to the tailnet (TLS) for the phone -- :8443 must be free; if the typed
# web UI already serves there, pick another port (e.g. --https=8446):
tailscale serve --bg --https=8443 http://127.0.0.1:8799
```

Config (all env, read at call time):
- `EVE_APP_TOKEN` / `approval_token.txt` — the app bearer token (blank → refuses to start).
- `EVE_APPROVAL_DB` — store path (default `~/jarvis-sidecar/approvals.db`).
- `EVE_REMOTE_APPROVAL` — `enabled`/`disabled` default (settings row from the app overrides).
- `EVE_REMOTE_APPROVAL_TTL_S` — remote approval TTL (default 14400 = 4h).
- `EVE_NTFY_URL` / `EVE_NTFY_TOPIC` — self-hosted ntfy push; falls back to Telegram if down.

Run the backend tests (real components, real green):
```bash
.venv/bin/python -m pytest tests/test_approval_store.py tests/test_release.py \
  tests/test_tool_policy_remote.py tests/test_approval_api.py tests/test_approval_push.py -q
```

### Endpoints (all Bearer-auth)
`GET /v1/health` · `GET /v1/approvals?status=pending` · `GET /v1/approvals/{id}` ·
`POST /v1/approvals/{id}/approve` · `POST /v1/approvals/{id}/deny` ·
`GET|POST /v1/settings` · `GET|POST /v1/memory` · `GET /v1/activity` · `WS /v1/stream`.

The client only ever sends an `id`; the sidecar fires the exact frozen draft (single-fire,
TTL-bounded, server-side tier re-assertion). A forged client can never synthesize a
`create_invoice`/`send_to_channel`.

---

## Android app

Native Kotlin + Jetpack Compose, MVVM/UDF, Ktor + kotlinx.serialization, manual DI.
Screens: **Approvals inbox (hero)**, Status (+ activation toggle), Activity, Memory;
Talk-to-EVE is Phase 2.

```bash
cd eve-app/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # point at your SDK (gitignored)
./gradlew testDebugUnitTest   # JVM unit tests — 28 pass
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```

### ✅ Compiled, tested, and packaged
Built against **Gradle 8.10.2 + AGP 8.5.2 + Kotlin 2.0.20 + JDK 17 + Android SDK
platform-35**: `:app:testDebugUnitTest` → **28/28 unit tests pass** (TokenValues 8,
Serialization 4, ApiClient 6, ApprovalsViewModel 6, NotificationsContract 4), and
`:app:assembleDebug` → a real **~12.5 MB `app-debug.apk`**. The Gradle wrapper jar is
committed (`./gradlew` works out of the box) and the fonts are bundled (Manrope as the
google/fonts variable instance driven per-weight via `FontVariation`; JetBrains Mono as
static TTFs with `tnum`) — both SIL OFL, licenses in `licenses/`.

Requirements to reproduce: an Android SDK with platform-35 + build-tools 34.0.0 and a JDK
17 (`JAVA_HOME`). `local.properties` (`sdk.dir`) is machine-specific and gitignored.

Then point the app at the sidecar (tailnet base URL + the `EVE_APP_TOKEN`) on the Connect
screen.

No code stubs, fakes, or placeholders remain — the earlier `PLACEHOLDER.md` notes for
binary assets are gone now that the real fonts + wrapper jar are in place.

---

## iOS (Phase 2)
SwiftUI against the same backend, generating `Tokens.swift` from the same `design/tokens.json`
so Android and iOS can't drift. Not built in this run.
