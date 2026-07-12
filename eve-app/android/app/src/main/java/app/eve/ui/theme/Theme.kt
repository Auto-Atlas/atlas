package app.eve.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * EveTheme exposes the design tokens via CompositionLocals so screens read them as
 * `EveTheme.colors`, `EveTheme.type`, etc. Dark is the default (tokens.$meta.primaryTheme).
 * We deliberately do NOT wrap Material3's MaterialTheme color scheme as the primary API —
 * the design system is bespoke — but a minimal Material surface is still provided so
 * material3 components (ripples, text selection) inherit sane defaults.
 */

private val LocalEveColors = staticCompositionLocalOf { EveDarkColors }
private val LocalEveType = staticCompositionLocalOf { EveType }
private val LocalEveSpacing = staticCompositionLocalOf { EveSpace }
private val LocalEveControl = staticCompositionLocalOf { EveControls }
private val LocalEveLayout = staticCompositionLocalOf { EveLayoutTokens }
private val LocalEveShape = staticCompositionLocalOf { EveShape }
private val LocalEveElevation = staticCompositionLocalOf { EveElevation }
private val LocalEveMotion = staticCompositionLocalOf { EveMotion }
private val LocalContentColor = staticCompositionLocalOf { EveDarkColors.textPrimary }

object EveTheme {
    val colors: EveColorScheme
        @Composable @ReadOnlyComposable get() = LocalEveColors.current
    val type: EveTypography
        @Composable @ReadOnlyComposable get() = LocalEveType.current
    val spacing: EveSpacing
        @Composable @ReadOnlyComposable get() = LocalEveSpacing.current
    val control: EveControl
        @Composable @ReadOnlyComposable get() = LocalEveControl.current
    val layout: EveLayout
        @Composable @ReadOnlyComposable get() = LocalEveLayout.current
    val shape: EveShapes
        @Composable @ReadOnlyComposable get() = LocalEveShape.current
    val elevation: EveElevations
        @Composable @ReadOnlyComposable get() = LocalEveElevation.current
    val motion: EveMotionTokens
        @Composable @ReadOnlyComposable get() = LocalEveMotion.current
    val contentColor: Color
        @Composable @ReadOnlyComposable get() = LocalContentColor.current
}

@Composable
fun EveTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) EveDarkColors else EveLightColors
    CompositionLocalProvider(
        LocalEveColors provides colors,
        LocalEveType provides EveType,
        LocalEveSpacing provides EveSpace,
        LocalEveControl provides EveControls,
        LocalEveLayout provides EveLayoutTokens,
        LocalEveShape provides EveShape,
        LocalEveElevation provides EveElevation,
        LocalEveMotion provides EveMotion,
        LocalContentColor provides colors.textPrimary,
        content = content,
    )
}

/** Convenience: a body TextStyle pre-tinted with the current content color. */
@Composable
@ReadOnlyComposable
fun bodyOn(color: Color = EveTheme.contentColor): TextStyle =
    EveTheme.type.body.copy(color = color)
