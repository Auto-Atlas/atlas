# EVE Design System

Single source of truth for the EVE native apps (Android Compose now, iOS SwiftUI Phase 2).
Imported from the claude_design **EVE Design System** project
(`f2654b07-6615-4451-a3a4-34fada5a5a01`) via the `claude_design` connector.

> ⚠️ The claude.ai/design output is a **visual MOCK only**. Zero lines of its React/HTML
> are ported, embedded, wrapped, or shipped. The native UI is hand-built Kotlin/Compose
> (then SwiftUI) that *matches* it. `tokens.json` is the contract both platforms read;
> `design-tokens/*.css` are verbatim provenance.

## Brand

Dark is the **primary** brand (`#0B0F14` canvas). Teal `#2DD4BF` is "calm intelligence";
indigo `#6366F1` is the secondary accent. Logo + icon in `brand/`. The signature live
element is the **listening orb** — a teal→indigo radial with a breathing pulse and a
5-bar waveform.

## Color

Use the semantic aliases (`accent`, `surface-raised`, `text-primary`, …), never the raw
ramp. Full values in `tokens.json` → `color.dark` / `color.light`.

### Trust tiers (the core product concept)
The palette encodes the speaker-ID trust model 1:1 with `speaker_state` tiers:

| Tier | Color (dark) | Meaning |
|---|---|---|
| owner | teal `#2DD4BF` | the owner — full access |
| **known** | **indigo `#818CF8`** | enrolled family — the tier that produces approvals |
| kid | amber `#FBBF24` | child — limited |
| unknown | rose `#FB7185` | unrecognized — blocked, **never** an Approve affordance |

Because every *remote approval* is by construction a `known`-tier request, the Approvals
inbox is visually indigo-consistent; the **requester name + avatar** carry the variance,
the indigo TierChip is a trust-*floor* reassurance ("from someone enrolled, not a stranger").
**Color is never the sole signal** — every tier chip and status carries a text label and,
for status, an icon shape (a11y: red-green safe).

## Type

Manrope (UI + display), JetBrains Mono for figures (`tnum` on — money lines up). Scale in
`tokens.json` → `type.scale`. Amounts render at `display`/`titleXl` in mono.

## Spacing / radii / sizing

4px grid. Screen gutter 20, card gap 14, card padding 18/22. Cards `radius-lg` (18),
controls `radius-md` (14), sheets `radius-xl` (24). Touch targets ≥44px (`control-md`);
both Approve and Deny ≥48px and well separated (no asymmetric mis-tap toward the unsafe
action).

## Elevation & motion

Soft shadows on dark; **accent glow reserved** for live/commit states. Motion is
restrained: `durDeliberate` (520ms) is the **approve/commit** beat — used for
hold-to-approve. Honor `prefers-reduced-motion` (static ring + haptic instead of the
sweep). Keyframes: `eve-listen`, `eve-halo`, `eve-think`, `eve-wave`, `eve-commit`, `eve-rise`.

## Components (anatomy)

- **ApprovalCard** — EVE's defining component. Renders invoice (line items + computed
  total) / channel message / text. Collapsed shows the **four W's** (amount, recipient,
  requester+TierChip, time-left); expanded shows full detail. `status` pending→approved
  swaps actions for the green confirmation. **Never** an Approve for `unknown`. The
  displayed amount is computed from the frozen args, never the summary string.
- **HoldToApproveButton** — 520ms radial commit fill → pop; release early = no fire.
  Reduced-motion: static ring + haptic.
- **TierChip** — color + text label (Owner/Known/Kid/Unknown).
- **Avatar**, **ListeningOrb**, **StatusTile**, **Card**, **EveButton**, **IconButton**,
  **EveSwitch**, **BottomNav**.

See `screens/` for per-screen specs and the full approval flow + edge states.
