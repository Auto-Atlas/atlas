<div align="center">

# 🍎 Atlas for iPhone — EVE on iOS (and Apple Watch)

### The native Apple client for a self-hosted assistant: approvals, status, and voice on your wrist and in your pocket.

![swift](https://img.shields.io/badge/Swift-5.10-F05138?logo=swift&logoColor=white)
![xcode](https://img.shields.io/badge/Xcode-26.2-147EFB)
![tests](https://img.shields.io/badge/AtlasKit%20tests-passing-brightgreen)

Part of the **[Atlas](https://github.com/Auto-Atlas/atlas)** family ·
[Android app](https://github.com/Auto-Atlas/atlas-android) ·
[Smart-glasses bridge](https://github.com/Auto-Atlas/atlas-glasses)

</div>

---

The native iOS client for [Atlas](https://github.com/Auto-Atlas/atlas) — the self-hosted EVE
voice assistant. It talks to your own Atlas server (approval hub + event stream) over your
tailnet; endpoints and tokens come from configuration, never from code. The centerpiece is
the same fail-safe approval flow as Android: a frozen draft with the amount and recipient
shown, released only by an explicit gesture, exactly once.

**Status:** Phase 1 in active development — `AtlasKit` (models + API layer) is built and
tested (approval DTOs with fail-safe amount handling, tier/risk, status, health, vision,
visual cards). The app shell builds and runs. **watchOS** (`AtlasWatch` + widgets) is Phase 3
and will live in this repo, reusing AtlasKit wholesale.

## 🚀 Quick start (Mac)

**Prerequisites:** Xcode 26.2+ (iOS 26.2 SDK), [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`).

```bash
git clone https://github.com/Auto-Atlas/atlas-ios && cd atlas-ios
xcodegen generate                 # project.yml → Atlas.xcodeproj
open Atlas.xcodeproj              # build the Atlas scheme, or:
xcodebuild -project Atlas.xcodeproj -scheme Atlas \
  -destination 'generic/platform=iOS Simulator' build
cd AtlasKit && swift test         # the package test suite
```

Verified standalone (xcodegen + full app build + AtlasKit tests) — 2026-07-07.

### ✅ Setup tracker

```markdown
- [ ] Xcode 26.2+ and XcodeGen installed
- [ ] xcodegen generate produced Atlas.xcodeproj
- [ ] Atlas scheme builds for the iOS Simulator
- [ ] cd AtlasKit && swift test green
- [ ] Atlas server reachable on your tailnet (approval API :8799 behind HTTPS)
- [ ] App configured with your base URL + token (see the Atlas pairing guide)
```

## 🏗️ Layout

| Path | What it is |
|---|---|
| `AtlasKit/` | Swift package: API models + client layer. All logic lives here so the app and the future watch target share one tested core. |
| `Atlas/` | The iPhone app target (SwiftUI). |
| `AtlasTests/` | App-level tests. |
| `project.yml` | XcodeGen spec — the `.xcodeproj` is generated, never hand-edited. |

Design notes and the phased plan live in the core repo under
[`docs/superpowers/specs/`](https://github.com/Auto-Atlas/atlas/tree/main/docs/superpowers/specs).

## 🔒 Security posture

Approval DTOs decode fail-safe: a missing or malformed amount can never display as zero or
auto-approve — unknown means blocked. Bearer auth on every call; nothing owner-specific is
compiled in.

## ⭐ Star tracker

If Atlas is useful to you, a star helps other people find it.

[![Star History Chart](https://api.star-history.com/svg?repos=Auto-Atlas/atlas,Auto-Atlas/atlas-android,Auto-Atlas/atlas-ios,Auto-Atlas/atlas-glasses&type=Date)](https://star-history.com/#Auto-Atlas/atlas&Auto-Atlas/atlas-android&Auto-Atlas/atlas-ios&Auto-Atlas/atlas-glasses&Date)
