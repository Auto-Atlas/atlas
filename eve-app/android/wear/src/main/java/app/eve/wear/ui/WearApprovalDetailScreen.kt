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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.eve.data.models.Approval
import app.eve.wear.approvals.WearActionState

/**
 * The approval DETAIL — reached by tapping a row (SwipeDismissable back). Shows the full request
 * (title, summary, requester + trust tier, risk, amount, expiry), then the hold-to-approve gate and
 * a two-tap Deny. Action feedback (in-flight / each terminal outcome / the named failure legs) is
 * rendered HONESTLY from the injected [WearActionState] — no optimistic UI.
 */
@Composable
fun WearApprovalDetailScreen(
    approval: Approval,
    actionState: WearActionState,
    reducedMotion: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val secondsLeft = ((approval.expiresAt * 1000.0 - nowMs()) / 1000.0).toLong().coerceAtLeast(0)
    val expired = approval.isExpired || secondsLeft <= 0

    Scaffold(
        timeText = { TimeText() },
        modifier = modifier.fillMaxSize().background(WearEveColors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = ApprovalFormatting.title(approval),
                color = WearEveColors.textPrimary,
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(6.dp))

            // Trust row: requester + tier chip.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = approval.requester ?: "Someone",
                    color = WearEveColors.textSecondary,
                    style = MaterialTheme.typography.caption1,
                )
                Spacer(Modifier.width(6.dp))
                WearTierChip(approval.requesterTier)
            }
            Spacer(Modifier.size(6.dp))
            WearRiskBadge(approval.riskLevel)

            Spacer(Modifier.size(10.dp))
            Text(
                text = approval.summary,
                color = WearEveColors.textSecondary,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
            )

            ApprovalFormatting.amountLabel(approval)?.let { amount ->
                Spacer(Modifier.size(8.dp))
                Text(
                    text = amount,
                    color = WearEveColors.textPrimary,
                    style = MaterialTheme.typography.title2,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.size(6.dp))
            Text(
                text = if (expired) "Expired" else "Expires in ${countdownLabel(secondsLeft)}",
                color = if (expired) WearEveColors.danger else WearEveColors.textTertiary,
                style = MaterialTheme.typography.caption2,
            )

            Spacer(Modifier.size(14.dp))
            ActionArea(
                actionState = actionState,
                expired = expired,
                reducedMotion = reducedMotion,
                onApprove = onApprove,
                onDeny = onDeny,
            )
        }
    }
}

@Composable
private fun ActionArea(
    actionState: WearActionState,
    expired: Boolean,
    reducedMotion: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    when (actionState) {
        is WearActionState.InFlight -> FeedbackBanner("Sending…", WearEveColors.accent)

        is WearActionState.Resolved -> FeedbackBanner(
            text = actionState.message,
            color = when (actionState.tone) {
                WearActionState.Tone.Positive -> WearEveColors.success
                WearActionState.Tone.Neutral -> WearEveColors.textSecondary
                WearActionState.Tone.Negative -> WearEveColors.danger
            },
        )

        is WearActionState.Idle -> {
            if (expired) {
                FeedbackBanner("This request has expired", WearEveColors.textTertiary)
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HoldToApproveWear(
                        label = "Hold to approve",
                        onApprove = onApprove,
                        reducedMotion = reducedMotion,
                    )
                    Spacer(Modifier.size(8.dp))
                    DenyButton(onDeny = onDeny)
                    Spacer(Modifier.size(4.dp))
                    // Honest scope note: rotary-crown confirm is not wired this increment.
                    Text(
                        text = "Press and hold to confirm",
                        color = WearEveColors.textTertiary,
                        style = MaterialTheme.typography.caption3,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Two-tap Deny: the first tap arms the confirm, the second sends. (Deny may be one tap per spec.) */
@Composable
private fun DenyButton(onDeny: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Button(
        onClick = {
            if (confirming) onDeny() else confirming = true
        },
        colors = ButtonDefaults.buttonColors(backgroundColor = WearEveColors.surface2),
        modifier = Modifier.fillMaxWidth().testTag("denyApproval"),
    ) {
        Text(
            text = if (confirming) "Tap again to deny" else "Deny",
            color = WearEveColors.danger,
        )
    }
}

@Composable
private fun FeedbackBanner(text: String, color: Color) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun WearTierChip(tier: String, modifier: Modifier = Modifier) {
    val tc = wearTierColor(tier)
    Text(
        text = wearTierLabel(tier),
        color = tc.fg,
        style = MaterialTheme.typography.caption3,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(tc.soft)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun WearRiskBadge(risk: String, modifier: Modifier = Modifier) {
    val rc = wearRiskColor(risk)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(rc.fg))
        Spacer(Modifier.width(6.dp))
        Text(
            text = wearRiskLabel(risk),
            color = rc.fg,
            style = MaterialTheme.typography.caption3,
            modifier = modifier
                .clip(MaterialTheme.shapes.small)
                .background(rc.soft)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Same shape as :app ApprovalCard.countdownLabel (h/m/s), for the expiry line. */
internal fun countdownLabel(secs: Long): String {
    if (secs <= 0) return "expired"
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
