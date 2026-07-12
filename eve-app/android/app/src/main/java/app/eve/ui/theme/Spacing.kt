package app.eve.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (4px grid) + semantic spacing, ported VERBATIM from tokens.json `space`. */
@Immutable
data class EveSpacing(
    val s0: Dp = 0.dp,
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s7: Dp = 32.dp,
    val s8: Dp = 40.dp,
    val s9: Dp = 48.dp,
    val s10: Dp = 56.dp,
    val s11: Dp = 64.dp,
    val s12: Dp = 80.dp,
    // semantic
    val gutterScreen: Dp = 20.dp,
    val gapCard: Dp = 14.dp,
    val padCard: Dp = 18.dp,
    val padCardLg: Dp = 22.dp,
)

/** control + layout token groups. */
@Immutable
data class EveControl(
    val sm: Dp = 36.dp,
    val md: Dp = 44.dp,
    val lg: Dp = 52.dp,
)

@Immutable
data class EveLayout(
    val screenMax: Dp = 440.dp,
    val tabbarHeight: Dp = 64.dp,
    val headerHeight: Dp = 56.dp,
    val borderHairline: Dp = 1.dp,
)

val EveSpace = EveSpacing()
val EveControls = EveControl()
val EveLayoutTokens = EveLayout()
