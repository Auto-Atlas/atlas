package app.eve.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** radius tokens, VERBATIM from tokens.json `radius`. `pill` = 999 -> fully rounded. */
@Immutable
data class EveShapes(
    val xs: RoundedCornerShape = RoundedCornerShape(6.dp),
    val sm: RoundedCornerShape = RoundedCornerShape(10.dp),
    val md: RoundedCornerShape = RoundedCornerShape(14.dp),
    val lg: RoundedCornerShape = RoundedCornerShape(18.dp),
    val xl: RoundedCornerShape = RoundedCornerShape(24.dp),
    val xxl: RoundedCornerShape = RoundedCornerShape(30.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

val EveShape = EveShapes()
