package app.eve.wear.di

import android.content.Context
import app.eve.wear.PhoneNodeSource
import app.eve.wear.WearableNodeSource
import app.eve.wear.data.GatewayClient
import app.eve.wear.data.GmsGatewayClient
import app.eve.wear.data.GmsSnapshotSource
import app.eve.wear.data.SnapshotSource
import app.eve.wear.notify.ApprovalNotifier
import app.eve.wear.notify.DenyFlow
import app.eve.wear.notify.NotifiedIdsStore
import app.eve.wear.notify.SharedPrefsNotifiedIdsStore
import app.eve.wear.talk.AudioRecordWristRecorder
import app.eve.wear.talk.AudioTrackPcmPlayer
import app.eve.wear.talk.GmsVoiceTurnClient
import app.eve.wear.talk.PcmPlayer
import app.eve.wear.talk.ReplySpeaker
import app.eve.wear.talk.TtsReplySpeaker
import app.eve.wear.talk.VoiceTurnClient
import app.eve.wear.talk.WristRecorder
import app.eve.wear.livevoice.GmsVoiceDoorSource
import app.eve.wear.livevoice.OkHttpWsVoiceClient
import app.eve.wear.livevoice.VoiceDoorSource
import app.eve.wear.livevoice.WsVoiceClient

/**
 * The watch app's manual DI container (mirrors the phone's AppContainer / EveApplication pattern):
 * one place that owns the real GMS seams so BOTH the UI ([app.eve.wear.MainActivity]) and the
 * background [app.eve.wear.notify.WearDenyReceiver] share the SAME instances (the deny receiver and
 * the ViewModel must talk to the same [GatewayClient]). No mocking library — tests build the seams
 * with fakes directly, never through this container.
 */
class WearContainer(context: Context) {

    private val appContext = context.applicationContext

    val nodeSource: PhoneNodeSource by lazy { WearableNodeSource(appContext) }
    val snapshotSource: SnapshotSource by lazy { GmsSnapshotSource(appContext) }
    val gatewayClient: GatewayClient by lazy { GmsGatewayClient(appContext) }

    val notifiedIdsStore: NotifiedIdsStore by lazy { SharedPrefsNotifiedIdsStore(appContext) }
    val approvalNotifier: ApprovalNotifier by lazy { ApprovalNotifier(notifiedIdsStore) }

    /**
     * On-watch TTS for the FALLBACK (Google) path's text-only reply. Held here so the talk screen
     * depends on the seam, not the Android engine; prewarm/shutdown are driven by the talk screen's
     * lifecycle. The v2 native path uses [pcmPlayer] instead (EVE's own synthesized voice).
     */
    val replySpeaker: ReplySpeaker by lazy { TtsReplySpeaker(appContext) }

    // ---- v2 native voice turn seams (wrist mic -> EVE's own voice) ----

    /** Wrist mic capture (16 kHz mono PCM16 -> WAV). Lazy so JVM tests never touch AudioRecord. */
    val wristRecorder: WristRecorder by lazy { AudioRecordWristRecorder() }

    /** The bidirectional voice-turn channel to the phone gateway node (ChannelClient). */
    val voiceTurnClient: VoiceTurnClient by lazy { GmsVoiceTurnClient(appContext) }

    /** Plays EVE's returned PCM on the wrist speaker (AudioTrack). Lazy for the same reason. */
    val pcmPlayer: PcmPlayer by lazy { AudioTrackPcmPlayer() }

    /** Shares [gatewayClient] so the wrist Deny sends over the exact same Data-Layer seam as the app. */
    val denyFlow: DenyFlow by lazy { DenyFlow(gatewayClient) }

    // ---- v3 LIVE voice seams (real call over one secure WebSocket to the owner's voice door) ----

    /** The live-voice WebSocket transport (OkHttp + streaming mic/player). Lazy so JVM tests never touch it. */
    val wsVoiceClient: WsVoiceClient by lazy { OkHttpWsVoiceClient(appContext) }

    /** Reads the retained {wsUrl, token} door config the phone writes on the Data Layer. */
    val voiceDoorSource: VoiceDoorSource by lazy { GmsVoiceDoorSource(appContext) }

    // ---- Health v2 seams (passive HR stream -> EVE's proactive warning) ----

    /** Did the owner turn heart alerts ON (drives boot/app-start re-registration). */
    val hrAlertsStore: app.eve.wear.health.HrAlertsStore by lazy {
        app.eve.wear.health.HrAlertsStore(appContext)
    }

    /** Registers/unregisters the passive HEART_RATE_BPM stream behind HrAlertService. */
    val hrPassiveMonitor: app.eve.wear.health.HrPassiveMonitor by lazy {
        app.eve.wear.health.HrPassiveMonitor(appContext)
    }

    /**
     * App-scoped policy+send glue for HR alerts. Held HERE (not in the service) because Health
     * Services recreates service instances at will — the hysteresis/cooldown memory must survive.
     * The threshold comes from [hrAlertsStore] (owner-configurable data, never baked in); it is
     * read once at construction — a changed threshold applies from the next app start.
     */
    val hrAlertRelay: app.eve.wear.health.HrAlertRelay by lazy {
        app.eve.wear.health.HrAlertRelay(
            gatewayClient,
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ),
            app.eve.wear.health.HrAlertPolicy(highBpm = hrAlertsStore.highBpm),
        )
    }
}
