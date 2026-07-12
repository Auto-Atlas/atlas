package app.eve.ui.approvals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveTheme

/**
 * Live "Agent Activity" section (live-delegation-approvals): one card per delegated task
 * (Hermes/Claude/Codex over the talk-back fabric) with a live step feed, an at-a-glance
 * state chip, and HONEST Cancel / Redirect controls — a disabled Redirect always shows why,
 * and a dropped stream shows RECONNECTING instead of a frozen feed that looks live.
 */
@Composable
fun AgentActivitySection(
    cards: List<AgentTaskCard>,
    streamHealthy: Boolean,
    onCancel: (String) -> Unit,
    onRedirect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    // Live tasks always show; the terminal tail stays short so the section never buries
    // the approvals inbox below it.
    val visible = cards.filter { !it.isTerminal } + cards.filter { it.isTerminal }.take(3)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Agent Activity",
                style = EveTheme.type.label.copy(color = colors.textSecondary),
                modifier = Modifier.weight(1f),
            )
            val (badgeColor, badgeText) =
                if (streamHealthy) colors.success to "LIVE" else colors.warning to "RECONNECTING…"
            Canvas(Modifier.size(7.dp)) {
                drawCircle(color = badgeColor, center = Offset(size.width / 2, size.height / 2))
            }
            Spacer(Modifier.width(EveTheme.spacing.s1))
            Text(badgeText, style = EveTheme.type.micro.copy(color = badgeColor))
        }
        Spacer(Modifier.size(EveTheme.spacing.s2))

        if (visible.isEmpty()) {
            Text(
                "No agents working right now",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                modifier = Modifier.padding(vertical = EveTheme.spacing.s2),
            )
        } else {
            var detailId by rememberSaveable { mutableStateOf<String?>(null) }
            Column(verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s3)) {
                visible.forEach { card ->
                    AgentTaskActivityCard(
                        card = card, onCancel = onCancel, onRedirect = onRedirect,
                        onOpenDetail = { detailId = card.id },
                    )
                }
            }
            // Tap-for-detail: the full play-by-play of whatever agent the owner tapped.
            // Cards keep flowing live underneath; the dialog reads the LATEST card state,
            // so open detail updates in real time too.
            val detailCard = cards.firstOrNull { it.id == detailId }
            if (detailCard != null) {
                AgentTaskDetailDialog(
                    card = detailCard,
                    onDismiss = { detailId = null },
                    onCancel = onCancel,
                    onRedirect = onRedirect,
                )
            }
        }
    }
}

/**
 * Full detail of one delegated task: complete task text, the ENTIRE live step feed, and
 * the untruncated final result — plus the same honest Cancel/Redirect controls for tasks
 * that support them. Live: recomposes as new events fold into the card.
 */
@Composable
private fun AgentTaskDetailDialog(
    card: AgentTaskCard,
    onDismiss: () -> Unit,
    onCancel: (String) -> Unit,
    onRedirect: (String, String) -> Unit,
) {
    val colors = EveTheme.colors
    val stateColor = agentStateColor(card.state)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(EveTheme.shape.md)
                .background(colors.surfaceRaised)
                .padding(EveTheme.spacing.s5),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(9.dp)) {
                    drawCircle(color = stateColor, center = Offset(size.width / 2, size.height / 2))
                }
                Spacer(Modifier.width(EveTheme.spacing.s2))
                Text(agentStateLabel(card.state), style = EveTheme.type.label.copy(color = stateColor))
                Spacer(Modifier.width(EveTheme.spacing.s3))
                Text(
                    card.agent,
                    style = EveTheme.type.title.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Close", style = EveTheme.type.label.copy(color = colors.textSecondary))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (card.taskText.isNotBlank()) {
                    Spacer(Modifier.size(EveTheme.spacing.s3))
                    Text("Task", style = EveTheme.type.micro.copy(color = colors.textSecondary))
                    Spacer(Modifier.size(EveTheme.spacing.s1))
                    Text(card.taskText, style = EveTheme.type.bodySm.copy(color = colors.textPrimary))
                }
                if (card.state == AgentTaskState.WaitingOnYou && !card.question.isNullOrBlank()) {
                    Spacer(Modifier.size(EveTheme.spacing.s3))
                    Text("Asking: ${card.question}", style = EveTheme.type.bodySm.copy(color = colors.warning))
                }
                if (card.feed.isNotEmpty()) {
                    Spacer(Modifier.size(EveTheme.spacing.s3))
                    Text("Activity", style = EveTheme.type.micro.copy(color = colors.textSecondary))
                    Spacer(Modifier.size(EveTheme.spacing.s1))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        card.feed.forEach { line ->
                            Text("• $line", style = EveTheme.type.bodySm.copy(color = colors.textPrimary))
                        }
                    }
                }
                if (!card.fullResult.isNullOrBlank()) {
                    Spacer(Modifier.size(EveTheme.spacing.s3))
                    Text("Result", style = EveTheme.type.micro.copy(color = colors.textSecondary))
                    Spacer(Modifier.size(EveTheme.spacing.s1))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(card.fullResult, style = EveTheme.type.bodySm.copy(color = colors.textPrimary))
                    }
                }
                if (!card.detail.isNullOrBlank()) {
                    Spacer(Modifier.size(EveTheme.spacing.s2))
                    Text(card.detail, style = EveTheme.type.micro.copy(color = colors.textSecondary))
                }
            }
            if (!card.isTerminal && (card.canCancel || card.canRedirect)) {
                Spacer(Modifier.size(EveTheme.spacing.s3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { onCancel(card.id) },
                        enabled = card.canCancel && !card.cancelInFlight,
                    ) { Text("Cancel", style = EveTheme.type.label.copy(color = colors.danger)) }
                    Spacer(Modifier.width(EveTheme.spacing.s3))
                    var steer by rememberSaveable(card.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = steer,
                        onValueChange = { steer = it },
                        placeholder = { Text("Redirect ${card.agent}…") },
                        enabled = card.canRedirect,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onRedirect(card.id, steer); steer = "" },
                        enabled = card.canRedirect && steer.isNotBlank(),
                    ) { Text("Send", style = EveTheme.type.label.copy(color = colors.accent)) }
                }
                if (!card.canRedirect && !card.redirectReason.isNullOrBlank()) {
                    Spacer(Modifier.size(EveTheme.spacing.s1))
                    Text(card.redirectReason, style = EveTheme.type.micro.copy(color = colors.textSecondary))
                }
            }
        }
    }
}

@Composable
private fun AgentTaskActivityCard(
    card: AgentTaskCard,
    onCancel: (String) -> Unit,
    onRedirect: (String, String) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val colors = EveTheme.colors
    val stateColor = agentStateColor(card.state)
    var redirectOpen by rememberSaveable(card.id) { mutableStateOf(false) }
    var redirectText by rememberSaveable(card.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.md)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, stateColor.copy(alpha = 0.35f), EveTheme.shape.md)
            .clickable(onClick = onOpenDetail)
            .padding(EveTheme.spacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(8.dp)) {
                drawCircle(color = stateColor, center = Offset(size.width / 2, size.height / 2))
            }
            Spacer(Modifier.width(EveTheme.spacing.s2))
            Text(agentStateLabel(card.state), style = EveTheme.type.micro.copy(color = stateColor))
            Spacer(Modifier.width(EveTheme.spacing.s3))
            Text(
                card.agent,
                style = EveTheme.type.label.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
            )
        }
        if (card.taskText.isNotBlank()) {
            Spacer(Modifier.size(EveTheme.spacing.s1))
            Text(card.taskText, style = EveTheme.type.bodySm.copy(color = colors.textPrimary), maxLines = 2)
        }
        if (card.state == AgentTaskState.WaitingOnYou && !card.question.isNullOrBlank()) {
            Spacer(Modifier.size(EveTheme.spacing.s2))
            Text(
                "Asking: ${card.question}",
                style = EveTheme.type.bodySm.copy(color = colors.warning),
            )
        }
        // The live step feed: newest lines, most recent last (the whole history stays in
        // the card model; this keeps the section compact).
        val tail = card.feed.takeLast(6)
        if (tail.isNotEmpty()) {
            Spacer(Modifier.size(EveTheme.spacing.s2))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                tail.forEach { line ->
                    Text("• $line", style = EveTheme.type.micro.copy(color = colors.textSecondary), maxLines = 2)
                }
            }
        }
        if (!card.detail.isNullOrBlank()) {
            Spacer(Modifier.size(EveTheme.spacing.s2))
            Text(card.detail, style = EveTheme.type.micro.copy(color = colors.textSecondary))
        }

        if (!card.isTerminal) {
            Spacer(Modifier.size(EveTheme.spacing.s3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onCancel(card.id) },
                    enabled = card.canCancel && !card.cancelInFlight,
                ) {
                    if (card.cancelInFlight) {
                        CircularProgressIndicator(
                            color = colors.danger, strokeWidth = 2.dp, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(EveTheme.spacing.s2))
                    }
                    Text("Cancel", style = EveTheme.type.label.copy(color = colors.danger))
                }
                Spacer(Modifier.width(EveTheme.spacing.s3))
                OutlinedButton(
                    onClick = { redirectOpen = !redirectOpen },
                    enabled = card.canRedirect && !card.redirectInFlight,
                ) {
                    if (card.redirectInFlight) {
                        CircularProgressIndicator(
                            color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(EveTheme.spacing.s2))
                    }
                    Text("Redirect", style = EveTheme.type.label.copy(color = colors.accent))
                }
            }
            if (!card.canRedirect && !card.redirectReason.isNullOrBlank()) {
                // Buttons reflect truth: the disabled Redirect carries its reason, never a
                // dead no-op (goal guardrail).
                Spacer(Modifier.size(EveTheme.spacing.s1))
                Text(card.redirectReason, style = EveTheme.type.micro.copy(color = colors.textSecondary))
            }
            if (redirectOpen && card.canRedirect) {
                Spacer(Modifier.size(EveTheme.spacing.s2))
                OutlinedTextField(
                    value = redirectText,
                    onValueChange = { redirectText = it },
                    placeholder = { Text("New instructions for ${card.agent}…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            onRedirect(card.id, redirectText)
                            redirectOpen = false
                            redirectText = ""
                        },
                        enabled = redirectText.isNotBlank(),
                    ) { Text("Send", style = EveTheme.type.label.copy(color = colors.accent)) }
                }
            }
        }
    }
}

/** At-a-glance state label (goal: state visually obvious — color + label). */
fun agentStateLabel(state: AgentTaskState): String = when (state) {
    AgentTaskState.Working -> "Working"
    AgentTaskState.WaitingOnYou -> "Waiting on you"
    AgentTaskState.CancelPending -> "Cancelling…"
    AgentTaskState.Done -> "Done"
    AgentTaskState.Failed -> "Failed"
    AgentTaskState.Cancelled -> "Cancelled"
}

@Composable
private fun agentStateColor(state: AgentTaskState): Color {
    val colors = EveTheme.colors
    return when (state) {
        AgentTaskState.Working -> colors.accent
        AgentTaskState.WaitingOnYou -> colors.warning
        AgentTaskState.CancelPending -> colors.warning
        AgentTaskState.Done -> colors.success
        AgentTaskState.Failed -> colors.danger
        AgentTaskState.Cancelled -> colors.textSecondary
    }
}
