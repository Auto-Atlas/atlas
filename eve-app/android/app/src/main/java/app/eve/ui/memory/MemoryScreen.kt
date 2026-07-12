package app.eve.ui.memory

import app.eve.ASSISTANT_NAME
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eve.data.models.MemoryItem
import app.eve.ui.components.EveButton
import app.eve.ui.theme.EveColorScheme
import app.eve.ui.theme.EveTheme

/**
 * "What Atlas knows about you" — the owner's canonical fact vault, rendered from the structured
 * `items` of GET /v1/memory. Facts are grouped under calm category headers, each carrying a date
 * chip and a subtle category accent. A live search filters client-side; an add box writes a new
 * fact straight to the owner page. Intimate by design — this is what your assistant remembers.
 */
@Composable
fun MemoryScreen(viewModel: MemoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EveTheme.colors
    var factDraft by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // Load the real vault on open — no typing required to see Atlas's memory.
    LaunchedEffect(Unit) { viewModel.load() }
    // One-shot feedback ("Saved.", errors) — collected once, never replayed on rotation.
    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        cursorColor = colors.accent,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderDefault,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textTertiary,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary,
    )

    Scaffold(
        modifier = modifier,
        containerColor = colors.surfaceCanvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.surfaceCanvas)
                .padding(horizontal = EveTheme.spacing.gutterScreen, vertical = EveTheme.spacing.s5),
            verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s4),
        ) {
            Text("Memory", style = EveTheme.type.titleXl.copy(color = colors.textPrimary))
            Text(
                "What $ASSISTANT_NAME knows about you.",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            )

            // Live search — filters items client-side (text + category). Always visible so the
            // vault stays scannable as it grows.
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                label = { Text("Search memories") },
                singleLine = true,
                colors = fieldColors,
                shape = EveTheme.shape.md,
                modifier = Modifier.fillMaxWidth(),
            )

            // The body switches on the load phase; it always sits above the add affordance so the
            // "tell Atlas one thing" action is reachable from every state.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val phase = state.phase) {
                    is MemoryPhase.Loading -> CenteredNote("Gathering what $ASSISTANT_NAME remembers…", colors)
                    is MemoryPhase.Error -> CenteredNote(
                        "Couldn't reach your memory. Pull again in a moment.",
                        colors,
                    )
                    is MemoryPhase.Empty -> CenteredNote(
                        "$ASSISTANT_NAME hasn't learned anything about you yet — that changes as you talk.",
                        colors,
                    )
                    is MemoryPhase.Loaded ->
                        if (phase.groups.isEmpty()) {
                            CenteredNote("No memories match \"${state.query.trim()}\".", colors)
                        } else {
                            MemoryGroups(phase, colors)
                        }
                }
            }

            // Add a memory — saves straight to the owner page (no name needed).
            OutlinedTextField(
                value = factDraft,
                onValueChange = { factDraft = it },
                label = { Text("Add a memory") },
                placeholder = { Text("Something $ASSISTANT_NAME should remember about you") },
                singleLine = true,
                colors = fieldColors,
                shape = EveTheme.shape.md,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            EveButton(
                text = if (state.saving) "Saving…" else "Remember this",
                onClick = {
                    viewModel.remember(factDraft)
                    factDraft = ""
                },
                enabled = !state.saving && factDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The grouped vault: a category header per non-empty bucket, then its facts as cards. */
@Composable
private fun MemoryGroups(phase: MemoryPhase.Loaded, colors: EveColorScheme) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        phase.groups.forEachIndexed { gi, group ->
            val accent = categoryAccent(group.category, colors)
            item(key = "header-${group.category.key}") {
                if (gi > 0) Spacer(Modifier.height(EveTheme.spacing.s3))
                CategoryHeader(group.category.label, group.items.size, accent, colors)
            }
            itemsIndexed(group.items, key = { _, it -> "${group.category.key}-${it.text}" }) { _, m ->
                MemoryCard(m, accent, colors)
            }
        }
    }
}

/** A category section title with an accent rule and a count. */
@Composable
private fun CategoryHeader(label: String, count: Int, accent: Color, colors: EveColorScheme) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(EveTheme.shape.pill)
                .background(accent),
        )
        Spacer(Modifier.size(EveTheme.spacing.s2))
        Text(
            label.uppercase(),
            style = EveTheme.type.micro.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.weight(1f))
        Text(
            count.toString(),
            style = EveTheme.type.micro.copy(color = colors.textTertiary),
        )
    }
}

/** One remembered fact: its text, a subtle category accent edge, and a "learned" date chip. */
@Composable
private fun MemoryCard(item: MemoryItem, accent: Color, colors: EveColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.md)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, colors.borderDefault, EveTheme.shape.md)
            // A soft accent edge on the leading side — color is a cue, never the only signal
            // (the category header text already names the bucket).
            .drawBehind {
                drawRoundRect(
                    color = accent.copy(alpha = 0.9f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }
            .padding(EveTheme.spacing.s4),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        Text(item.text, style = EveTheme.type.body.copy(color = colors.textPrimary))
        if (item.date.isNotBlank()) {
            DateChip(item.date, accent, colors)
        }
    }
}

/** A soft "learned <date>" chip in the category accent. */
@Composable
private fun DateChip(date: String, accent: Color, colors: EveColorScheme) {
    Text(
        text = "learned $date",
        style = EveTheme.type.micro.copy(color = accent),
        modifier = Modifier
            .clip(EveTheme.shape.pill)
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A calm centered note for the Loading / Empty / Error / no-match states. */
@Composable
private fun CenteredNote(text: String, colors: EveColorScheme) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = EveTheme.type.bodySm.copy(color = colors.textTertiary),
            modifier = Modifier.padding(horizontal = EveTheme.spacing.s6),
        )
    }
}

/** Map a category to a calm accent drawn from the theme tokens (no hardcoded colors). */
private fun categoryAccent(cat: MemoryCategory, colors: EveColorScheme): Color = when (cat) {
    MemoryCategory.Faith -> colors.accent2          // indigo — reflective
    MemoryCategory.Health -> colors.success         // green — vitality
    MemoryCategory.Family -> colors.tier.known.fg   // soft violet — close circle
    MemoryCategory.Business -> colors.accent        // teal — the work
    MemoryCategory.Goals -> colors.warning          // amber — aspiration
    MemoryCategory.Preferences -> colors.textLink   // light teal — taste
    MemoryCategory.Other -> colors.textTertiary     // neutral
}
