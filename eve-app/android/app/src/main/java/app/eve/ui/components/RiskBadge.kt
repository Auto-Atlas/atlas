package app.eve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.eve.ui.skills.RiskLevel
import app.eve.ui.theme.EveTheme

/**
 * Risk-tier pill for a skill. Color encodes Atlas's caution level, but color is NEVER the sole
 * signal — the chip always carries the word (a11y, red-green safe), like TierChip.
 */
@Composable
fun RiskBadge(risk: RiskLevel, modifier: Modifier = Modifier) {
    val tier = EveTheme.colors.tier
    val (label, fg, bg) = when (risk) {
        RiskLevel.High -> Triple("High risk", tier.unknown.fg, tier.unknown.soft)
        RiskLevel.Medium -> Triple("Medium", tier.kid.fg, tier.kid.soft)
        RiskLevel.Low -> Triple("Low", tier.known.fg, tier.known.soft)
    }
    Text(
        text = label,
        style = EveTheme.type.micro.copy(color = fg),
        modifier = modifier.clip(EveTheme.shape.pill).background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
