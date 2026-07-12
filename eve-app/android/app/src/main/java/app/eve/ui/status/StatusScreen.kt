package app.eve.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.activity.compose.rememberLauncherForActivityResult
import app.eve.health.HealthAvailability
import app.eve.health.HealthPermissionRequest
import app.eve.data.models.SystemStatus
import app.eve.data.models.Telemetry
import app.eve.ui.components.EveSwitch
import app.eve.ui.components.StatusTile
import app.eve.ui.components.TileStatus
import app.eve.ui.theme.EveTheme

@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    onReRunSetup: () -> Unit = {},
    /** Health Connect permission launcher inputs, or null when HC is unavailable (or in preview). */
    healthRequest: HealthPermissionRequest? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EveTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }

    // The Health Connect permission dialog; re-checks grants when it returns. Only built when a real
    // request (and thus an Android runtime) is available.
    val healthLauncher = healthRequest?.let { req ->
        rememberLauncherForActivityResult(req.contract) { viewModel.onHealthPermissionsChanged() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceCanvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EveTheme.spacing.gutterScreen, vertical = EveTheme.spacing.s5),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        Text("Status", style = EveTheme.type.titleXl.copy(color = colors.textPrimary))

        val h = state.health
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
        ) {
            StatusTile(
                label = "Sidecar",
                value = if (state.online) "Up" else "Down",
                icon = if (state.online) Icons.Filled.Cloud else Icons.Filled.CloudOff,
                status = if (state.online) TileStatus.Ok else TileStatus.Bad,
                accent = if (state.online) colors.success else colors.danger,
                modifier = Modifier.weight(1f),
            )
            StatusTile(
                label = "Pending",
                value = (state.status?.pendingApprovals ?: h?.pending ?: 0).toString(),
                icon = Icons.Filled.Inbox,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
        ) {
            StatusTile(
                label = "Unverified",
                value = (h?.releasingOrphans ?: 0).toString(),
                icon = Icons.Filled.Warning,
                status = if ((h?.releasingOrphans ?: 0) > 0) TileStatus.Warn else TileStatus.Ok,
                accent = if ((h?.releasingOrphans ?: 0) > 0) colors.warning else null,
                modifier = Modifier.weight(1f),
            )
            StatusTile(
                label = "Remote approval",
                value = if (state.remoteApprovalEnabled) "On" else "Off",
                icon = Icons.Filled.VerifiedUser,
                status = if (state.remoteApprovalEnabled) TileStatus.Ok else null,
                accent = if (state.remoteApprovalEnabled) colors.accent else colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.padding(top = EveTheme.spacing.s2))

        // ---- Activation toggle (the deliberate opt-in front door, spec §1.9) ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(EveTheme.shape.lg)
                .background(colors.surfaceRaised)
                .padding(EveTheme.spacing.padCard),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Remote approvals",
                    style = EveTheme.type.headline.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
                )
                EveSwitch(
                    checked = state.remoteApprovalEnabled,
                    onCheckedChange = { viewModel.setRemoteApproval(it) },
                    enabled = state.online && !state.togglePending,
                )
            }
            Spacer(Modifier.padding(top = EveTheme.spacing.s2))
            Text(
                "Known family members' high-risk requests will reach you for approval instead of being blocked.",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            )
        }

        Spacer(Modifier.padding(top = EveTheme.spacing.s2))

        // ---- Thinking toggle (Epic T): manual on/off, default fast ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(EveTheme.shape.lg)
                .background(colors.surfaceRaised)
                .padding(EveTheme.spacing.padCard),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Thinking mode",
                    style = EveTheme.type.headline.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
                )
                EveSwitch(
                    checked = state.thinkingEnabled,
                    onCheckedChange = { viewModel.setThinking(it) },
                    enabled = state.online && !state.thinkingPending,
                )
            }
            Spacer(Modifier.padding(top = EveTheme.spacing.s2))
            Text(
                "EVE reasons before answering. Turn it on for a hard question, off to go back to fast replies.",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            )
        }

        Spacer(Modifier.padding(top = EveTheme.spacing.s2))

        // ---- Barge-in toggle: let me talk over EVE (default off = speakerphone-safe) ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(EveTheme.shape.lg)
                .background(colors.surfaceRaised)
                .padding(EveTheme.spacing.padCard),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Let me interrupt EVE",
                    style = EveTheme.type.headline.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f),
                )
                EveSwitch(
                    checked = state.bargeInEnabled,
                    onCheckedChange = { viewModel.setBargeIn(it) },
                    enabled = state.online && !state.bargeInPending,
                )
            }
            Spacer(Modifier.padding(top = EveTheme.spacing.s2))
            Text(
                "Talk over her to cut in. Best with earbuds — on a speakerphone her own voice can interrupt her. Takes effect on your next voice session.",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            )
        }

        // ---- Meta glasses toggle (LOCAL/per-device, default off): route capture + speech to glasses ----
        if (state.glassesSupported) {
            Spacer(Modifier.padding(top = EveTheme.spacing.s2))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EveTheme.shape.lg)
                    .background(colors.surfaceRaised)
                    .padding(EveTheme.spacing.padCard),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Meta glasses",
                        style = EveTheme.type.headline.copy(color = colors.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                    EveSwitch(
                        checked = state.glassesEnabled,
                        onCheckedChange = { viewModel.setGlasses(it) },
                        // Local write — works offline. Only blocked while a write is in flight.
                        enabled = !state.glassesTogglePending,
                    )
                }
                Spacer(Modifier.padding(top = EveTheme.spacing.s2))
                Text(
                    if (state.glassesToolkitAvailable) {
                        "When your Ray-Ban Meta or Oakley Meta glasses are connected, EVE looks through " +
                            "the glasses camera and speaks out the glasses speaker."
                    } else {
                        "Meta's glasses toolkit isn't bundled in this build yet (it's a token-gated developer " +
                            "preview). You can turn this on, but glasses captures will error until the SDK is added."
                    },
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                )
            }
        }

        // ---- Health (Health Connect → EVE): the owner's heart-first vitals feed ----
        if (state.healthSupported) {
            Spacer(Modifier.padding(top = EveTheme.spacing.s2))
            HealthCard(
                state = state,
                onAllow = { healthRequest?.let { healthLauncher?.launch(it.permissions) } },
                onSyncNow = { viewModel.syncHealthNow() },
                canRequest = healthRequest != null,
            )
        }

        // ---- Engine telemetry (real cost/throughput proxied from OpenJarvis) ----
        Text(
            "Engine",
            style = EveTheme.type.headline.copy(color = colors.textPrimary),
            modifier = Modifier.padding(top = EveTheme.spacing.s2),
        )
        val status = state.status
        when {
            state.desktopOffline || (status != null && !status.desktopOnline) ->
                DesktopOfflineNote()

            status != null -> TelemetrySection(status)

            // Sidecar offline / telemetry not yet loaded — stay quiet, the tiles above already
            // tell the connection story (never fabricate engine numbers).
            !state.online ->
                Text(
                    "Connect to EVE to see engine telemetry.",
                    style = EveTheme.type.bodySm.copy(color = colors.textTertiary),
                )

            else ->
                Text(
                    "Loading telemetry…",
                    style = EveTheme.type.bodySm.copy(color = colors.textTertiary),
                )
        }

        state.errorMessage?.let {
            Text(it, style = EveTheme.type.bodySm.copy(color = colors.warning))
        }

        // ---- Re-run setup / re-enroll voice (onboarding isn't first-run-only) ----
        Spacer(Modifier.padding(top = EveTheme.spacing.s2))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(EveTheme.shape.lg)
                .background(colors.surfaceRaised)
                .padding(EveTheme.spacing.padCard),
            verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
        ) {
            Text("Set up EVE again", style = EveTheme.type.headline.copy(color = colors.textPrimary))
            Text(
                "Re-enter your name, re-enroll your voice, or update what matters to you.",
                style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
            )
            app.eve.ui.components.EveButton(
                text = "Re-run setup",
                style = app.eve.ui.components.EveButtonStyle.Subtle,
                onClick = onReRunSetup,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The "Health" row. Renders one honest state at a time from real Health Connect status — never a
 * silent off: unavailable / needs-update / needs-permission / never-synced / last-synced. The only
 * actions are "Allow" (launches the HC permission dialog) and "Sync now" (enqueues an upload).
 */
@Composable
private fun HealthCard(
    state: StatusUiState,
    onAllow: () -> Unit,
    onSyncNow: () -> Unit,
    canRequest: Boolean,
) {
    val colors = EveTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .padding(EveTheme.spacing.padCard),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Filled.MonitorHeart,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.padding(end = EveTheme.spacing.s2),
            )
            Text("Health", style = EveTheme.type.headline.copy(color = colors.textPrimary))
        }

        when {
            state.healthAvailability == null ->
                Text(
                    "Checking Health Connect…",
                    style = EveTheme.type.bodySm.copy(color = colors.textTertiary),
                )

            state.healthAvailability == HealthAvailability.NOT_INSTALLED ->
                Text(
                    "Health Connect isn't set up on this phone. Install or turn it on in your phone's " +
                        "settings so EVE can read your heart rate and health from your watch.",
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                )

            state.healthAvailability == HealthAvailability.PROVIDER_UPDATE_REQUIRED ->
                Text(
                    "Health Connect needs an update before EVE can read your health. Update it in your " +
                        "phone's app store, then come back.",
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                )

            // Available but not (fully) permitted → ask.
            !state.healthPermitted -> {
                Text(
                    "Let EVE read your heart rate, sleep, steps, blood-oxygen, blood pressure and " +
                        "workouts from your watch — so she knows how your heart's been.",
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                )
                app.eve.ui.components.EveButton(
                    text = "Allow health access",
                    style = app.eve.ui.components.EveButtonStyle.Subtle,
                    onClick = onAllow,
                    enabled = canRequest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Available + permitted → show sync state + manual trigger.
            else -> {
                Text(
                    if (state.healthLastUploadAt == null) {
                        "Connected. Not synced yet — sync to send your latest health to EVE."
                    } else {
                        "Last synced ${relativeTime(state.healthLastUploadAt)}. EVE reads the last 24 hours " +
                            "from your watch; refreshes automatically every 30 minutes."
                    },
                    style = EveTheme.type.bodySm.copy(color = colors.textSecondary),
                )
                app.eve.ui.components.EveButton(
                    text = if (state.healthSyncing) "Syncing…" else "Sync now",
                    style = app.eve.ui.components.EveButtonStyle.Subtle,
                    onClick = onSyncNow,
                    enabled = !state.healthSyncing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Coarse "how long ago" for the last-sync line. No locale-sensitive formatting, no fake precision. */
private fun relativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val minutes = (nowMillis - epochMillis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

@Composable
private fun DesktopOfflineNote() {
    val colors = EveTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(EveTheme.shape.lg)
            .background(colors.surfaceRaised)
            .padding(EveTheme.spacing.padCard),
        verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.padding(end = EveTheme.spacing.s2),
            )
            Text("Desktop offline", style = EveTheme.type.headline.copy(color = colors.textSecondary))
        }
        Text(
            "Engine cost and throughput appear here once OpenJarvis is running on your desktop. Approvals still work.",
            style = EveTheme.type.bodySm.copy(color = colors.textTertiary),
        )
    }
}

@Composable
private fun TelemetrySection(status: SystemStatus) {
    val t: Telemetry = status.telemetry
    val colors = EveTheme.colors

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        StatusTile(
            label = "Total tokens",
            value = formatCount(t.totalTokens),
            icon = Icons.Filled.Numbers,
            modifier = Modifier.weight(1f),
        )
        StatusTile(
            label = "Total cost",
            value = "$" + String.format("%.2f", t.totalCost),
            icon = Icons.Filled.Payments,
            accent = if (t.totalCost > 0) colors.accent else null,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.padding(top = EveTheme.spacing.s1))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        StatusTile(
            label = "Avg latency",
            value = if (t.avgLatencyS > 0) String.format("%.1fs", t.avgLatencyS) else "—",
            icon = Icons.Filled.Timer,
            modifier = Modifier.weight(1f),
        )
        StatusTile(
            label = "Throughput",
            value = if (t.avgThroughput > 0) "${t.avgThroughput.toInt()} tok/s" else "—",
            icon = Icons.Filled.Speed,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.padding(top = EveTheme.spacing.s1))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EveTheme.spacing.gapCard),
    ) {
        StatusTile(
            label = "Requests",
            value = formatCount(t.totalRequests),
            icon = Icons.Filled.Bolt,
            modifier = Modifier.weight(1f),
        )
        StatusTile(
            label = "GPU util",
            value = if (t.avgGpuUtilization > 0) "${t.avgGpuUtilization.toInt()}%" else "—",
            icon = Icons.Filled.Speed,
            modifier = Modifier.weight(1f),
        )
    }

    // ---- Budget (limits + today's usage), only when the server provides one ----
    status.budget?.let { budget ->
        Spacer(Modifier.padding(top = EveTheme.spacing.s2))
        Text("Budget", style = EveTheme.type.headline.copy(color = colors.textPrimary))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(EveTheme.shape.lg)
                .background(colors.surfaceRaised)
                .padding(EveTheme.spacing.padCard),
            verticalArrangement = Arrangement.spacedBy(EveTheme.spacing.s2),
        ) {
            BudgetLine(
                label = "Tokens today",
                value = formatCount(budget.usage.tokensToday),
                limit = budget.limits.maxTokensPerDay?.let { formatCount(it) },
            )
            BudgetLine(
                label = "Requests this hour",
                value = formatCount(budget.usage.requestsThisHour),
                limit = budget.limits.maxRequestsPerHour?.let { formatCount(it) },
            )
        }
    }
}

@Composable
private fun BudgetLine(label: String, value: String, limit: String?) {
    val colors = EveTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = EveTheme.type.bodySm.copy(color = colors.textSecondary), modifier = Modifier.weight(1f))
        Text(
            if (limit != null) "$value / $limit" else value,
            style = EveTheme.type.body.copy(color = colors.textPrimary),
        )
    }
}

private fun formatCount(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> {
        val k = n / 1000.0
        if (k >= 10) "${k.toInt()}k" else String.format("%.1fk", k)
    }
    else -> String.format("%.1fM", n / 1_000_000.0)
}
