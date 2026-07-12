package app.eve.ui.skills

import app.eve.ASSISTANT_NAME
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.eve.data.models.FeedMode
import app.eve.ui.components.EveButton
import app.eve.ui.components.EveButtonStyle
import app.eve.ui.components.RiskBadge
import app.eve.ui.theme.EveTheme

@Composable
fun SkillsScreen(viewModel: SkillsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    SkillsContent(
        state = state,
        onFeed = viewModel::feed,
        onUnprime = viewModel::unprime,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsContent(
    state: SkillsUiState,
    onFeed: (String, FeedMode) -> Unit,
    onUnprime: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    var sheetFor by remember { mutableStateOf<SkillRow?>(null) }

    Column(modifier.fillMaxSize().padding(EveTheme.spacing.s4)) {
        Text("Skills", style = EveTheme.type.title.copy(color = colors.textPrimary))
        Text(
            "Things $ASSISTANT_NAME knows how to do — hand one over.",
            style = EveTheme.type.body.copy(color = colors.textSecondary),
            modifier = Modifier.padding(top = EveTheme.spacing.s1, bottom = EveTheme.spacing.s3),
        )
        when (state) {
            is SkillsUiState.Loading ->
                Text("Loading…", style = EveTheme.type.body.copy(color = colors.textSecondary))
            is SkillsUiState.Offline ->
                Text("Off the tailnet — can't reach $ASSISTANT_NAME.", style = EveTheme.type.body.copy(color = colors.textSecondary))
            is SkillsUiState.Error ->
                Text(state.message, style = EveTheme.type.body.copy(color = colors.danger))
            is SkillsUiState.Loaded -> LazyColumn(verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2)) {
                state.groups.forEach { group ->
                    item(key = "h-${group.risk}") {
                        Text(
                            group.risk.name.uppercase(),
                            style = EveTheme.type.caption.copy(color = colors.textTertiary),
                            modifier = Modifier.padding(top = EveTheme.spacing.s2),
                        )
                    }
                    items(group.rows, key = { it.tool }) { row ->
                        SkillRowItem(row, onOpen = { sheetFor = row }, onUnprime = onUnprime)
                    }
                }
            }
        }
    }

    sheetFor?.let { row ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.fillMaxWidth().padding(EveTheme.spacing.s4)) {
                Text(row.tool, style = EveTheme.type.headline.copy(color = colors.textPrimary))
                Text(
                    row.catalog,
                    style = EveTheme.type.body.copy(color = colors.textSecondary),
                    modifier = Modifier.padding(vertical = EveTheme.spacing.s2),
                )
                EveButton(
                    text = "Use now — $ASSISTANT_NAME is listening",
                    onClick = { onFeed(row.tool, FeedMode.Live); sheetFor = null },
                )
                Spacer(Modifier.height(EveTheme.spacing.s2))
                EveButton(
                    text = "Save for next chat",
                    style = EveButtonStyle.Subtle,
                    onClick = { onFeed(row.tool, FeedMode.Next); sheetFor = null },
                )
            }
        }
    }
}

@Composable
private fun SkillRowItem(row: SkillRow, onOpen: () -> Unit, onUnprime: (String) -> Unit) {
    val colors = EveTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = EveTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.tool, style = EveTheme.type.body.copy(color = colors.textPrimary))
            Text(row.catalog, style = EveTheme.type.caption.copy(color = colors.textSecondary))
            Row(horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s1)) {
                RiskBadge(row.risk)
                if (row.requiresConfirmation) {
                    Text("Asks first 🔒", style = EveTheme.type.micro.copy(color = colors.textTertiary))
                }
                when (row.feedState) {
                    FeedState.PrimedForNext -> Text(
                        "Primed for next chat ✕",
                        style = EveTheme.type.micro.copy(color = colors.accent),
                        modifier = Modifier.clickable { onUnprime(row.tool) },
                    )
                    FeedState.HandedToEve -> Text("Handed to $ASSISTANT_NAME", style = EveTheme.type.micro.copy(color = colors.accent))
                    FeedState.Sending -> Text("Sending…", style = EveTheme.type.micro.copy(color = colors.textTertiary))
                    FeedState.Expired -> Text("Didn't catch it", style = EveTheme.type.micro.copy(color = colors.textTertiary))
                    FeedState.Idle -> Unit
                }
            }
        }
    }
}
