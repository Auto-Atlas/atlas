package app.eve.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.eve.wear.approvals.WearApprovalsViewModel
import app.eve.wear.notify.ApprovalNotifier
import app.eve.wear.talk.VoiceState
import app.eve.wear.talk.WearTalkViewModel
import app.eve.wear.livevoice.WearLiveVoiceViewModel
import app.eve.wear.livevoice.VoiceState as LiveVoiceState
import app.eve.wear.ui.WearApprovalsApp
import app.eve.wear.ui.rememberReducedMotion
import app.eve.wear.talk.WearTalkPhase
import kotlinx.coroutines.launch

/**
 * The watch app's only activity. Renders the approvals experience driven by [WearApprovalsViewModel]
 * over the REAL Data Layer seams held by the shared [app.eve.wear.di.WearContainer] (so the app and
 * the wrist-notification deny receiver talk to the SAME GatewayClient). The pre-first-snapshot
 * phone-link check stays as the NoPhone diagnosis input (a NodeClient query, no polling).
 *
 * Battery honesty: the snapshot/result Data-Layer listeners live only while the ViewModel's flows
 * are collected (the activity's lifecycleScope); the node query + a single refresh run ONCE per
 * resume — an idle screen never wakes the radio on a timer.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as WearApplication).container }

    private val viewModel: WearApprovalsViewModel by lazy {
        WearApprovalsViewModel(
            snapshotSource = container.snapshotSource,
            gateway = container.gatewayClient,
            scope = lifecycleScope,
        )
    }

    /**
     * Push-to-talk VM — over the SAME shared GatewayClient seam (fallback path) plus the v2 native
     * seams (wrist mic, voice-turn channel, PCM player) held by the container.
     */
    private val talkViewModel: WearTalkViewModel by lazy {
        WearTalkViewModel(
            gateway = container.gatewayClient,
            recorder = container.wristRecorder,
            voiceClient = container.voiceTurnClient,
            pcmPlayer = container.pcmPlayer,
            scope = lifecycleScope,
        )
    }

    /**
     * v3 LIVE-voice VM — the real call over one secure WebSocket to the owner's public voice door. Its
     * door config arrives from the phone over the Data Layer ([container.voiceDoorSource]); nothing is
     * hardcoded. Shares the activity's lifecycleScope like the other VMs.
     */
    private val liveVoiceViewModel: WearLiveVoiceViewModel by lazy {
        WearLiveVoiceViewModel(
            client = container.wsVoiceClient,
            configSource = container.voiceDoorSource,
            scope = lifecycleScope,
        )
    }

    /** The approval id a notification asked us to open on the detail screen (consumed once). */
    private var openApprovalId by mutableStateOf<String?>(null)

    // Standard one-shot runtime permission for POST_NOTIFICATIONS (targetSdk 35). If denied the app
    // still works — approval notifications simply can't show; we log it and never nag.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.i(TAG, "POST_NOTIFICATIONS denied — wrist approval notifications can't show; app still works")
            }
        }

    // ---- Health v2: heart alerts (passive HR stream -> Atlas warns in her voice) ----

    /** UI mirror of HrAlertsStore.enabled — flips only when enable/disable actually succeeded. */
    private var heartAlertsOn by mutableStateOf(false)

    // Two-step permission rule (platform): request BODY_SENSORS first; asking for the BACKGROUND
    // grant simultaneously makes the system silently deny BOTH. Background is asked only after the
    // foreground grant lands and registration succeeded.
    private val requestBodySensorsBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(TAG, "BODY_SENSORS_BACKGROUND denied — HR alerts work only while the app is up; enable it in Settings > Permissions")
            }
        }
    private val requestBodySensors =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableHeartAlerts()
            else Log.i(TAG, "BODY_SENSORS denied — heart alerts stay OFF (owner's call, never nag)")
        }

    private fun toggleHeartAlerts() {
        when {
            heartAlertsOn -> {
                container.hrAlertsStore.enabled = false
                heartAlertsOn = false
                lifecycleScope.launch { container.hrPassiveMonitor.unregister() }
            }
            container.hrPassiveMonitor.hasPermission() -> enableHeartAlerts()
            else -> requestBodySensors.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun enableHeartAlerts() {
        lifecycleScope.launch {
            when (val outcome = container.hrPassiveMonitor.ensureRegistered()) {
                app.eve.wear.health.HrPassiveMonitor.MonitorOutcome.Registered -> {
                    container.hrAlertsStore.enabled = true
                    heartAlertsOn = true
                    // Separate SECOND step (see the two-step rule above). Without it alerts flow
                    // only while the app is foregrounded — the denial log names that honestly.
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BODY_SENSORS_BACKGROUND,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestBodySensorsBackground.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
                    }
                }
                else -> {
                    // Named refusal: the chip stays OFF, never a fake-armed heart alert.
                    container.hrAlertsStore.enabled = false
                    heartAlertsOn = false
                    Log.e(TAG, "Heart alerts could not be enabled: $outcome")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openApprovalId = intent.approvalDeepLink()
        heartAlertsOn = container.hrAlertsStore.enabled
        maybeAskNotificationPermission()
        setContent {
            val state by viewModel.uiState.collectAsState()
            val actions by viewModel.actions.collectAsState()
            val talkPhase by talkViewModel.phase.collectAsState()
            val talkTranscript by talkViewModel.transcript.collectAsState()
            // Two voice sources, one note: the native path drives the VM's voiceState (PcmPlayer +
            // server voice_error); the fallback (Google) path drives the TTS speaker. Only one is ever
            // active at a time, so prefer whichever is non-Idle.
            val nativeVoice by talkViewModel.voiceState.collectAsState()
            val ttsVoice by container.replySpeaker.state.collectAsState()
            val voiceState = if (nativeVoice != VoiceState.Idle) nativeVoice else ttsVoice

            // v3 live-voice state, controls, transcript.
            val liveState by liveVoiceViewModel.state.collectAsState()
            val liveControls by liveVoiceViewModel.controls.collectAsState()
            val liveTranscript by liveVoiceViewModel.transcript.collectAsState()

            // Keep the screen awake for the whole turn (record -> send -> think -> her voice); release
            // when idle so an at-rest screen never blocks ambient/sleep. The live call keeps the screen
            // awake for the WHOLE session (connect through teardown), mirroring the v2 talk phases.
            val liveSessionUp = liveState !is LiveVoiceState.Idle &&
                liveState !is LiveVoiceState.NotConfigured &&
                liveState !is LiveVoiceState.Error
            val keepAwake = talkPhase is WearTalkPhase.Recording ||
                talkPhase is WearTalkPhase.Sending ||
                talkPhase is WearTalkPhase.ThinkingAwaitingReply ||
                voiceState is VoiceState.Speaking ||
                liveSessionUp
            LaunchedEffect(keepAwake) { setKeepScreenOn(keepAwake) }

            val reducedMotion = rememberReducedMotion()
            WearApprovalsApp(
                state = state,
                actions = actions,
                reducedMotion = reducedMotion,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onRetryLink = ::retryLink,
                talkPhase = talkPhase,
                talkTranscript = talkTranscript,
                voiceState = voiceState,
                onStartRecording = talkViewModel::startRecording,
                onStopRecording = talkViewModel::stopRecording,
                onMicPermissionDenied = talkViewModel::onMicPermissionDenied,
                onAsk = talkViewModel::ask,
                onSpeechUnavailable = talkViewModel::reportSpeechUnavailable,
                onSpeakReply = container.replySpeaker::speak,
                onPrewarmVoice = container.replySpeaker::prewarm,
                onShutdownVoice = container.replySpeaker::shutdown,
                liveState = liveState,
                liveControls = liveControls,
                liveTranscript = liveTranscript,
                // Lambda, not collectAsState: the level ticks with every PCM frame and must reach
                // the ring's frame loop without recomposing the whole app (deferred read).
                liveBotLevel = { liveVoiceViewModel.botLevel.value },
                onLiveOrbTap = liveVoiceViewModel::onOrbTap,
                onLiveOrbLongPress = liveVoiceViewModel::onOrbLongPress,
                onLiveScreenEntry = liveVoiceViewModel::onScreenEntry,
                onLiveAutoStart = liveVoiceViewModel::onAutoStart,
                onLiveMicPermissionDenied = liveVoiceViewModel::onMicPermissionDenied,
                heartAlertsOn = heartAlertsOn,
                onToggleHeartAlerts = ::toggleHeartAlerts,
                openApprovalId = openApprovalId,
                onOpenConsumed = { openApprovalId = null },
            )
        }
    }

    /** Activity-level screen-awake window flag, toggled from the talk phase (never left stuck on). */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the wrist speaker's AudioTrack so a disposed talk screen never holds the engine.
        container.pcmPlayer.release()
        // Tear down any live-voice socket + mic/player so a disposed screen never holds them open.
        container.wsVoiceClient.hangUp()
    }

    /** singleTop: a fresh notification tap while we're already open arrives here, not a new instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.approvalDeepLink()?.let { openApprovalId = it }
    }

    override fun onResume() {
        super.onResume()
        // One-shot per foreground: (1) diagnose the phone link for the pre-snapshot NoPhone state,
        // (2) ask the phone to push a fresh snapshot now. Neither is a loop.
        checkPhoneLink()
        viewModel.requestRefresh()
    }

    /** The retry button re-runs BOTH one-shots: the link diagnosis and the snapshot refresh. */
    private fun retryLink() {
        checkPhoneLink()
        viewModel.requestRefresh()
    }

    /** Run the NodeClient query ONCE and feed the honest result to the ViewModel (never a fake OK). */
    private fun checkPhoneLink() {
        viewModel.reportPhoneLink(PhoneLinkState.Checking)
        lifecycleScope.launch {
            val link = try {
                phoneLinkStateFrom(Result.success(container.nodeSource.connectedNodes()))
            } catch (e: Exception) {
                phoneLinkStateFrom(Result.failure(e))
            }
            viewModel.reportPhoneLink(link)
        }
    }

    /** Ask for POST_NOTIFICATIONS at most ONCE per process (never on every resume). */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (askedNotificationPermission) return
        askedNotificationPermission = true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Intent.approvalDeepLink(): String? = getStringExtra(ApprovalNotifier.EXTRA_OPEN_APPROVAL_ID)

    private companion object {
        const val TAG = "WearMainActivity"

        /** Process-wide guard: request the notification permission a single time, not per resume. */
        @Volatile
        var askedNotificationPermission = false
    }
}
