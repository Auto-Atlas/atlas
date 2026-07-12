package app.eve.ui.skills

import androidx.compose.runtime.Immutable

enum class RiskLevel { High, Medium, Low }

/** One row's feed status, resolved per-row (no Map/Set in UI state → cards strong-skip). */
enum class FeedState { Idle, Sending, PrimedForNext, HandedToEve, Expired }

@Immutable
data class SkillRow(
    val tool: String,
    val catalog: String,
    val risk: RiskLevel,
    val requiresConfirmation: Boolean,
    val feedState: FeedState,
)

@Immutable
data class RiskGroup(val risk: RiskLevel, val rows: List<SkillRow>)

sealed interface SkillsUiState {
    data object Loading : SkillsUiState
    data object Offline : SkillsUiState
    data class Error(val message: String) : SkillsUiState
    data class Loaded(val groups: List<RiskGroup>) : SkillsUiState
}

internal fun riskOf(raw: String): RiskLevel = when (raw.lowercase()) {
    "high" -> RiskLevel.High
    "medium" -> RiskLevel.Medium
    else -> RiskLevel.Low
}
