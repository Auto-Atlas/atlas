package app.eve.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable

/**
 * Motion tokens, VERBATIM from tokens.json `motion`. Durations in ms; eases as cubic-bezier
 * control points. `durDeliberate` (520ms) is the approve/commit beat used by
 * HoldToApproveButton (design-system.md).
 */
@Immutable
data class EveMotionTokens(
    val durInstantMs: Int = 90,
    val durFastMs: Int = 140,
    val durBaseMs: Int = 220,
    val durSlowMs: Int = 340,
    val durDeliberateMs: Int = 520,
    val easeStandard: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f),
    val easeEmphasized: Easing = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1.0f),
    val easeExit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f),
    val easeSpring: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f),
)

val EveMotion = EveMotionTokens()
