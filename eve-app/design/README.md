# eve-app/design — the design source of truth

Everything the native UI is built against. Imported from the claude_design **EVE Design
System** project (`f2654b07-6615-4451-a3a4-34fada5a5a01`).

```
tokens.json            canonical machine-readable tokens (Compose + SwiftUI generate from THIS)
design-tokens/*.css    verbatim provenance from the design project (reference only, never shipped)
brand/                 eve-logo.svg, eve-icon.svg (+ preview PNGs)
design-system.md       the system: brand, color, type, spacing, motion, components
screens/               approvals.md (hero), other-screens.md (Status/Activity/Memory/Talk)
```

## How tokens map to native code

- **Android (Compose):** `eve-app/android/app/src/main/java/app/eve/ui/theme/` —
  `Color.kt`, `Type.kt`, `Spacing.kt`, `Shape.kt`, `Elevation.kt`, `Motion.kt` port the
  values from `tokens.json` verbatim. `TokenValuesTest.kt` asserts they match.
- **iOS (SwiftUI, Phase 2):** `eve-app/ios/.../Tokens.swift` mirrors the same `tokens.json`
  so the two platforms can't drift.

## The hard rule

The claude.ai/design mock is **reference only** — React/HTML that exists purely to look at
and extract values from. **Zero lines reach the app.** The native UI is hand-built to
*match* the mock, never to embed or wrap it.
