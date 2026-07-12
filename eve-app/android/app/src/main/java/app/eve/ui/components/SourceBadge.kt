package app.eve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme

/**
 * Surface-origin chip for a conversation: phone-voice / desktop-voice / typed-chat. Color hints at
 * the channel but the text label always carries the meaning (a11y, color is never the sole signal),
 * mirroring [TierChip]. Falls back to the raw source string for any channel we don't special-case.
 */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val colors = EveTheme.colors
    val (label: String, fg: Color, soft: Color) = when (source.lowercase()) {
        "phone-voice" -> Triple("Phone", colors.accent, colors.accentSoft)
        "desktop-voice" -> Triple("Desktop", colors.accent2, colors.accent2Soft)
        "typed-chat", "typed" -> Triple("Typed", colors.textSecondary, colors.surfaceOverlay)
        else -> Triple(source.ifBlank { "Session" }, colors.textSecondary, colors.surfaceOverlay)
    }
    Text(
        text = label,
        style = EveTheme.type.micro.copy(color = fg),
        modifier = modifier
            .clip(EveTheme.shape.pill)
            .background(soft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
