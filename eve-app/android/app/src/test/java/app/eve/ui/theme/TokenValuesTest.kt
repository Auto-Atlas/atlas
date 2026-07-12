package app.eve.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asserts the Compose tokens were ported EXACTLY from eve-app/design/tokens.json. This is a
 * regression guard against drift: every literal here is the value typed in tokens.json. The
 * 8-digit #RRGGBBAA tokens are checked in their #AARRGGBB Compose form with the conversion
 * spelled out, so a wrong alpha/channel swap fails loudly.
 */
class TokenValuesTest {

    @Test
    fun dark_core_colors_match_tokens_json() {
        assertEquals(Color(0xFF0B0F14), EveDarkColors.surfaceCanvas, "color.dark.surfaceCanvas")
        assertEquals(Color(0xFF060A0E), EveDarkColors.surfaceSunken, "color.dark.surfaceSunken")
        assertEquals(Color(0xFF11161D), EveDarkColors.surfaceRaised, "color.dark.surfaceRaised")
        assertEquals(Color(0xFFF8FAFC), EveDarkColors.textPrimary, "color.dark.textPrimary")
        assertEquals(Color(0xFF94A3B8), EveDarkColors.textSecondary, "color.dark.textSecondary")
        assertEquals(Color(0xFF2DD4BF), EveDarkColors.accent, "color.dark.accent (teal)")
        assertEquals(Color(0xFF6366F1), EveDarkColors.accent2, "color.dark.accent2 (indigo)")
        assertEquals(Color(0xFF34D399), EveDarkColors.success, "color.dark.success")
        assertEquals(Color(0xFFFBBF24), EveDarkColors.warning, "color.dark.warning")
        assertEquals(Color(0xFFF87171), EveDarkColors.danger, "color.dark.danger")
        assertEquals(Color(0xFF04201C), EveDarkColors.textOnAccent, "color.dark.textOnAccent")
    }

    @Test
    fun dark_alpha_tokens_convert_rrggbbaa_to_aarrggbb() {
        // tokens.json "#2DD4BF1F" => alpha 0x1F, rgb 2DD4BF.
        assertEquals(Color(0x1F2DD4BF), EveDarkColors.accentSoft, "color.dark.accentSoft")
        // "#2DD4BF4D"
        assertEquals(Color(0x4D2DD4BF), EveDarkColors.accentLine, "color.dark.accentLine")
        // "#94A3B81A"
        assertEquals(Color(0x1A94A3B8), EveDarkColors.borderSubtle, "color.dark.borderSubtle")
        // "#F8717121"
        assertEquals(Color(0x21F87171), EveDarkColors.dangerSoft, "color.dark.dangerSoft")
    }

    @Test
    fun trust_tier_colors_match_tokens_json() {
        // tier.dark.* — known is indigo #818CF8 (the tier that produces approvals).
        assertEquals(Color(0xFF2DD4BF), EveDarkColors.tier.owner.fg, "tier.dark.owner")
        assertEquals(Color(0xFF818CF8), EveDarkColors.tier.known.fg, "tier.dark.known")
        assertEquals(Color(0xFFFBBF24), EveDarkColors.tier.kid.fg, "tier.dark.kid")
        assertEquals(Color(0xFFFB7185), EveDarkColors.tier.unknown.fg, "tier.dark.unknown")
        assertEquals(Color(0x29818CF8), EveDarkColors.tier.known.soft, "tier.dark.knownSoft")
    }

    @Test
    fun type_scale_matches_tokens_json() {
        // type.scale.display { size 34, line 40, track -0.02 }
        assertEquals(34.sp, EveType.display.fontSize, "display.size")
        assertEquals(40.sp, EveType.display.lineHeight, "display.line")
        assertEquals((-0.02).em, EveType.display.letterSpacing, "display.track")
        // body { size 15, line 22 }
        assertEquals(15.sp, EveType.body.fontSize, "body.size")
        assertEquals(22.sp, EveType.body.lineHeight, "body.line")
        // label { size 13, line 16 }
        assertEquals(13.sp, EveType.label.fontSize, "label.size")
        // micro { size 11, track 0.08 }
        assertEquals(11.sp, EveType.micro.fontSize, "micro.size")
        assertEquals(0.08.em, EveType.micro.letterSpacing, "micro.track")
        // money renders at display size in mono.
        assertEquals(34.sp, EveType.moneyDisplay.fontSize, "moneyDisplay.size == display.size")
    }

    @Test
    fun type_families_reference_the_two_token_families() {
        assertEquals(Manrope, EveType.body.fontFamily, "type.fontSans Manrope")
        assertEquals(JetBrainsMono, EveType.moneyDisplay.fontFamily, "type.fontMono JetBrains Mono")
    }

    @Test
    fun spacing_grid_and_semantic_match_tokens_json() {
        assertEquals(4.dp, EveSpace.s1, "space.s1")
        assertEquals(16.dp, EveSpace.s4, "space.s4")
        assertEquals(80.dp, EveSpace.s12, "space.s12")
        assertEquals(20.dp, EveSpace.gutterScreen, "space.gutterScreen")
        assertEquals(14.dp, EveSpace.gapCard, "space.gapCard")
        assertEquals(18.dp, EveSpace.padCard, "space.padCard")
        assertEquals(22.dp, EveSpace.padCardLg, "space.padCardLg")
    }

    @Test
    fun radius_control_and_layout_match_tokens_json() {
        // radius.lg = 18 (cards). Compare against a freshly constructed shape of the token value.
        assertEquals(
            androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            EveShape.lg,
            "radius.lg",
        )
        assertEquals(
            androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            EveShape.md,
            "radius.md (controls)",
        )
        assertEquals(44.dp, EveControls.md, "control.md")
        assertEquals(52.dp, EveControls.lg, "control.lg")
        assertEquals(440.dp, EveLayoutTokens.screenMax, "layout.screenMax")
        assertEquals(64.dp, EveLayoutTokens.tabbarHeight, "layout.tabbarHeight")
        assertEquals(1.dp, EveLayoutTokens.borderHairline, "layout.borderHairline")
    }

    @Test
    fun motion_durations_match_tokens_json() {
        assertEquals(90, EveMotion.durInstantMs, "motion.durInstantMs")
        assertEquals(220, EveMotion.durBaseMs, "motion.durBaseMs")
        // The approve/commit beat.
        assertEquals(520, EveMotion.durDeliberateMs, "motion.durDeliberateMs")
    }
}
