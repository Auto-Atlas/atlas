package app.eve.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme

/** Button intent — drives color. Deny uses danger; primary uses accent. */
enum class EveButtonStyle { Primary, Danger, Subtle }

/**
 * Token-driven button. Touch target >=48dp (Deny/Approve requirement). Disabled state is
 * visibly inert and non-clickable — actions never "silently fail" (screens/approvals.md).
 */
@Composable
fun EveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: EveButtonStyle = EveButtonStyle.Primary,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = EveTheme.colors
    val shape: RoundedCornerShape = EveTheme.shape.md
    val (bg, fg, border) = when (style) {
        EveButtonStyle.Primary -> Triple(colors.accent, colors.textOnAccent, Color.Transparent)
        EveButtonStyle.Danger -> Triple(colors.dangerSoft, colors.danger, colors.danger)
        EveButtonStyle.Subtle -> Triple(colors.surfaceRaised2, colors.textPrimary, colors.borderStrong)
    }
    val alpha = if (enabled) 1f else 0.4f

    // Press-shrink: a subtle 0.97 scale on press (spec — transform shrink, not a color flip).
    // Read deferred inside graphicsLayer so the per-frame value never recomposes the button.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "buttonScale",
    )
    // Primary CTA carries a soft accent glow (reserved for the primary action), enabled-only.
    val glow = if (style == EveButtonStyle.Primary && enabled) {
        Modifier.eveGlow(EveTheme.elevation.glowAccent)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
            .defaultMinSize(minHeight = 48.dp)
            .then(glow)
            .clip(shape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .border(EveTheme.layout.borderHairline, border.copy(alpha = border.alpha * alpha), shape)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClickLabel = contentDescription,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = EveTheme.spacing.s5, vertical = EveTheme.spacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            style = EveTheme.type.label.copy(color = fg.copy(alpha = fg.alpha * alpha)),
            textAlign = TextAlign.Center,
        )
    }
}
