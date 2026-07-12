package app.eve.wear.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.eve.data.models.Approval
import app.eve.wear.approvals.WearApprovalsUiState

/**
 * The pending-approvals LIST — Wear-native (ScalingLazyColumn + TimeText), EVE-branded. Renders each
 * honest [WearApprovalsUiState] with REAL copy (no filler): a Loading spinner, the no-phone /
 * server-down legs named explicitly (server-down shows the phone's real detail and, if a prior list
 * exists, labels it as stale from <time ago>), a genuine Empty, or the pending rows.
 */
@Composable
fun WearApprovalsListScreen(
    state: WearApprovalsUiState,
    onSelect: (Approval) -> Unit,
    onRetryLink: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = { System.currentTimeMillis() },
    // Opens the push-to-talk (v2 "Voice note") screen. Defaulted so existing callers/tests need no
    // change; the voice entries show in the states where the phone link is up (Empty + Pending).
    onOpenTalk: () -> Unit = {},
    // Opens the v3 LIVE-voice (real call) screen — the "Live" chip beside "Voice note".
    onOpenLive: () -> Unit = {},
    // Health v2: the heart-alerts toggle chip. null = feature not wired (tests/previews) — hidden.
    heartAlertsOn: Boolean = false,
    onToggleHeartAlerts: (() -> Unit)? = null,
) {
    Scaffold(
        timeText = { TimeText() },
        modifier = modifier.fillMaxSize().background(WearEveColors.background),
    ) {
        when (state) {
            is WearApprovalsUiState.Loading -> CenteredMessage { CircularProgressIndicator() }

            is WearApprovalsUiState.NoPhone -> CenteredColumn {
                StatusTitle("Phone unreachable", WearEveColors.warning)
                BodyText(state.reason)
                Spacer(Modifier.size(10.dp))
                CompactChip(
                    onClick = onRetryLink,
                    label = { Text("Retry") },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = WearEveColors.surface2,
                        contentColor = WearEveColors.accent,
                    ),
                )
            }

            is WearApprovalsUiState.ServerDown -> ServerDownContent(state, onSelect, nowMs)

            is WearApprovalsUiState.Empty -> CenteredColumn {
                StatusTitle("All clear", WearEveColors.accent)
                BodyText("No pending approvals")
                Spacer(Modifier.size(10.dp))
                VoiceEntryChips(onOpenLive = onOpenLive, onOpenTalk = onOpenTalk)
                if (onToggleHeartAlerts != null) {
                    Spacer(Modifier.size(6.dp))
                    HeartAlertsChip(on = heartAlertsOn, onToggle = onToggleHeartAlerts)
                }
            }

            is WearApprovalsUiState.Pending -> ApprovalList(
                header = pendingHeader(state.approvals.size),
                approvals = state.approvals,
                stale = false,
                onSelect = onSelect,
                topEntry = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VoiceEntryChips(onOpenLive = onOpenLive, onOpenTalk = onOpenTalk)
                        if (onToggleHeartAlerts != null) {
                            Spacer(Modifier.size(4.dp))
                            HeartAlertsChip(on = heartAlertsOn, onToggle = onToggleHeartAlerts)
                        }
                    }
                },
            )
        }
    }
}

/**
 * Health v2 toggle: "♥ Alerts off — tap to enable" / "♥ Alerts on". The label carries the REAL
 * state (HrAlertsStore-backed, flipped only when enable/disable actually succeeded) — never an
 * optimistic lie while registration is still failing.
 */
@Composable
private fun HeartAlertsChip(on: Boolean, onToggle: () -> Unit) {
    CompactChip(
        onClick = onToggle,
        label = { Text(if (on) "♥ Alerts on" else "♥ Alerts off") },
        colors = ChipDefaults.chipColors(
            backgroundColor = if (on) WearEveColors.accentSoft else WearEveColors.surface2,
            contentColor = if (on) WearEveColors.accent else WearEveColors.textSecondary,
        ),
        modifier = Modifier.testTag("heartAlertsEntry"),
    )
}

@Composable
private fun ServerDownContent(
    state: WearApprovalsUiState.ServerDown,
    onSelect: (Approval) -> Unit,
    nowMs: () -> Long,
) {
    val stale = state.staleApprovals
    if (stale.isNullOrEmpty()) {
        CenteredColumn {
            StatusTitle("Phone can't reach EVE", WearEveColors.warning)
            BodyText(state.detail)
        }
    } else {
        // Show the last-known list, but LABELLED as stale (never as if current).
        val age = ApprovalFormatting.relativeAge(state.fetchedAtEpochMs, nowMs())
        ApprovalList(
            header = "Stale — showing list from $age",
            headerColor = WearEveColors.warning,
            subheader = state.detail,
            approvals = stale,
            stale = true,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun ApprovalList(
    header: String,
    approvals: List<Approval>,
    stale: Boolean,
    onSelect: (Approval) -> Unit,
    headerColor: Color = WearEveColors.textSecondary,
    subheader: String? = null,
    // Optional entry chip pinned above the header (the "Talk to EVE" mic entry on the live list).
    topEntry: (@Composable () -> Unit)? = null,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (topEntry != null) {
            item { topEntry() }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = header,
                    color = headerColor,
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
                if (subheader != null) {
                    Text(
                        text = subheader,
                        color = WearEveColors.textTertiary,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        items(approvals, key = { it.id }) { approval ->
            ApprovalChip(approval = approval, stale = stale, onClick = { onSelect(approval) })
        }
    }
}

/**
 * One pending-approval row. Shows the tool-derived title (amount for invoices), the requester trust
 * line with a tier-colored dot, the risk badge, and — for invoices — the amount. Stale rows dim.
 */
@Composable
internal fun ApprovalChip(
    approval: Approval,
    stale: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = wearTierColor(approval.requesterTier)
    val alpha = if (stale) 0.55f else 1f
    Chip(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ChipDefaults.chipColors(
            backgroundColor = WearEveColors.surface.copy(alpha = alpha),
            contentColor = WearEveColors.textPrimary,
        ),
        label = {
            Text(
                text = ApprovalFormatting.title(approval),
                color = WearEveColors.textPrimary,
                maxLines = 1,
            )
        },
        secondaryLabel = {
            val risk = wearRiskColor(approval.riskLevel)
            Column {
                // Requester trust line: tier-colored dot + "Requested by <name>".
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tier.fg))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = ApprovalFormatting.requesterLine(approval),
                        color = WearEveColors.textSecondary,
                        maxLines = 1,
                    )
                }
                // Risk badge: risk-colored dot + word (color is never the sole signal — a11y).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(risk.fg))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = wearRiskLabel(approval.riskLevel),
                        color = risk.fg,
                        maxLines = 1,
                    )
                }
            }
        },
    )
}

// ---- small shared building blocks ------------------------------------------

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

@Composable
private fun StatusTitle(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.title3,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        color = WearEveColors.textSecondary,
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun pendingHeader(count: Int): String = when (count) {
    1 -> "1 approval waiting"
    else -> "$count approvals waiting"
}

/** Convenience so a preview/host has the app context for reduced-motion without threading it. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return isReducedMotion(context)
}
