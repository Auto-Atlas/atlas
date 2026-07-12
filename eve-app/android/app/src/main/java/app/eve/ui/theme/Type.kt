package app.eve.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.eve.R

/**
 * Typography ported VERBATIM from tokens.json type.scale. Two families: Manrope (UI +
 * display) and JetBrains Mono (figures, with tnum so money lines up).
 *
 * Manrope is bundled as the upstream VARIABLE font (res/font/manrope_variable.ttf, the
 * google/fonts `Manrope[wght]` instance, OFL). Each UI weight is derived from it via
 * FontVariation (`wght`), supported on minSdk 26+. JetBrains Mono ships static instances.
 *
 * tokens.json `track` is letter-spacing in em (e.g. display -0.02). Compose TextStyle.letterSpacing
 * takes a TextUnit; we express it as `.em` so it scales with font size exactly like the CSS.
 */

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private fun sans(
    size: Int,
    line: Int,
    weight: Int,
    track: Double,
): TextStyle = TextStyle(
    fontFamily = Manrope,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = track.em,
)

@Immutable
data class EveTypography(
    val display: TextStyle,
    val titleXl: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val bodyLg: TextStyle,
    val body: TextStyle,
    val bodySm: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val micro: TextStyle,
    /** Amounts render at display/titleXl size but in JetBrains Mono with tabular figures. */
    val moneyDisplay: TextStyle,
    val moneyTitle: TextStyle,
)

val EveType = EveTypography(
    // type.scale.* — { size, line, weight, track }
    display = sans(34, 40, 800, -0.02),
    titleXl = sans(28, 34, 800, -0.018),
    title = sans(22, 28, 700, -0.015),
    headline = sans(17, 24, 700, -0.011),
    bodyLg = sans(16, 24, 400, 0.0),
    body = sans(15, 22, 400, 0.0),
    bodySm = sans(14, 20, 400, 0.0),
    label = sans(13, 16, 600, 0.0),
    caption = sans(12, 16, 400, 0.0),
    // micro: upper=true is applied at call sites via text.uppercase(); track 0.08 baked in.
    micro = sans(11, 14, 700, 0.08),
    moneyDisplay = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight(700),
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.01).em,
        // JetBrains Mono is monospaced; tnum is on by default in the font so figures align.
    ),
    moneyTitle = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight(700),
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.01).em,
    ),
)
