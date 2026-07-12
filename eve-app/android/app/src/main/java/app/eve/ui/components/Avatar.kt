package app.eve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme

/** Initials avatar tinted with the requester's tier-soft color. */
@Composable
fun Avatar(name: String?, tier: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = EveTheme.colors
    val tc = when (tier.lowercase()) {
        "owner" -> colors.tier.owner
        "known" -> colors.tier.known
        "kid" -> colors.tier.kid
        else -> colors.tier.unknown
    }
    val initials = (name ?: "?")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tc.soft),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initials, style = EveTheme.type.label.copy(color = tc.fg))
    }
}
