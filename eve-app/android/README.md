<div align="center">

# 📱 Atlas for Android — EVE in your pocket

### Remote approvals, live status, memory, glasses control — the owner's console for a self-hosted assistant.

![kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4)
![minSdk](https://img.shields.io/badge/minSdk-26-brightgreen)
![tests](https://img.shields.io/badge/unit%20tests-JVM%20%C2%B7%20passing-brightgreen)

Part of the **[Atlas](https://github.com/Auto-Atlas/atlas)** family ·
[iOS app](https://github.com/Auto-Atlas/atlas-ios) ·
[Smart-glasses bridge](https://github.com/Auto-Atlas/atlas-glasses)

</div>

---

The native Android client for [Atlas](https://github.com/Auto-Atlas/atlas) — the self-hosted
EVE voice assistant. Its hero feature is the **approval console**: when EVE is asked to do
something high-stakes (send an SMS, create an invoice, delegate to an agent), the frozen
draft lands on your phone and **hold-to-approve** (520 ms commit) releases it — single-fire,
fail-closed, over your own tailnet. Nothing owner-specific is hardcoded: the app pairs to
*your* server with *your* token.

Also on board: live status tiles + the remote-approval toggle, EVE's thinking-mode switch,
the day digest, per-speaker memory browsing, the Today tab (morning-brief action items), a
live voice channel, and smart-glasses capture routing (phone camera ↔ glasses camera).

## 🚀 Quick start

**Prerequisites:** Android Studio (Koala+) or the command-line SDK, JDK 17.

```bash
git clone https://github.com/Auto-Atlas/atlas-android && cd atlas-android
# point local.properties at your SDK (Android Studio does this automatically):
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew testDebugUnitTest     # JVM unit tests
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

Build + tests verified from this repo standalone (Linux, JDK 17, AGP 8.5) — 2026-07-07.

**Connect it to your Atlas server** (run on the machine hosting Atlas):

```bash
python -m approval_api                                        # binds 127.0.0.1:8799
tailscale serve --bg --https=8443 http://127.0.0.1:8799       # expose on your tailnet (:8443 taken? use e.g. --https=8446)
```

Then pair in seconds: say **"EVE, show the pairing QR"** and scan it from the app
(**Scan** on first launch) — the QR carries the base URL and token, nothing to type.
Manual path: enter `https://<host>.ts.net:8443` and your `EVE_APP_TOKEN`.

### ✅ Setup tracker

```markdown
- [ ] Android SDK + JDK 17 installed, local.properties points at the SDK
- [ ] ./gradlew testDebugUnitTest green
- [ ] ./gradlew assembleDebug produced app-debug.apk
- [ ] Atlas approval API exposed on the tailnet (:8443 → :8799)
- [ ] App paired (QR scan, or base URL + token)
- [ ] Approval round-trip tested: request → phone notification → hold-to-approve → fired once
- [ ] Optional: glasses capture routing (needs atlas-glasses + hardware)
```

## 🏗️ Architecture (MVVM, manual DI)

- `data/` — `@Serializable` models matching the server's row shape; `ApiClient` (typed
  suspend calls, Bearer auth from DataStore, honest `ApiError` mapping incl. 401/404/409 →
  `AlreadyResolved`); `StreamClient` (WebSocket → cold `Flow<StreamEvent>`); `Settings`.
- `ui/theme/` — design tokens ported verbatim from `design/tokens.json`, exposed via `EveTheme`.
- `ui/approvals/` — the hero: full per-card state machine
  (Pending / Releasing / Resolved(Success|SendFailed|Unverified|Elsewhere) / Expired / Denied),
  WS events + a 1 s countdown ticker folded into one flow, `HoldToApproveButton`.
- `ui/status|activity|memory/` — status tiles, day digest, per-speaker memory with live search.
- `glasses/` + `vision/` — capture-source routing: EVE's look requests go to the phone camera
  or paired smart glasses, gated by settings (stub source keeps it testable without hardware).
- `push/` — foreground `StreamService` + notifications where **Review opens the primed card
  and can never fire an approval by itself**; Deny is a broadcast.

**Stack:** Kotlin 2.0 · AGP 8.5 · compileSdk 35 · minSdk 26 · Compose (Material3) ·
Navigation-Compose · Ktor (CIO + websockets) · kotlinx.serialization · DataStore · Coroutines.

## 🧪 Tests

Pure-JVM suites: design-token values vs `tokens.json`, serialization round-trips against
committed real-shape fixtures, `ApiClient` error mapping (MockEngine), the approvals state
machine (countdown → expired, WS resolution, offline disables actions, Empty ≠ Offline),
the notification contract (no one-tap-fire path exists), and the glasses capture-routing
suites (source selection, readiness, frame routing).

## 🔒 Security posture

Bearer token on every call; approval release is single-fire and expires server-side; a
notification can never approve anything by itself; the app shows an explicit **Offline**
state (never fake data) when the brain is unreachable.

## ⭐ Star tracker

If Atlas is useful to you, a star helps other people find it.

[![Star History Chart](https://api.star-history.com/svg?repos=Auto-Atlas/atlas,Auto-Atlas/atlas-android,Auto-Atlas/atlas-ios,Auto-Atlas/atlas-glasses&type=Date)](https://star-history.com/#Auto-Atlas/atlas&Auto-Atlas/atlas-android&Auto-Atlas/atlas-ios&Auto-Atlas/atlas-glasses&Date)
