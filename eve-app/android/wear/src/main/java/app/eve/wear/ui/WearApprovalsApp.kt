package app.eve.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import app.eve.data.models.Approval
import app.eve.wear.approvals.WearActionState
import app.eve.wear.approvals.WearApprovalsUiState
import app.eve.wear.talk.TalkTurn
import app.eve.wear.talk.VoiceState
import app.eve.wear.talk.WearTalkPhase
import app.eve.wear.livevoice.LiveTranscriptLine
import app.eve.wear.livevoice.WearLiveVoiceScreen
import app.eve.wear.livevoice.VoiceControls as LiveVoiceControls
import app.eve.wear.livevoice.VoiceState as LiveVoiceState

/**
 * The watch approvals experience: a SwipeDismissable nav host (list -> detail, swipe-back to
 * dismiss). Stateless over the ViewModel — it takes the current [WearApprovalsUiState] and the
 * per-approval [WearActionState] map plus callbacks, so it renders exactly what the VM computed
 * (every state honest, none faked).
 */
@Composable
fun WearApprovalsApp(
    state: WearApprovalsUiState,
    actions: Map<String, WearActionState>,
    reducedMotion: Boolean,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRetryLink: () -> Unit,
    // ---- push-to-talk (the talk route) ----
    talkPhase: WearTalkPhase,
    talkTranscript: List<TalkTurn>,
    voiceState: VoiceState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMicPermissionDenied: () -> Unit,
    onAsk: (String) -> Unit,
    onSpeechUnavailable: () -> Unit,
    onSpeakReply: (String) -> Unit,
    onPrewarmVoice: () -> Unit,
    onShutdownVoice: () -> Unit,
    // ---- v3 live voice (the real call) ----
    liveState: LiveVoiceState,
    liveControls: LiveVoiceControls,
    liveTranscript: List<LiveTranscriptLine>,
    liveBotLevel: () -> Float = { 0f },
    // Health v2: the heart-alerts chip state + toggle (null = feature not wired; chip hidden).
    heartAlertsOn: Boolean = false,
    onToggleHeartAlerts: (() -> Unit)? = null,
    onLiveOrbTap: () -> Unit,
    onLiveOrbLongPress: () -> Unit,
    onLiveScreenEntry: () -> Unit,
    onLiveAutoStart: () -> Unit,
    onLiveMicPermissionDenied: () -> Unit,
    // Deep link from a wrist approval notification: the approval id to open on the DETAIL screen.
    // Null when the app was launched normally. [onOpenConsumed] fires once we've navigated so a
    // recomposition can't re-navigate (and a later notification tap can set a fresh id).
    openApprovalId: String? = null,
    onOpenConsumed: () -> Unit = {},
) {
    val navController = rememberSwipeDismissableNavController()

    // Land on the orb the moment the app opens — the wrist is voice-first ("app opens → call
    // auto-starts → the orb is the screen"). The list stays one swipe-back away (it is still the
    // start destination), so approvals remain reachable. A notification launch instead deep-links
    // to its approval (below), so we only auto-open live when there is no pending link. rememberSaveable
    // so a config-change recreation never yanks the user back to live after they navigated away.
    var landedOnOrb by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!landedOnOrb && openApprovalId == null) {
            landedOnOrb = true
            navController.navigate(ROUTE_LIVE)
        }
    }

    // Route a notification deep link straight to the approval's detail (where hold-to-approve lives).
    LaunchedEffect(openApprovalId) {
        if (openApprovalId != null) {
            navController.navigate("$ROUTE_DETAIL/$openApprovalId")
            onOpenConsumed()
        }
    }

    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            WearApprovalsListScreen(
                state = state,
                onSelect = { approval -> navController.navigate("$ROUTE_DETAIL/${approval.id}") },
                onRetryLink = onRetryLink,
                onOpenTalk = { navController.navigate(ROUTE_TALK) },
                onOpenLive = { navController.navigate(ROUTE_LIVE) },
                heartAlertsOn = heartAlertsOn,
                onToggleHeartAlerts = onToggleHeartAlerts,
            )
        }
        composable(ROUTE_LIVE) {
            WearLiveVoiceScreen(
                state = liveState,
                controls = liveControls,
                transcript = liveTranscript,
                reducedMotion = reducedMotion,
                onOrbTap = onLiveOrbTap,
                onOrbLongPress = onLiveOrbLongPress,
                onScreenEntry = onLiveScreenEntry,
                onAutoStart = onLiveAutoStart,
                onMicPermissionDenied = onLiveMicPermissionDenied,
                botLevel = liveBotLevel,
            )
        }
        composable(ROUTE_TALK) {
            WearTalkScreen(
                phase = talkPhase,
                transcript = talkTranscript,
                voiceState = voiceState,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onMicPermissionDenied = onMicPermissionDenied,
                onAsk = onAsk,
                onSpeechUnavailable = onSpeechUnavailable,
                onSpeakReply = onSpeakReply,
                onPrewarmVoice = onPrewarmVoice,
                onShutdownVoice = onShutdownVoice,
            )
        }
        composable(
            route = "$ROUTE_DETAIL/{approvalId}",
            arguments = listOf(navArgument("approvalId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val approvalId = backStackEntry.arguments?.getString("approvalId")
            val approval = approvalId?.let { findApproval(state, it) }
            if (approval == null) {
                // The row resolved and left the phone's snapshot while its detail was open — pop back
                // to the (now-updated) list instead of showing a stale/empty detail.
                LaunchedEffect(approvalId) { navController.popBackStack() }
            } else {
                WearApprovalDetailScreen(
                    approval = approval,
                    actionState = actions[approval.id] ?: WearActionState.Idle,
                    reducedMotion = reducedMotion,
                    onApprove = { onApprove(approval.id) },
                    onDeny = { onDeny(approval.id) },
                )
            }
        }
    }
}

/** The approval the detail route targets, from whichever list the current state carries. */
private fun findApproval(state: WearApprovalsUiState, id: String): Approval? = when (state) {
    is WearApprovalsUiState.Pending -> state.approvals
    is WearApprovalsUiState.ServerDown -> state.staleApprovals ?: emptyList()
    else -> emptyList()
}.firstOrNull { it.id == id }

private const val ROUTE_LIST = "list"
private const val ROUTE_DETAIL = "detail"
private const val ROUTE_TALK = "talk"
private const val ROUTE_LIVE = "live"
