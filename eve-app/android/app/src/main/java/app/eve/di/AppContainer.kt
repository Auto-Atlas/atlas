package app.eve.di

import android.content.Context
import app.eve.data.ActivityRepository
import app.eve.data.ApiClient
import app.eve.data.ApprovalRepository
import app.eve.data.MemoryRepository
import app.eve.data.Settings
import app.eve.data.SkillsRepository
import app.eve.data.StatusRepository
import app.eve.data.StreamClient
import app.eve.data.TalkRepository
import app.eve.data.TodayChecks
import app.eve.data.TodayRepository
import app.eve.glasses.BluetoothGlassesAudioRouter
import app.eve.glasses.GlassesCameraSource
import app.eve.glasses.NoGlassesAudioRouter
import app.eve.glasses.StubGlassesCameraSource
import app.eve.reminder.ForegroundActivityTracker
import app.eve.vision.FrameCaptureController
import app.eve.voice.SmallWebRtcSignaling
import app.eve.voice.VoiceClient
import app.eve.voice.WebRtcVoiceClient
import app.eve.voice.deriveVoiceUrl
import kotlinx.coroutines.flow.first

/**
 * Manual dependency container (no Hilt — single-user app). Holds the singletons the app needs.
 * The ApiClient and StreamClient read the base URL + token fresh from [settings] per call, so
 * rotating credentials in-app needs no rewiring.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings: Settings = Settings(appContext)

    /** First-run gate: persisted SharedPreferences flag driving the onboarding wizard. */
    val onboardingState: app.eve.onboarding.OnboardingState =
        app.eve.onboarding.OnboardingState(appContext)

    val apiClient: ApiClient = ApiClient(connection = { settings.current() })

    val streamClient: StreamClient = StreamClient(connection = { settings.current() })

    /**
     * The Meta glasses camera (DAT). Currently the honest stub — the toolkit is a token-gated dev
     * preview and isn't bundled (see build.gradle.kts + app.eve.glasses.GlassesToolkit). Swap for
     * RealGlassesCameraSource when the SDK is wired in; the routing + toggle below don't change.
     */
    val glassesCameraSource: GlassesCameraSource = StubGlassesCameraSource()

    /**
     * capture_frame camera capture. Snaps one still and uploads it. Routing (app.eve.vision.CaptureRouter):
     * a "phone"-sourced event → the foreground activity's camera (foreground-only, no background path);
     * a "glasses"-sourced event → [glassesCameraSource] when the toggle's on + glasses connected, else
     * an honest error (never a phone fallback); "any" prefers glasses when available, else phone.
     */
    val frameCaptureController: FrameCaptureController =
        FrameCaptureController(
            appContext,
            apiClient,
            currentActivity = { ForegroundActivityTracker.current() },
            glassesSource = glassesCameraSource,
            glassesEnabled = { settings.glassesEnabledNow() },
        )

    /**
     * surface_visual card state. Handles `surface_visual` stream events (dispatched from
     * [app.eve.push.StreamService]) by fetching + decoding the image and exposing the latest card
     * as a StateFlow the Talk screen renders. Held here (not in the Talk VM) so it's a single
     * consumer and the card survives navigation.
     */
    val visualHub: app.eve.visual.VisualHub = app.eve.visual.VisualHub(apiClient)

    /**
     * Local, per-device "Meta glasses" toggle for the Status screen (DataStore-backed, not
     * /v1/settings — the glasses are hardware paired to THIS phone). Reports the DAT SDK's real
     * bundled state so the UI can be honest about "toolkit not bundled".
     */
    val glassesToggle: app.eve.ui.status.GlassesToggle = object : app.eve.ui.status.GlassesToggle {
        override val isToolkitAvailable: Boolean = glassesCameraSource.isToolkitAvailable
        override suspend fun isEnabled(): Boolean = settings.glassesEnabledNow()
        override suspend fun setEnabled(enabled: Boolean) = settings.setGlassesEnabled(enabled)
    }

    // ---- Health v1 (Health Connect → sidecar) ----------------------------------------------------
    // The availability/permission edge (all androidx.health.connect types live inside it), the
    // last-sync store, and the seam the Status "Health" row + HealthUploadWorker share. The worker
    // reaches these through EveApplication.container; the Status VM only sees [healthController].
    val healthConnectManager: app.eve.health.HealthConnectManager =
        app.eve.health.HealthConnectManager(appContext)

    val healthUploadStore: app.eve.health.HealthUploadStore =
        app.eve.health.HealthUploadStore(appContext)

    val healthController: app.eve.health.HealthController =
        app.eve.health.AppHealthController(appContext, healthConnectManager, healthUploadStore)

    /**
     * Everything the Status Health card's Compose permission launcher needs, or null when Health
     * Connect isn't available on this device (then the card shows the honest "unavailable" state and
     * no launcher is built). Nothing owner-specific — just the six read-permission strings + the SDK's
     * own request contract.
     */
    fun healthPermissionRequest(): app.eve.health.HealthPermissionRequest? {
        val contract = healthConnectManager.requestPermissionsContract() ?: return null
        return app.eve.health.HealthPermissionRequest(
            permissions = app.eve.health.HealthConnectManager.READ_PERMISSIONS,
            contract = contract,
        )
    }

    val approvalRepository: ApprovalRepository = ApprovalRepository(apiClient)
    val talkRepository: TalkRepository = TalkRepository(apiClient)
    val statusRepository: StatusRepository = StatusRepository(apiClient)
    val activityRepository: ActivityRepository = ActivityRepository(apiClient)
    val memoryRepository: MemoryRepository = MemoryRepository(apiClient)
    val skillsRepository: SkillsRepository = SkillsRepository(apiClient)

    /**
     * The real Data-Layer gateway backing the watch bridge — created LAZILY so unit tests (which
     * construct [app.eve.wearbridge.WearBridge] directly with fakes) never touch Play Services.
     */
    private val gmsWearGateway: app.eve.wearbridge.GmsWearGateway by lazy {
        app.eve.wearbridge.GmsWearGateway(appContext)
    }

    /**
     * Phone-side gateway to approval_api over the Wearable Data Layer. Fetch lambdas point at the
     * existing repos (fresh GET each time — the bridge never mutates a cached list; approval_api is
     * the single source of truth). Triggered by: an inbound REFRESH message, post-action, and
     * approval stream events (see [app.eve.push.StreamService]). No polling.
     */
    val wearBridge: app.eve.wearbridge.WearBridge by lazy {
        app.eve.wearbridge.WearBridge(
            approvalRepository = approvalRepository,
            fetchPending = { approvalRepository.pending() },
            fetchStatus = { statusRepository.status() },
            snapshotWriter = gmsWearGateway,
            resultSender = gmsWearGateway,
            // Watch push-to-talk: one utterance through Atlas's full brain (POST /v1/ask). Same
            // fresh-per-call style as the fetch lambdas — the bridge holds no ApiClient itself.
            askEve = { text -> talkRepository.ask(text) },
            // Live-voice door for the watch — AUTOMATIC pairing: derived from the approval base
            // (same host, Funnel door port/path) unless the Settings override is set. Paired with
            // the EXISTING connection bearer (nothing owner-specific hardcoded). Read fresh so an
            // in-app change is pushed on the next refresh. When nothing is derivable the blank is
            // written honestly ("not configured" on the wrist).
            fetchVoiceDoor = {
                val conn = settings.current()
                app.eve.data.wear.VoiceDoorConfig(
                    wsUrl = app.eve.voice.deriveWatchVoiceDoorUrl(
                        approvalBase = conn.baseUrl,
                        override = settings.watchVoiceDoorUrlNow(),
                    ) ?: "",
                    token = conn.token,
                )
            },
            // Health v2: the watch's passive HR alert -> POST /v1/health/event. Same fresh-per-call
            // style; the bridge logs every failed leg LOUDLY (a swallowed heart alert is the worst
            // possible silent fallback).
            postHealthEvent = { alert -> apiClient.healthEvent(alert) },
        )
    }

    /**
     * Phone-side relay for the v2 NATIVE watch voice turn (over a ChannelClient stream, not a
     * Message — the audio exceeds the Message cap). Pure core + fresh-per-call HTTP lambda, same style
     * as [wearBridge]; the GMS edge lives in [app.eve.wearbridge.WearBridgeService.onChannelOpened].
     * Lazy so unit tests that build [app.eve.wearbridge.VoiceTurnRelay] with a fake never touch it.
     */
    val voiceTurnRelay: app.eve.wearbridge.VoiceTurnRelay by lazy {
        app.eve.wearbridge.VoiceTurnRelay(
            voiceTurn = { audioB64, requestId -> apiClient.voiceTurn(audioB64, requestId) },
        )
    }

    private val todayChecks: TodayChecks = TodayChecks(appContext)
    val todayRepository: TodayRepository = TodayRepository(apiClient, todayChecks)

    /**
     * Resolves the effective phone_bot voice URL from the current connection + optional override
     * (pure [deriveVoiceUrl]), or null when nothing usable is configured (drives the
     * "not configured" Talk state).
     */
    suspend fun voiceUrl(): String? {
        val base = settings.current().baseUrl
        val override = settings.voiceUrlOverride.first()
        return deriveVoiceUrl(base, override)
    }

    /**
     * Builds a fresh native voice client for a session. [Context] comes from EveApplication via
     * this container (BMAD: Amelia) — required by PeerConnectionFactory.initialize. Returns null
     * if the voice URL isn't configured.
     */
    suspend fun newVoiceClient(): VoiceClient? {
        val url = voiceUrl() ?: return null
        // When the glasses toggle is on, hand the voice client a router that steers Atlas's speech to
        // the glasses' Bluetooth speaker (DAT exposes no audio API — it's standard BT A2DP/SCO). Off
        // → the inert router, so playback stays on the phone exactly as before. Read once at session
        // start (like barge-in), so toggling mid-call takes effect on the next session.
        val glassesAudio =
            if (settings.glassesEnabledNow()) BluetoothGlassesAudioRouter(appContext) else NoGlassesAudioRouter
        return WebRtcVoiceClient(
            appContext,
            SmallWebRtcSignaling(baseUrl = url),
            glassesAudio = glassesAudio,
        )
    }
}
