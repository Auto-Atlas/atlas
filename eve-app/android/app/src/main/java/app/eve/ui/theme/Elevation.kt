package app.eve.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Elevation/glow tokens, ported from tokens.json `elevation`. CSS box-shadows don't map 1:1
 * to Compose; each token is decomposed into (color, blur-radius, spread/offset) so a
 * Modifier helper can render the equivalent glow with drawBehind. Accent glow is RESERVED for
 * live/commit states (design-system.md "Elevation & motion").
 */
@Immutable
data class GlowSpec(
    val color: Color,
    val blur: Dp,
    val offsetY: Dp,
    val spread: Dp,
)

@Immutable
data class EveElevations(
    // "0 8px 30px rgba(45,212,191,0.16)"
    val glowAccent: GlowSpec = GlowSpec(Color(0x292DD4BF), blur = 30.dp, offsetY = 8.dp, spread = 0.dp),
    // "0 0 44px rgba(45,212,191,0.30)"
    val glowAccentStrong: GlowSpec = GlowSpec(Color(0x4D2DD4BF), blur = 44.dp, offsetY = 0.dp, spread = 0.dp),
    // "0 0 0 3px rgba(45,212,191,0.35)"
    val ringFocus: GlowSpec = GlowSpec(Color(0x592DD4BF), blur = 0.dp, offsetY = 0.dp, spread = 3.dp),
    // "0 0 0 3px rgba(248,113,113,0.30)"
    val ringDanger: GlowSpec = GlowSpec(Color(0x4DF87171), blur = 0.dp, offsetY = 0.dp, spread = 3.dp),
)

val EveElevation = EveElevations()
