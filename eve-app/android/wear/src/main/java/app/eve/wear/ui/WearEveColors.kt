package app.eve.wear.ui

import androidx.compose.ui.graphics.Color

/**
 * EVE brand colors for the watch, ported VERBATIM from :app ui/theme/Color.kt (color.dark — the
 * primary brand theme) and the risk hues from :app ui/components/RiskBadge.kt. Copied (not shared)
 * because :app's theme depends on Compose-for-phone material3 the :wear module doesn't pull in;
 * keeping the raw values here avoids that coupling.
 *
 * ---- keep in sync with :app ui/theme/Color.kt (EveDarkColors) and RiskBadge.kt ----
 */
object WearEveColors {
    // Surfaces / text — EveDarkColors.
    val background = Color(0xFF0B0F14)   // surfaceCanvas
    val surface = Color(0xFF11161D)      // surfaceRaised (chips/cards)
    val surface2 = Color(0xFF161D26)     // surfaceRaised2
    val border = Color(0xFF1E293B)       // borderDefault
    val textPrimary = Color(0xFFF8FAFC)  // textPrimary
    val textSecondary = Color(0xFF94A3B8) // textSecondary
    val textTertiary = Color(0xFF64748B) // textTertiary

    val accent = Color(0xFF2DD4BF)       // accent
    val accentSoft = Color(0x1F2DD4BF)   // accentSoft (token #2DD4BF1F)
    val success = Color(0xFF34D399)      // success
    val warning = Color(0xFFFBBF24)      // warning
    val danger = Color(0xFFF87171)       // danger

    // Trust-tier pairs — EveDarkColors.tier (fg, soft).
    val tierOwnerFg = Color(0xFF2DD4BF)
    val tierOwnerSoft = Color(0x242DD4BF)
    val tierKnownFg = Color(0xFF818CF8)
    val tierKnownSoft = Color(0x29818CF8)
    val tierKidFg = Color(0xFFFBBF24)
    val tierKidSoft = Color(0x26FBBF24)
    val tierUnknownFg = Color(0xFFFB7185)
    val tierUnknownSoft = Color(0x24FB7185)
}

/** Trust-tier color pair for the requester chip/avatar. */
data class WearTierColor(val fg: Color, val soft: Color)

/** Tier string -> color pair + label. Mirrors :app TierChip/Avatar (lowercased match). */
fun wearTierColor(tier: String): WearTierColor = when (tier.lowercase()) {
    "owner" -> WearTierColor(WearEveColors.tierOwnerFg, WearEveColors.tierOwnerSoft)
    "known" -> WearTierColor(WearEveColors.tierKnownFg, WearEveColors.tierKnownSoft)
    "kid" -> WearTierColor(WearEveColors.tierKidFg, WearEveColors.tierKidSoft)
    else -> WearTierColor(WearEveColors.tierUnknownFg, WearEveColors.tierUnknownSoft)
}

fun wearTierLabel(tier: String): String = when (tier.lowercase()) {
    "owner" -> "Owner"
    "known" -> "Known"
    "kid" -> "Kid"
    else -> "Unknown"
}

/** Risk pair + label — ported VERBATIM from :app RiskBadge.kt (high=unknown hue, med=kid, low=known). */
fun wearRiskColor(risk: String): WearTierColor = when (risk.lowercase()) {
    "high" -> WearTierColor(WearEveColors.tierUnknownFg, WearEveColors.tierUnknownSoft)
    "medium" -> WearTierColor(WearEveColors.tierKidFg, WearEveColors.tierKidSoft)
    else -> WearTierColor(WearEveColors.tierKnownFg, WearEveColors.tierKnownSoft)
}

fun wearRiskLabel(risk: String): String = when (risk.lowercase()) {
    "high" -> "High risk"
    "medium" -> "Medium"
    else -> "Low"
}
