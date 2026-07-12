package app.eve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme
import app.eve.ui.theme.TierColor

/**
 * Trust-tier chip. Color encodes the speaker-ID tier, but color is NEVER the sole signal —
 * the chip always carries the text label (a11y, red-green safe). Most approvals are "Known"
 * (the only tier that produces remote approvals).
 */
@Composable
fun TierChip(tier: String, modifier: Modifier = Modifier) {
    val colors = EveTheme.colors
    val (label, tc: TierColor) = when (tier.lowercase()) {
        "owner" -> "Owner" to colors.tier.owner
        "known" -> "Known" to colors.tier.known
        "kid" -> "Kid" to colors.tier.kid
        else -> "Unknown" to colors.tier.unknown
    }
    Text(
        text = label,
        style = EveTheme.type.micro.copy(color = tc.fg),
        modifier = modifier
            .clip(EveTheme.shape.pill)
            .background(tc.soft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
