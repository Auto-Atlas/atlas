package app.eve.ui.approvals

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eve.ui.components.ApprovalCard
import app.eve.ui.theme.EveTheme

/**
 * The hero screen. Renders the four states (Loading / Offline / Empty / Items). Offline is
 * visually distinct (banner + greyed stale queue with actions disabled) so the owner is never
 * shown "all clear" while blind.
 */
@Composable
fun ApprovalsScreen(viewModel: ApprovalsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val agentActivity by viewModel.agentActivity.collectAsStateWithLifecycle()
    val streamHealthy by viewModel.streamHealthy.collectAsStateWithLifecycle()
    val reducedMotion = isReducedMotion()
    val colors = EveTheme.colors

    val waiting = when (val s = state) {
        is ApprovalsUiState.Items -> s.cards.size
        is ApprovalsUiState.Offline -> s.staleItems.size
        else -> 0
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .padding(horizontal = EveTheme.spacing.gutterScreen),
    ) {
        // The hero screen always wears its identity: title + how many actions wait + the "Guarded"
        // shield pill (the trust promise — every action is gated). Per the EVE design system template.
        ApprovalsHeader(waiting = waiting, online = state !is ApprovalsUiState.Offline)

        // Live delegated-agent window (live-delegation-approvals): what Hermes/Claude/Codex
        // are doing RIGHT NOW, with Cancel/Redirect. Always present — an empty section says
        // so plainly rather than hiding (the black-box this goal exists to remove).
        AgentActivitySection(
            cards = agentActivity,
            streamHealthy = streamHealthy,
            onCancel = viewModel::cancelTask,
            onRedirect = viewModel::redirectTask,
            modifier = Modifier.padding(bottom = EveTheme.spacing.s2),
        )

        when (val s = state) {
            is ApprovalsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }

            is ApprovalsUiState.Empty -> EmptyState()

            is ApprovalsUiState.Offline -> Column(Modifier.fillMaxSize()) {
                OfflineBanner()
                CardList(
                    cards = s.staleItems,
                    online = false,
                    reducedMotion = reducedMotion,
                    viewModel = viewModel,
                )
            }

            is ApprovalsUiState.Items -> CardList(
                cards = s.cards,
                online = true,
                reducedMotion = reducedMotion,
                viewModel = viewModel,
            )
        }
    }
}

/** Screen header: title, a live "N actions waiting" subtitle, and the "Guarded" shield pill. */
@Composable
private fun ApprovalsHeader(waiting: Int, online: Boolean, modifier: Modifier = Modifier) {
    val colors = EveTheme.colors
    val subtitle = when {
        !online -> "Reconnecting to EVE…"
        waiting == 0 -> "You're all clear."
        waiting == 1 -> "1 action waiting on you"
        else -> "$waiting actions waiting on you"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = EveTheme.spacing.s6, bottom = EveTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Approvals", style = EveTheme.type.titleXl.copy(color = colors.textPrimary))
            Spacer(Modifier.size(EveTheme.spacing.s1))
            Text(subtitle, style = EveTheme.type.bodySm.copy(color = colors.textSecondary))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(EveTheme.shape.pill)
                .background(colors.accentSoft)
                .padding(horizontal = EveTheme.spacing.s3, vertical = EveTheme.spacing.s1),
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(EveTheme.spacing.s1))
            Text("Guarded", style = EveTheme.type.micro.copy(color = colors.accent))
        }
    }
}

@Composable
private fun CardList(
    cards: List<ApprovalCardState>,
    online: Boolean,
    reducedMotion: Boolean,
    viewModel: ApprovalsViewModel,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = EveTheme.spacing.s5),
    ) {
        items(cards, key = { it.id }) { card ->
            // Newly-arriving cards ease in (fade + slight slide) rather than popping; placement
            // also animates as cards above resolve and the list reflows. easeEmphasized on the
            // base beat keeps it in step with the card's own animations. reducedMotion => no
            // appearance/placement animation (snap), honoring the OS "Remove animations" setting.
            val itemAnim = if (reducedMotion) {
                Modifier
            } else {
                val spec = tween<androidx.compose.ui.unit.IntOffset>(
                    durationMillis = EveTheme.motion.durBaseMs,
                    easing = EveTheme.motion.easeEmphasized,
                )
                Modifier.animateItem(
                    fadeInSpec = tween(EveTheme.motion.durBaseMs, easing = EveTheme.motion.easeEmphasized),
                    placementSpec = spec,
                    fadeOutSpec = tween(EveTheme.motion.durFastMs, easing = EveTheme.motion.easeExit),
                )
            }
            ApprovalCard(
                state = card,
                online = online,
                reducedMotion = reducedMotion,
                onToggleExpand = { viewModel.toggleExpand(card.id) },
                onApprove = { viewModel.approve(card.id) },
                onDeny = { viewModel.deny(card.id) },
                modifier = (if (online) Modifier else Modifier.graphicsLayer { alpha = 0.55f })
                    .then(itemAnim),
            )
        }
    }
}

/**
 * Empty inbox. A calm, deliberate "all clear" — a soft accent disc with a hand-drawn check
 * (no icon dependency), then the spec copy. Centered with a generous vertical rhythm so the
 * screen reads as resolved rather than merely blank.
 */
@Composable
private fun EmptyState() {
    val colors = EveTheme.colors
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = EveTheme.spacing.s11),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(EveTheme.shape.pill)
                    .background(colors.accentSoft)
                    .border(EveTheme.layout.borderHairline, colors.accentLine, EveTheme.shape.pill),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(30.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.20f, h * 0.54f)
                        lineTo(w * 0.42f, h * 0.74f)
                        lineTo(w * 0.82f, h * 0.28f)
                    }
                    drawPath(
                        path = path,
                        color = colors.accent,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            Spacer(Modifier.padding(top = EveTheme.spacing.s5))
            Text(
                "All clear",
                style = EveTheme.type.title.copy(color = colors.textPrimary),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.padding(top = EveTheme.spacing.s1))
            Text(
                "Nothing waiting.",
                style = EveTheme.type.body.copy(color = colors.textSecondary),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Off-tailnet banner. A proper bordered card (radius + warning border + soft fill) with a
 * leading warning dot so it reads as a first-class alert, not stray text — keeping the owner
 * aware they're seeing a stale queue rather than "all clear".
 */
@Composable
private fun OfflineBanner() {
    val colors = EveTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EveTheme.spacing.s4)
            .clip(EveTheme.shape.md)
            .background(colors.warningSoft)
            .border(EveTheme.layout.borderHairline, colors.warning.copy(alpha = 0.35f), EveTheme.shape.md)
            .padding(horizontal = EveTheme.spacing.s4, vertical = EveTheme.spacing.s3),
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color = colors.warning, center = Offset(size.width / 2, size.height / 2)) }
        Spacer(Modifier.width(EveTheme.spacing.s3))
        Text(
            text = "Can't reach EVE — you're off the tailnet",
            style = EveTheme.type.label.copy(color = colors.warning),
        )
    }
}

@Composable
private fun isReducedMotion(): Boolean {
    val context = LocalContext.current
    // The OS "Remove animations" accessibility setting drives ANIMATOR_DURATION_SCALE to 0.
    val scale = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
    return scale == 0f
}
