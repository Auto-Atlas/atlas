package app.eve.ui.components

import app.eve.ASSISTANT_NAME
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eve.ui.approvals.ApprovalCardState
import app.eve.ui.approvals.CardPhase
import app.eve.ui.approvals.ResolvedOutcome
import app.eve.ui.theme.EveTheme
import app.eve.ui.theme.JetBrainsMono
import java.text.NumberFormat
import java.util.Locale

/**
 * Fixed width for the right-aligned amount column in [LineItemTable] so per-row amounts and the
 * total share one clean column (with JetBrains Mono tabular figures). Sized to comfortably fit
 * the larger moneyTitle total; longer amounts wrap rather than shoving the description column.
 */
private val amountColumnWidth = 96.dp

private fun money(dollars: Double): String =
    NumberFormat.getCurrencyInstance(Locale.US).apply { maximumFractionDigits = if (dollars % 1.0 == 0.0) 0 else 2 }
        .format(dollars)

private fun countdownLabel(secs: Long): String {
    if (secs <= 0) return "expired"
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return when {
        h > 0 -> "${h}h ${m}m left"
        m > 0 -> "${m}m ${s}s left"
        else -> "${s}s left"
    }
}

/**
 * Atlas's defining component. Collapsed shows the four W's; expanded shows full detail and the
 * approve/deny actions. The amount is ALWAYS computed from frozen args (approval.totalDollars),
 * never the summary. Actions are gated on [ApprovalCardState.actionsEnabled] and an external
 * [online] flag (offline => disabled, never silently failing).
 */
@Composable
fun ApprovalCard(
    state: ApprovalCardState,
    online: Boolean,
    reducedMotion: Boolean,
    onToggleExpand: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EveTheme.colors
    val motion = EveTheme.motion
    val ap = state.approval
    val stale = !online

    // Deliberate commit feel (design-system.md): the card grows/shrinks and swaps terminal
    // banners on a measured beat rather than snapping. reducedMotion collapses to instant.
    val containerSizeModifier = if (reducedMotion) {
        Modifier
    } else {
        Modifier.animateContentSize(
            animationSpec = tween(motion.durBaseMs, easing = motion.easeStandard),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .border(EveTheme.layout.borderHairline, colors.borderDefault, EveTheme.shape.lg)
            .then(containerSizeModifier)
            .padding(EveTheme.spacing.padCard),
    ) {
        // ---- Four W's (collapsed header, always visible) -------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = ap.requester, tier = ap.requesterTier)
            Spacer(Modifier.width(EveTheme.spacing.s3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ap.requester ?: "Someone",
                        style = EveTheme.type.headline.copy(color = colors.textPrimary),
                    )
                    Spacer(Modifier.width(EveTheme.spacing.s2))
                    TierChip(tier = ap.requesterTier)
                }
                // 2nd W: recipient / channel target.
                Text(
                    text = recipientLine(state),
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                )
            }
        }

        Spacer(Modifier.padding(top = EveTheme.spacing.s3))

        // 1st W: amount (invoice) computed from args, JetBrains Mono tnum; channel => target.
        val total = ap.totalDollars
        if (total != null) {
            Text(
                text = money(total),
                style = EveTheme.type.moneyDisplay.copy(color = colors.textPrimary),
                modifier = Modifier.semantics { contentDescription = "Amount ${money(total)}" },
            )
        } else {
            Text(
                text = "Message to ${ap.channelArgs?.channel ?: "channel"}",
                style = EveTheme.type.title.copy(color = colors.textPrimary),
            )
        }

        Spacer(Modifier.padding(top = EveTheme.spacing.s2))

        // 4th W: time-left, amber under 60s.
        TimePill(state, reducedMotion)

        // ---- Expanded detail ----------------------------------------------
        // animateContentSize on the container already drives height; AnimatedVisibility adds the
        // fade so detail doesn't pop in. reducedMotion => 0ms (instant, no animation).
        val expandDurMs = if (reducedMotion) 0 else motion.durBaseMs
        AnimatedVisibility(
            visible = state.expanded,
            enter = fadeIn(tween(expandDurMs, easing = motion.easeStandard)) +
                expandVertically(tween(expandDurMs, easing = motion.easeStandard)),
            exit = fadeOut(tween(expandDurMs, easing = motion.easeExit)) +
                shrinkVertically(tween(expandDurMs, easing = motion.easeExit)),
        ) {
            Column {
                Spacer(Modifier.padding(top = EveTheme.spacing.s4))
                // Trust context: WHO asked for this money action, surfaced before the owner
                // commits. Uses the real Approval.requester; subtle caption token, not shouty.
                // The customer/business is already shown via recipientLine, so it's not repeated.
                ap.requester?.takeIf { it.isNotBlank() }?.let { who ->
                    Text(
                        text = "Requested by $who",
                        style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                    )
                    Spacer(Modifier.padding(top = EveTheme.spacing.s2))
                }
                if (ap.isInvoice) {
                    LineItemTable(state)
                } else {
                    ChannelBody(state)
                }
                Spacer(Modifier.padding(top = EveTheme.spacing.s3))
                ActionRow(
                    state = state,
                    online = online,
                    reducedMotion = reducedMotion,
                    onApprove = onApprove,
                    onDeny = onDeny,
                )
            }
        }

        Spacer(Modifier.padding(top = EveTheme.spacing.s3))

        // Terminal status banner (success/failed/expired/denied/elsewhere) — text + color, never
        // color alone (a11y). When pending+collapsed, an expand affordance instead.
        // Crossfade the swap so a resolved/denied/expired banner settles in on the commit beat
        // instead of replacing the expand toggle instantly. reducedMotion => 0ms (instant).
        val bannerDurMs = if (reducedMotion) 0 else motion.durBaseMs
        // Crossfade keyed on the banner KIND (not the Pending countdown value) so the per-second
        // tick doesn't retrigger a fade — only an actual phase transition does. Render from the
        // captured key so the outgoing frame keeps showing the previous banner during the fade.
        androidx.compose.animation.Crossfade(
            targetState = phaseKey(state.phase),
            animationSpec = tween(bannerDurMs, easing = motion.easeStandard),
            label = "approvalPhaseBanner",
        ) { key ->
            when (key) {
                PhaseBannerKey.Pending -> ExpandToggle(state.expanded, onToggleExpand)
                PhaseBannerKey.Releasing -> StatusBanner("Approving…", colors.accent)
                PhaseBannerKey.Expired -> StatusBanner("Expired — ask ${ap.requester ?: "them"} to try again", colors.textTertiary)
                PhaseBannerKey.Denied -> StatusBanner("Denied — I let ${ap.requester ?: "them"} know", colors.danger)
                PhaseBannerKey.Resolved -> {
                    val resolved = state.phase as? CardPhase.Resolved
                    if (resolved != null) ResolvedBanner(resolved.outcome, ap.requester, state)
                }
            }
        }

        if (stale && state.phase is CardPhase.Pending) {
            Spacer(Modifier.padding(top = EveTheme.spacing.s2))
            StatusBanner("Stale — can't reach $ASSISTANT_NAME", colors.warning)
        }
    }
}

@Composable
private fun recipientLine(state: ApprovalCardState): String {
    val ap = state.approval
    return when {
        ap.isInvoice -> "Invoice for ${ap.invoiceArgs?.customerName ?: "a customer"}"
        ap.isChannel -> "via ${ap.channelArgs?.channel ?: "channel"}"
        else -> ap.summary
    }
}

@Composable
private fun TimePill(state: ApprovalCardState, reducedMotion: Boolean) {
    val colors = EveTheme.colors
    val motion = EveTheme.motion

    // Under 60s: amber text PLUS a warningSoft pill, so urgency reads as a shape/container
    // change and not color alone (design-system.md: "Color is never the sole signal"). The
    // threshold swap animates briefly (durFast/easeStandard) instead of snapping; the pill is
    // always present (clip+padding) with a transparent container when calm, so only the colors
    // tween rather than the layout popping. reducedMotion => snap (tween 0).
    val urgent = state.isUrgent
    val colorTween = tween<Color>(
        durationMillis = if (reducedMotion) 0 else motion.durFastMs,
        easing = motion.easeStandard,
    )
    val containerColor by animateColorAsState(
        targetValue = if (urgent) colors.warningSoft else Color.Transparent,
        animationSpec = colorTween,
        label = "timePillContainer",
    )
    val textColor by animateColorAsState(
        targetValue = if (urgent) colors.warning else colors.textTertiary,
        animationSpec = colorTween,
        label = "timePillText",
    )

    Text(
        text = countdownLabel(state.secondsLeft),
        // JetBrains Mono carries tabular figures (tnum, like moneyDisplay) so the seconds digits
        // keep a fixed advance width and don't reflow/jitter every tick. Sizing stays on the
        // label token; only the family swaps to the existing mono family.
        style = EveTheme.type.label.copy(color = textColor, fontFamily = JetBrainsMono),
        modifier = Modifier
            .clip(EveTheme.shape.pill)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun LineItemTable(state: ApprovalCardState) {
    val colors = EveTheme.colors
    val inv = state.approval.invoiceArgs ?: return
    Column(Modifier.fillMaxWidth()) {
        inv.lineItems.forEach { item ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${item.description}  ×${item.quantity} @ ${money(item.rate)}",
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(EveTheme.spacing.s3))
                // Amounts right-align in a fixed column with JetBrains Mono tabular figures
                // (same tnum treatment as money totals) so the digits line up cleanly down the
                // column instead of reflowing with each row's text width.
                Text(
                    text = money(item.amount),
                    style = EveTheme.type.bodySm.copy(
                        color = colors.textPrimary,
                        fontFamily = JetBrainsMono,
                    ),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(amountColumnWidth),
                )
            }
            Spacer(Modifier.padding(top = EveTheme.spacing.s1))
        }
        Spacer(Modifier.padding(top = EveTheme.spacing.s2))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                "Total",
                style = EveTheme.type.label.copy(color = colors.textSecondary),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(EveTheme.spacing.s3))
            Text(
                text = money(state.approval.totalDollars ?: 0.0),
                style = EveTheme.type.moneyTitle.copy(color = colors.textPrimary),
                textAlign = TextAlign.End,
                modifier = Modifier.width(amountColumnWidth),
            )
        }
    }
}

@Composable
private fun ChannelBody(state: ApprovalCardState) {
    val colors = EveTheme.colors
    val ch = state.approval.channelArgs ?: return
    Column {
        Text("to ${ch.channel}", style = EveTheme.type.label.copy(color = colors.textSecondary))
        Spacer(Modifier.padding(top = EveTheme.spacing.s2))
        Text(ch.message, style = EveTheme.type.body.copy(color = colors.textPrimary))
    }
}

@Composable
private fun ActionRow(
    state: ApprovalCardState,
    online: Boolean,
    reducedMotion: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val ap = state.approval
    val enabled = state.actionsEnabled && online
    val consequence = ap.totalDollars?.let {
        "Hold to approve sending the ${money(it)} invoice to ${ap.invoiceArgs?.customerName ?: "the customer"}"
    } ?: "Hold to approve sending the message to ${ap.channelArgs?.channel ?: "the channel"}"

    Column {
        Box(Modifier.semantics { contentDescription = consequence }) {
            HoldToApproveButton(
                label = "Hold to approve",
                consequence = consequence,
                onApprove = onApprove,
                enabled = enabled,
                reducedMotion = reducedMotion,
            )
        }
        Spacer(Modifier.padding(top = EveTheme.spacing.s3))
        EveButton(
            text = "Deny",
            onClick = onDeny,
            style = EveButtonStyle.Danger,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            contentDescription = "Deny this request",
        )
    }
}

@Composable
private fun ResolvedBanner(outcome: ResolvedOutcome, requester: String?, state: ApprovalCardState) {
    val colors = EveTheme.colors
    val who = requester ?: "them"
    when (outcome) {
        is ResolvedOutcome.Success -> {
            val detail = invoiceNumber(state)
            StatusBanner("Sent — ${detail ?: "done"}. I let $who know.", colors.success)
        }
        is ResolvedOutcome.SendFailed ->
            StatusBanner("Approved, but $ASSISTANT_NAME couldn't reach the service — Retry.", colors.warning)
        is ResolvedOutcome.Unverified ->
            StatusBanner("Approved — outcome unverified, check the service.", colors.warning)
        is ResolvedOutcome.Elsewhere ->
            StatusBanner("Already handled — approved elsewhere.", colors.textTertiary)
    }
}

private fun invoiceNumber(state: ApprovalCardState): String? {
    val result = state.approval.result ?: return null
    val num = (result["invoice_number"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return num?.let { "invoice #$it created" }
}

/** Stable banner identity for the phase Crossfade — ignores the Pending countdown value. */
private enum class PhaseBannerKey { Pending, Releasing, Expired, Denied, Resolved }

private fun phaseKey(phase: CardPhase): PhaseBannerKey = when (phase) {
    is CardPhase.Pending -> PhaseBannerKey.Pending
    is CardPhase.Releasing -> PhaseBannerKey.Releasing
    is CardPhase.Expired -> PhaseBannerKey.Expired
    is CardPhase.Denied -> PhaseBannerKey.Denied
    is CardPhase.Resolved -> PhaseBannerKey.Resolved
}

@Composable
private fun StatusBanner(text: String, color: Color) {
    Text(text = text, style = EveTheme.type.bodySm.copy(color = color))
}

@Composable
private fun ExpandToggle(expanded: Boolean, onToggle: () -> Unit) {
    val colors = EveTheme.colors
    Text(
        text = if (expanded) "Hide detail" else "Review",
        style = EveTheme.type.label.copy(color = colors.accent),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.sm)
            .clickable(onClick = onToggle)
            .padding(vertical = EveTheme.spacing.s2),
    )
}
