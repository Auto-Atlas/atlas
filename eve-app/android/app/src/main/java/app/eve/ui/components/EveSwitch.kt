package app.eve.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme

/** Token-styled switch. On = accent track; off = sunken track. */
@Composable
fun EveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = EveTheme.colors
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.surfaceOverlay,
        animationSpec = tween(EveTheme.motion.durFastMs),
        label = "track",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(EveTheme.motion.durFastMs, easing = EveTheme.motion.easeStandard),
        label = "knob",
    )
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(EveTheme.shape.pill)
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.4f))
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .padding(vertical = 2.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) colors.textOnAccent else colors.textSecondary),
        )
    }
}
