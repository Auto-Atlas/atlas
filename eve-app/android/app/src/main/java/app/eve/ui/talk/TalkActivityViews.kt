package app.eve.ui.talk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.eve.ui.theme.EveColorScheme
import app.eve.ui.theme.EveTheme

/**
 * The transformative tool-call surfaces for the Talk screen — the Android analogues of the desktop
 * `JarvisActivity` (ToolStatusLine) and `DelegationTicker`. They turn the raw `tool_call` /
 * `delegation_*` feed into the premium, human-readable view: a friendly "Checking your email…"
 * line with a pulsing icon, and a live per-brain waterfall (codex → glm → local) that flips through
 * trying → working Ns → ✓ answered / ✗ failed.
 */

/** One human-phrased tool readout: pulsing icon + present-tense phrase while running, then
 *  name + DONE/FAILED + latency. Mirrors the desktop ToolStatusLine. */
@Composable
fun ToolStatusLine(tool: ToolActivity, modifier: Modifier = Modifier) {
    val colors = EveTheme.colors
    val visual = toolVisual(tool.tool)
    val running = tool.status == ToolStatus.RUNNING
    val accent = when (tool.status) {
        ToolStatus.RUNNING -> colors.accent
        ToolStatus.OK -> colors.success
        ToolStatus.ERROR -> colors.danger
    }
    // Pulse the icon opacity while running (transition always created; value used only when running).
    val pulseT = rememberInfiniteTransition(label = "toolpulse")
    val pulse by pulseT.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "toolpulseAlpha",
    )
    val iconAlpha = if (running) pulse else 1f
    val statusWord = when (tool.status) {
        ToolStatus.RUNNING -> null
        ToolStatus.OK -> "DONE"
        ToolStatus.ERROR -> "FAILED"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = accent.copy(alpha = iconAlpha),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(EveTheme.spacing.s2))
        Text(
            text = if (running) visual.running else visual.title,
            style = EveTheme.type.bodySm.copy(color = colors.textPrimary),
        )
        if (statusWord != null) {
            Spacer(Modifier.width(EveTheme.spacing.s2))
            Text(
                text = statusWord + latencySuffix(tool.latencyMs),
                style = EveTheme.type.caption.copy(color = accent),
            )
        }
    }
}

/** The live per-brain delegation waterfall card. Renders nothing when there's no delegation. */
@Composable
fun DelegationTicker(delegation: DelegationState?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = delegation != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val d = delegation ?: return@AnimatedVisibility
        val colors = EveTheme.colors
        val headerColor = when {
            !d.done -> colors.accent
            d.ok == true -> colors.success
            else -> colors.danger
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(EveTheme.shape.lg)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderSubtle, EveTheme.shape.lg)
                .padding(EveTheme.spacing.padCard),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = headerColor, pulsing = !d.done)
                Spacer(Modifier.width(EveTheme.spacing.s2))
                Text(
                    text = if (!d.done) "DELEGATING…" else "DELEGATED → ${(d.winner ?: "?").uppercase()}",
                    style = EveTheme.type.label.copy(color = headerColor),
                )
                Spacer(Modifier.weight(1f))
                if (d.done && d.totalLatencyMs != null) {
                    Text(
                        text = latencyText(d.totalLatencyMs),
                        style = EveTheme.type.caption.copy(color = colors.textTertiary),
                    )
                }
            }

            d.task?.takeIf { it.isNotBlank() }?.let { task ->
                Spacer(Modifier.height(EveTheme.spacing.s1))
                Text(
                    text = task,
                    style = EveTheme.type.caption.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(EveTheme.spacing.s2))
            for (step in d.rows()) {
                DelegationRow(
                    step = step,
                    isActive = step.brain == d.activeBrain,
                    activeDetail = d.activeDetail,
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun DelegationRow(
    step: DelegationStep,
    isActive: Boolean,
    activeDetail: String?,
    colors: EveColorScheme,
) {
    val phaseColor = when (step.phase) {
        "answer" -> colors.success
        "fail" -> colors.danger
        "try", "working" -> colors.accent
        else -> colors.textTertiary // queued / unknown
    }
    val label = when (step.phase) {
        "try" -> "trying…"
        "working" -> if (isActive && !activeDetail.isNullOrBlank()) "working $activeDetail" else "working…"
        "answer" -> "✓ answered"
        "fail" -> "✗ failed"
        "queued" -> "queued"
        else -> step.phase
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = step.brain,
            style = EveTheme.type.bodySm.copy(color = colors.textPrimary),
            modifier = Modifier.width(72.dp),
        )
        Text(text = label, style = EveTheme.type.caption.copy(color = phaseColor))
        Spacer(Modifier.weight(1f))
        val ms = step.latencyMs
        if (ms != null) {
            Text(
                text = latencyText(ms),
                style = EveTheme.type.caption.copy(color = colors.textTertiary),
            )
        }
    }
}

/** A small status dot; pulses its alpha while [pulsing] (the live "in flight" cue). */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val alpha = if (pulsing) a else 1f
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

private fun latencyText(ms: Long): String =
    if (ms >= 1000) "${"%.1f".format(ms / 1000.0)}s" else "${ms}ms"

private fun latencySuffix(ms: Long?): String = if (ms == null) "" else "  •  ${latencyText(ms)}"
