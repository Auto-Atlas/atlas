package app.eve.ui.activity

import app.eve.ASSISTANT_NAME
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eve.data.models.ConversationMessage
import app.eve.data.models.ConversationSummary
import app.eve.ui.components.SourceBadge
import app.eve.ui.theme.EveTheme

@Composable
fun ActivityScreen(viewModel: ActivityViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    // A drill-in renders OVER the list; Android back closes the detail before leaving the tab.
    val openDetail = detail
    if (openDetail != null) {
        BackHandler { viewModel.closeDetail() }
        DetailView(openDetail, onBack = { viewModel.closeDetail() }, modifier = modifier)
    } else {
        FeedView(state, onOpen = { viewModel.open(it) }, modifier = modifier)
    }
}

// ---- Feed (list of conversations) -----------------------------------------------------------

@Composable
private fun FeedView(
    state: ActivityUiState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .padding(horizontal = EveTheme.spacing.gutterScreen, vertical = EveTheme.spacing.s5),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        Text("Activity", style = EveTheme.type.titleXl.copy(color = colors.textPrimary))
        Text(
            "What $ASSISTANT_NAME has been doing.",
            style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
        )

        when (state) {
            is ActivityUiState.Loading ->
                CenterNote("Loading your activity…", colors.textSecondary)

            is ActivityUiState.Offline ->
                EmptyState(
                    title = "Desktop offline",
                    body = "Start OpenJarvis on your desktop to see your activity here.",
                )

            is ActivityUiState.Empty ->
                EmptyState(
                    title = "Nothing yet",
                    body = "Once you talk to $ASSISTANT_NAME, your conversations show up here — newest first.",
                )

            is ActivityUiState.Error ->
                EmptyState(title = "Can't reach $ASSISTANT_NAME", body = state.message)

            is ActivityUiState.Loaded ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
                ) {
                    items(state.conversations, key = { it.id }) { conv ->
                        ConversationRow(conv, onClick = { onOpen(conv.id) })
                    }
                }
        }
    }
}

@Composable
private fun ConversationRow(conv: ConversationSummary, onClick: () -> Unit) {
    val colors = EveTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, colors.borderDefault, EveTheme.shape.lg)
            .clickable(onClick = onClick)
            .padding(EveTheme.spacing.padCard),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
        ) {
            SourceBadge(conv.source)
            Spacer(Modifier.weight(1f))
            Text(
                relativeTime(conv.startedAt),
                style = EveTheme.type.caption.copy(color = colors.textTertiary),
            )
        }
        Text(
            text = conv.title.ifBlank { "(untitled)" },
            style = EveTheme.type.headline.copy(color = colors.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = statLine(conv),
            style = EveTheme.type.caption.copy(color = colors.textSecondary),
        )
    }
}

private fun statLine(conv: ConversationSummary): String {
    val parts = mutableListOf("${conv.msgCount} msgs")
    if (conv.toolCount > 0) parts += "${conv.toolCount} tools"
    if (conv.totalTokens > 0) parts += "${compactTokens(conv.totalTokens)} tokens"
    return parts.joinToString("  ·  ")
}

// ---- Detail (one conversation's message + action timeline) ----------------------------------

@Composable
private fun DetailView(
    detail: DetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .padding(horizontal = EveTheme.spacing.gutterScreen, vertical = EveTheme.spacing.s5),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(EveTheme.shape.pill)
                    .clickable(onClick = onBack)
                    .padding(end = EveTheme.spacing.s2),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to activity",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text("Conversation", style = EveTheme.type.title.copy(color = colors.textPrimary))
        }

        when (detail) {
            is DetailUiState.Loading -> CenterNote("Loading…", colors.textSecondary)
            is DetailUiState.Offline ->
                EmptyState(
                    title = "Desktop offline",
                    body = "Start OpenJarvis to read this conversation.",
                )
            is DetailUiState.Error -> EmptyState(title = "Can't load", body = detail.message)
            is DetailUiState.Loaded -> {
                val conv = detail.detail
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s1),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
                    ) {
                        SourceBadge(conv.source)
                        Text(
                            relativeTime(conv.startedAt),
                            style = EveTheme.type.caption.copy(color = colors.textTertiary),
                        )
                    }
                    Text(
                        conv.title.ifBlank { "(untitled)" },
                        style = EveTheme.type.headline.copy(color = colors.textPrimary),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s3),
                ) {
                    items(conv.messages, key = { it.seq }) { msg ->
                        when {
                            msg.role == "delegation" -> DelegationRow(msg)
                            msg.role == "tool" -> ToolRow(msg)
                            msg.text.isNotBlank() -> ChatBubble(msg)
                            // silent assistant placeholders / empty turns are skipped
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ConversationMessage) {
    val colors = EveTheme.colors
    val isUser = msg.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    val bubble = if (isUser) colors.accentSoft else colors.surfaceRaised
    val border = if (isUser) colors.accentLine else colors.borderDefault
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text = if (isUser) "You" else "$ASSISTANT_NAME",
            style = EveTheme.type.micro.copy(color = colors.textTertiary),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Box(
            modifier = Modifier
                .clip(EveTheme.shape.md)
                .background(bubble)
                .border(EveTheme.layout.borderHairline, border, EveTheme.shape.md)
                .padding(horizontal = EveTheme.spacing.s4, vertical = EveTheme.spacing.s3),
        ) {
            Text(msg.text, style = EveTheme.type.body.copy(color = colors.textPrimary))
        }
    }
}

/** A tool call — reads as an ACTION Atlas took, not a chat bubble. */
@Composable
private fun ToolRow(msg: ConversationMessage) {
    val colors = EveTheme.colors
    val failed = msg.ok == false || msg.status.equals("error", ignoreCase = true)
    val accent = if (failed) colors.danger else colors.accent
    ActionCard(
        icon = { Icon(Icons.Filled.Build, null, tint = accent, modifier = Modifier.size(16.dp)) },
        accent = accent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tool",
                style = EveTheme.type.micro.copy(color = colors.textTertiary),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (failed) "failed" else "ran",
                style = EveTheme.type.micro.copy(color = accent),
            )
        }
        Text(
            msg.toolName ?: "tool",
            style = EveTheme.type.headline.copy(color = colors.textPrimary),
        )
        msg.args?.takeIf { it != "{}" && it.isNotBlank() }?.let {
            Text(
                it,
                style = EveTheme.type.caption.copy(color = colors.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (failed) {
            msg.detail?.let {
                Text(
                    it,
                    style = EveTheme.type.caption.copy(color = colors.danger),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An agent delegation — Atlas handed work to another brain (hermes / codex / …). */
@Composable
private fun DelegationRow(msg: ConversationMessage) {
    val colors = EveTheme.colors
    val failed = msg.ok == false
    val accent = if (failed) colors.danger else colors.accent2
    ActionCard(
        icon = { Icon(Icons.Filled.AccountTree, null, tint = accent, modifier = Modifier.size(16.dp)) },
        accent = accent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Delegated", style = EveTheme.type.micro.copy(color = colors.textTertiary))
            Spacer(Modifier.weight(1f))
            val brains = msg.delegationBrains
            if (brains.isNotEmpty()) {
                Text(
                    brains.joinToString(" → "),
                    style = EveTheme.type.micro.copy(color = accent),
                )
            }
        }
        msg.task?.let {
            Text(
                it,
                style = EveTheme.type.body.copy(color = colors.textPrimary),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val resolvedBy = msg.brain
        msg.result?.takeIf { it.isNotBlank() }?.let {
            Text(
                (if (resolvedBy != null) "$resolvedBy: " else "") + it,
                style = EveTheme.type.caption.copy(color = colors.textSecondary),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        msg.totalLatencyMs?.takeIf { it > 0 }?.let {
            Text(
                "${it / 1000}s",
                style = EveTheme.type.micro.copy(color = colors.textTertiary),
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: @Composable () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = EveTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(EveTheme.shape.md)
            .background(colors.surfaceSunken)
            .border(EveTheme.layout.borderHairline, colors.borderSubtle, EveTheme.shape.md),
    ) {
        // A left accent rail visually separates an action from a chat bubble at a glance.
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EveTheme.spacing.s4),
            verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s1),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
            }
            content()
        }
    }
}

// ---- shared small pieces --------------------------------------------------------------------

@Composable
private fun EmptyState(title: String, body: String) {
    val colors = EveTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = EveTheme.spacing.s9),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        Text(title, style = EveTheme.type.title.copy(color = colors.textPrimary))
        Text(
            body,
            style = EveTheme.type.body.copy(color = colors.textSecondary),
            modifier = Modifier.padding(horizontal = EveTheme.spacing.s6),
        )
    }
}

@Composable
private fun CenterNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = EveTheme.spacing.s9),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(text, style = EveTheme.type.body.copy(color = color))
    }
}

// ---- formatting -----------------------------------------------------------------------------

/** "just now" / "12m ago" / "3h ago" / "2d ago" from an epoch-millis timestamp. */
internal fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val diff = (now - epochMs).coerceAtLeast(0L)
    val sec = diff / 1000
    val min = sec / 60
    val hr = min / 60
    val day = hr / 24
    return when {
        sec < 45 -> "just now"
        min < 60 -> "${min}m ago"
        hr < 24 -> "${hr}h ago"
        day < 7 -> "${day}d ago"
        else -> "${day / 7}w ago"
    }
}

/** 116819 -> "117k", 1500 -> "1.5k", 800 -> "800". */
internal fun compactTokens(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 100_000 -> {
        val k = n / 1000.0
        if (k >= 10) "${k.toInt()}k" else String.format("%.1fk", k)
    }
    else -> "${n / 1000}k"
}
