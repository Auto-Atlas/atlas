package app.eve.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import app.eve.ui.theme.GlowSpec

/**
 * Renders a token GlowSpec (decomposed from tokens.json elevation box-shadows) behind a
 * composable. Accent glow is reserved for live/commit states (design-system.md).
 */
fun Modifier.eveGlow(spec: GlowSpec): Modifier = this.drawBehind {
    val blurPx = spec.blur.toPx()
    val offsetYPx = spec.offsetY.toPx()
    val spreadPx = spec.spread.toPx()
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            color = spec.color
            if (blurPx > 0f) {
                asFrameworkPaint().maskFilter =
                    android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawRoundRect(
            left = -spreadPx,
            top = -spreadPx + offsetYPx,
            right = size.width + spreadPx,
            bottom = size.height + spreadPx + offsetYPx,
            radiusX = 18f,
            radiusY = 18f,
            paint = paint,
        )
    }
}

@Composable
fun GlowBox(spec: GlowSpec, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.eveGlow(spec)) { content() }
}
