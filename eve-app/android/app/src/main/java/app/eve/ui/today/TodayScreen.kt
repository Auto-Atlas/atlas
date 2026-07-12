package app.eve.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eve.data.models.Today
import app.eve.ui.theme.EveTheme

@Composable
fun TodayScreen(viewModel: TodayViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EveTheme.colors
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EveTheme.spacing.gutterScreen, vertical = EveTheme.spacing.s5),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        when (val s = state) {
            is TodayUiState.Loading -> {
                Header(user = null, refreshing = false, onRefresh = viewModel::refresh)
                Text(
                    "Gathering today's plan…",
                    style = EveTheme.type.body.copy(color = colors.textSecondary),
                )
            }

            is TodayUiState.Error -> {
                Header(user = null, refreshing = false, onRefresh = viewModel::refresh)
                Text(s.message, style = EveTheme.type.body.copy(color = colors.warning))
            }

            is TodayUiState.Empty -> {
                Header(user = null, refreshing = false, onRefresh = viewModel::refresh)
                Text(
                    "Today's ritual hasn't been set yet. It lands after the 5 AM strategy.",
                    style = EveTheme.type.body.copy(color = colors.textSecondary),
                )
            }

            is TodayUiState.Loaded -> Loaded(
                state = s,
                onRefresh = viewModel::refresh,
                onToggle = viewModel::toggle,
            )
        }

        Spacer(Modifier.height(EveTheme.spacing.s8))
    }
}

@Composable
private fun Loaded(
    state: TodayUiState.Loaded,
    onRefresh: () -> Unit,
    onToggle: (Int, Boolean) -> Unit,
) {
    val today = state.today
    Header(user = today.user, refreshing = state.refreshing, onRefresh = onRefresh)

    if (today.whys.isNotEmpty()) WhysCard(today.whys)

    if (today.actionItems.isNotEmpty()) {
        ActionItemsHeader(done = state.doneCount, total = state.total)
        today.actionItems.forEachIndexed { index, item ->
            ActionItemCard(
                text = item,
                checked = index in state.checked,
                onCheckedChange = { onToggle(index, it) },
            )
        }
    }

    if (today.goals.isNotEmpty()) GoalsSection(today.goals)

    if (today.strategy.isNotBlank()) StrategySection(today.strategy)
}

@Composable
private fun Header(user: String?, refreshing: Boolean, onRefresh: () -> Unit) {
    val colors = EveTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Today",
                style = EveTheme.type.display.copy(color = colors.textPrimary),
            )
            Text(
                if (!user.isNullOrBlank()) "Your morning, ${user}." else "Your morning, made to stick.",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            )
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh today",
                tint = if (refreshing) colors.textTertiary else colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Quiet, reverent recitation of why he gets up — small, anchored, never shouty. */
@Composable
private fun WhysCard(whys: List<String>) {
    val colors = EveTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, colors.accentLine, EveTheme.shape.lg)
            .padding(EveTheme.spacing.padCard),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s3),
    ) {
        Text(
            "WHY YOU GET UP",
            style = EveTheme.type.micro.copy(color = colors.accent),
        )
        whys.forEach { why ->
            Text(
                why,
                style = EveTheme.type.bodyLg.copy(
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun ActionItemsHeader(done: Int, total: Int) {
    val colors = EveTheme.colors
    Spacer(Modifier.height(EveTheme.spacing.s2))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Today's moves",
            style = EveTheme.type.title.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(
            "$done / $total",
            style = EveTheme.type.label.copy(
                color = if (total > 0 && done == total) colors.success else colors.textSecondary,
            ),
        )
    }
}

/** The hero: a large, tappable card. Whole surface toggles the check — satisfying to tick off. */
@Composable
private fun ActionItemCard(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = EveTheme.colors
    val border by animateColorAsState(
        if (checked) colors.successSoft else colors.borderDefault,
        label = "actionBorder",
    )
    val bg by animateColorAsState(
        if (checked) colors.successSoft else colors.surfaceRaised,
        label = "actionBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(bg)
            .border(EveTheme.layout.borderHairline, border, EveTheme.shape.lg)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(EveTheme.spacing.padCard),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s3),
    ) {
        CheckDot(checked = checked)
        Text(
            text = text,
            style = EveTheme.type.bodyLg.copy(
                color = if (checked) colors.textTertiary else colors.textPrimary,
                fontWeight = if (checked) FontWeight.Normal else FontWeight.SemiBold,
                textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A round check target that fills + pops the tick on completion. */
@Composable
private fun CheckDot(checked: Boolean) {
    val colors = EveTheme.colors
    val fill by animateColorAsState(
        if (checked) colors.success else Color.Transparent,
        label = "checkFill",
    )
    val ring by animateColorAsState(
        if (checked) colors.success else colors.borderStrong,
        label = "checkRing",
    )
    val tickScale by animateFloatAsState(if (checked) 1f else 0f, label = "checkTick")
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .border(2.dp, ring, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = colors.textOnAccent,
            modifier = Modifier
                .size(16.dp)
                .scale(tickScale),
        )
    }
}

@Composable
private fun GoalsSection(goals: Map<String, List<String>>) {
    val colors = EveTheme.colors
    Spacer(Modifier.height(EveTheme.spacing.s2))
    Text("Goals this season", style = EveTheme.type.title.copy(color = colors.textPrimary))
    goals.forEach { (domain, lines) ->
        if (lines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EveTheme.shape.md)
                    .background(colors.surfaceRaised)
                    .border(EveTheme.layout.borderHairline, colors.borderDefault, EveTheme.shape.md)
                    .padding(EveTheme.spacing.padCard),
                verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
            ) {
                Text(
                    domain.uppercase(),
                    style = EveTheme.type.micro.copy(color = colors.accent),
                )
                lines.forEach { line ->
                    Text(
                        "• $line",
                        style = EveTheme.type.body.copy(color = colors.textSecondary),
                    )
                }
            }
        }
    }
}

/** Today's narrative strategy — collapsed by default, the action cards are the headline. */
@Composable
private fun StrategySection(strategy: String) {
    val colors = EveTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.height(EveTheme.spacing.s2))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, colors.borderDefault, EveTheme.shape.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = expanded,
                    role = Role.Button,
                    onValueChange = { expanded = it },
                )
                .padding(EveTheme.spacing.padCard),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Today's strategy",
                style = EveTheme.type.headline.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse strategy" else "Expand strategy",
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                strategy,
                style = EveTheme.type.body.copy(color = colors.textSecondary),
                modifier = Modifier.padding(
                    start = EveTheme.spacing.padCard,
                    end = EveTheme.spacing.padCard,
                    bottom = EveTheme.spacing.padCard,
                ),
            )
        }
    }
}
