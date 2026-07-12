package app.eve.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Color tokens ported VERBATIM from eve-app/design/tokens.json (color.dark / color.light /
 * color.tier). Dark is the primary brand. These are the single source of truth for in-app
 * color; the XML copies in res/values/colors.xml exist only for window/icon chrome.
 *
 * tokens.json stores some colors as 8-digit #RRGGBBAA (e.g. accentSoft "#2DD4BF1F"). Compose
 * Color(0x...) expects #AARRGGBB, so those are converted here (alpha moved to the front) and
 * the original token string is noted in a comment for traceability.
 */

/** Trust-tier color pair (the core product concept: speaker-ID trust model). */
@Immutable
data class TierColor(val fg: Color, val soft: Color)

@Immutable
data class TierColors(
    val owner: TierColor,
    val known: TierColor,
    val kid: TierColor,
    val unknown: TierColor,
)

@Immutable
data class EveColorScheme(
    val surfaceCanvas: Color,
    val surfaceSunken: Color,
    val surfaceRaised: Color,
    val surfaceRaised2: Color,
    val surfaceOverlay: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
    val textLink: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val accent2: Color,
    val accent2Soft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val tier: TierColors,
)

/** color.dark — the primary theme. */
val EveDarkColors = EveColorScheme(
    surfaceCanvas = Color(0xFF0B0F14),
    surfaceSunken = Color(0xFF060A0E),
    surfaceRaised = Color(0xFF11161D),
    surfaceRaised2 = Color(0xFF161D26),
    surfaceOverlay = Color(0xFF1E2630),
    borderSubtle = Color(0x1A94A3B8), // token "#94A3B81A"
    borderDefault = Color(0xFF1E293B),
    borderStrong = Color(0xFF24303C),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textTertiary = Color(0xFF64748B),
    textOnAccent = Color(0xFF04201C),
    textLink = Color(0xFF5EEAD4),
    accent = Color(0xFF2DD4BF),
    accentHover = Color(0xFF5EEAD4),
    accentPressed = Color(0xFF14B8A6),
    accentSoft = Color(0x1F2DD4BF), // token "#2DD4BF1F"
    accentLine = Color(0x4D2DD4BF), // token "#2DD4BF4D"
    accent2 = Color(0xFF6366F1),
    accent2Soft = Color(0x246366F1), // token "#6366F124"
    success = Color(0xFF34D399),
    successSoft = Color(0x2134D399), // token "#34D39921"
    warning = Color(0xFFFBBF24),
    warningSoft = Color(0x24FBBF24), // token "#FBBF2424"
    danger = Color(0xFFF87171),
    dangerSoft = Color(0x21F87171), // token "#F8717121"
    tier = TierColors(
        owner = TierColor(Color(0xFF2DD4BF), Color(0x242DD4BF)),   // tier.dark.owner / ownerSoft
        known = TierColor(Color(0xFF818CF8), Color(0x29818CF8)),   // tier.dark.known / knownSoft
        kid = TierColor(Color(0xFFFBBF24), Color(0x26FBBF24)),     // tier.dark.kid / kidSoft
        unknown = TierColor(Color(0xFFFB7185), Color(0x24FB7185)), // tier.dark.unknown / unknownSoft
    ),
)

/** color.light — provided for completeness; dark stays primary (tokens.$meta.primaryTheme). */
val EveLightColors = EveColorScheme(
    surfaceCanvas = Color(0xFFEEF1F5),
    surfaceSunken = Color(0xFFE2E7EE),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceRaised2 = Color(0xFFFFFFFF),
    surfaceOverlay = Color(0xFFFFFFFF),
    borderSubtle = Color(0x140F172A), // token "#0F172A14"
    borderDefault = Color(0x1F0F172A), // token "#0F172A1F"
    borderStrong = Color(0x2E0F172A), // token "#0F172A2E"
    textPrimary = Color(0xFF0B0F14),
    textSecondary = Color(0xFF475569),
    textTertiary = Color(0xFF64748B),
    textOnAccent = Color(0xFF04201C),
    textLink = Color(0xFF14B8A6),
    accent = Color(0xFF14B8A6),
    accentHover = Color(0xFF2DD4BF),
    accentPressed = Color(0xFF0F9488),
    accentSoft = Color(0x1F14B8A6), // token "#14B8A61F"
    accentLine = Color(0x4714B8A6), // token "#14B8A647"
    accent2 = Color(0xFF6366F1),
    accent2Soft = Color(0x1F6366F1), // token "#6366F11F"
    success = Color(0xFF059669),
    successSoft = Color(0x1F10B981), // token "#10B9811F"
    warning = Color(0xFFD97706),
    warningSoft = Color(0x24D97706), // token "#D9770624"
    danger = Color(0xFFDC2626),
    dangerSoft = Color(0x1ADC2626), // token "#DC26261A"
    tier = TierColors(
        owner = TierColor(Color(0xFF14B8A6), Color(0x2414B8A6)),
        known = TierColor(Color(0xFF6366F1), Color(0x1F6366F1)),
        kid = TierColor(Color(0xFFD97706), Color(0x26FBBF24)),
        unknown = TierColor(Color(0xFFE11D48), Color(0x24FB7185)),
    ),
)
