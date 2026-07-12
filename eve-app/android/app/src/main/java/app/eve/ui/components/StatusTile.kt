package app.eve.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme

/** Health signal for a tile's glow-dot — paired with the value so health is never color-alone. */
enum class TileStatus { Ok, Warn, Bad }

/** A single status metric tile: optional leading icon + a status glow-dot, a large value, a label. */
@Composable
fun StatusTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    status: TileStatus? = null,
    accent: Color? = null,
) {
    val colors = EveTheme.colors
    Column(
        modifier = modifier
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, colors.borderDefault, EveTheme.shape.lg)
            .padding(EveTheme.spacing.s4)
            .fillMaxWidth(),
    ) {
        if (icon != null || status != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null, // label carries the name; dot carries health
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (status != null) {
                    val dot = when (status) {
                        TileStatus.Ok -> colors.success
                        TileStatus.Warn -> colors.warning
                        TileStatus.Bad -> colors.danger
                    }
                    // Dot + soft halo (a non-color-alone health signal, glowing in the design tone).
                    Canvas(Modifier.size(10.dp)) {
                        val r = size.minDimension / 2f
                        drawCircle(color = dot.copy(alpha = 0.25f), radius = r)
                        drawCircle(color = dot, radius = r * 0.6f)
                    }
                }
            }
            Spacer(Modifier.height(EveTheme.spacing.s2))
        }
        Text(
            text = value,
            style = EveTheme.type.titleXl.copy(color = accent ?: colors.textPrimary),
        )
        Text(
            text = label.uppercase(),
            style = EveTheme.type.micro.copy(color = colors.textSecondary),
        )
    }
}
