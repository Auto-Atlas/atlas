package app.eve.voice

import app.eve.ASSISTANT_NAME
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The seam (BMAD: the blessed FakeRepo pattern). [VoiceController] consumes this interface so it
 * can be driven by synthetic [VoiceEvent]s in a JVM unit test, while production uses
 * [WebRtcVoiceClient] (native, NOT JVM-unit-testable — `org.webrtc` loads
 * libjingle_peerconnection_so.so which UnsatisfiedLinkErrors on a plain JVM).
 */
interface VoiceClient {
    /** Hot stream of reducer events produced by the connection (already off the webrtc thread). */
    val events: Flow<VoiceEvent>

    /** Build the peer connection, capture mic, exchange SDP, flush ICE. */
    suspend fun connect()

    /** Tear the session down. */
    fun hangUp()

    /** Barge-in: stop remote playback and reclaim the floor (manual interrupt, spec §3). */
    fun interrupt()

    /** Mute/unmute the outbound mic — Atlas stops/starts hearing you (the local track is gated). */
    fun setMicMuted(muted: Boolean)

    /** Route playback to the loudspeaker (true) or the earpiece (false). */
    fun setSpeakerphone(on: Boolean)
}

/**
 * Native SmallWebRTC client — the Kotlin/libwebrtc equivalent of pipecat's JS small-webrtc
 * transport. The ONLY file in the package that imports `org.webrtc`.
 *
 * Flow (mirrors the JS client; spec §1):
 *  1. Init [PeerConnectionFactory] once (loads the .so) — [Context] from EveApplication/AppContainer.
 *  2. Add a mic [AudioTrack] via a SEND_RECV transceiver.
 *  3. Buffer local ICE candidates immediately (libwebrtc emits them on setLocalDescription,
 *     BEFORE we have a pc_id — the #1 naive-port bug).
 *  4. createOffer → setLocalDescription.
 *  5. POST /api/offer → answer {sdp,type,pc_id}.
 *  6. setRemoteDescription(answer); store pc_id; flush buffered candidates via PATCH (snake_case)
 *     and PATCH later ones live. Remote candidates are bundled in the answer SDP (no remote
 *     trickle channel to wait on).
 *  7. Remote track → playback + audio focus + MODE_IN_COMMUNICATION.
 *  8. Poll getStats → inbound-rtp.bytesReceived → MediaFlowing / MediaStalled (the orb never
 *     animates over silence).
 *
 * Threading (spec §1): all Observer callbacks fire on libwebrtc's signaling thread. This client
 * re-emits them onto its own coroutine dispatcher before they reach the controller; nothing here
 * touches Compose. `restart_pc` is reserved/unused in v1 (reconnect = a fresh offer).
 */
class WebRtcVoiceClient(
    private val appContext: Context,
    private val signaling: SmallWebRtcSignaling,
    /**
     * Optional glasses audio router. When [GlassesAudioRouter.isSupported] and it succeeds, Atlas's
     * speech rides the glasses' Bluetooth speaker instead of the phone. Defaults to the inert
     * [NoGlassesAudioRouter] so the phone-only path (and its tests) are unchanged.
     */
    private val glassesAudio: app.eve.glasses.GlassesAudioRouter = app.eve.glasses.NoGlassesAudioRouter,
) : VoiceClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: Flow<VoiceEvent> = _events.asSharedFlow()

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private var pcId: String? = null
    private val pendingCandidates = ArrayList<IceCandidate>()
    private val candidateLock = Any()
    private val closed = AtomicBoolean(false)
    private var statsJob: Job? = null

    // Session generation: bumped on every teardown (so each connect() opens a NEW generation). The
    // async SDP/offer callbacks (createOffer -> setLocalDescription -> POST -> setRemoteDescription)
    // can fire LATE — after a reconnect has already torn the old session down and started a new one.
    // Each callback captures the generation it was scheduled in and no-ops if it's stale, so a
    // straggler from a previous session can't mutate pcId, start a stats loop, or emit Failed into
    // the live one.
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private val audioManager: AudioManager
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var priorAudioMode: Int = AudioManager.MODE_NORMAL

    // Off the webrtc signaling thread before any emit.
    private fun emit(event: VoiceEvent) {
        scope.launch { _events.emit(event) }
    }

    override suspend fun connect() {
        if (closed.get()) return
        // Single-flight: tear down any prior session BEFORE building a new one so a reconnect
        // (VoiceController re-uses this client) can't leak a peer connection, mic capture, or a
        // second stats loop, nor let a stale PcObserver corrupt the new session's state.
        teardownSession()
        // This session's generation (teardownSession just bumped it). Async SDP callbacks scheduled
        // below capture `gen` and bail if a newer session has since started.
        val gen = generation.get()

        ensureFactory()
        val factory = this.factory ?: run {
            emit(VoiceEvent.Failed("WebRTC unavailable"))
            return
        }

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            // Host candidates only — media rides the tailnet/LAN candidate (spec §5), no STUN/TURN.
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(config, PcObserver()) ?: run {
            emit(VoiceEvent.Failed("Could not create peer connection"))
            return
        }
        peerConnection = pc

        // Mic capture (echo cancellation / NS / AGC on).
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory.createAudioSource(audioConstraints)
        audioSource = source
        val track = factory.createAudioTrack("eve-mic", source)
        micTrack = track
        pc.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
        )

        configureAudioRouting()

        // createOffer → setLocalDescription → POST → setRemoteDescription → flush ICE.
        // Every async callback is scoped to `gen`: a straggler from a torn-down session is dropped.
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                if (isStale(gen)) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        if (isStale(gen)) return
                        scope.launch { exchangeSdp(gen, desc) }
                    }

                    override fun onSetFailure(error: String?) {
                        if (isStale(gen)) return
                        emit(VoiceEvent.Failed("setLocalDescription failed: $error"))
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                if (isStale(gen)) return
                emit(VoiceEvent.Failed("createOffer failed: $error"))
            }
        }, MediaConstraints())
    }

    /** True once a newer session has started (or the client was torn down) since [gen] was captured. */
    private fun isStale(gen: Int): Boolean = closed.get() || generation.get() != gen

    private suspend fun exchangeSdp(gen: Int, localOffer: SessionDescription) {
        if (isStale(gen)) return
        val pc = peerConnection ?: return
        val result = signaling.offer(SdpRequest(sdp = localOffer.description, type = "offer"))
        // The network round-trip can outlast the session: re-check before mutating any state.
        if (isStale(gen)) return
        result.fold(
            onSuccess = { answer ->
                pcId = answer.pcId
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        if (isStale(gen)) return
                        flushCandidates()
                        startStatsPolling()
                    }

                    override fun onSetFailure(error: String?) {
                        if (isStale(gen)) return
                        emit(VoiceEvent.Failed("setRemoteDescription failed: $error"))
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
            },
            onFailure = { e ->
                emit(VoiceEvent.Failed("Can't reach $ASSISTANT_NAME: ${e.message ?: "signaling failed"}"))
            },
        )
    }

    private fun flushCandidates() {
        val id = pcId ?: return
        val toSend: List<IceCandidate>
        synchronized(candidateLock) {
            toSend = ArrayList(pendingCandidates)
            pendingCandidates.clear()
        }
        if (toSend.isEmpty()) return
        scope.launch { patch(id, toSend) }
    }

    private suspend fun patch(id: String, candidates: List<IceCandidate>) {
        val patch = IcePatch(
            pcId = id,
            candidates = candidates.map {
                IceCandidatePatch(
                    candidate = it.sdp,
                    sdpMid = it.sdpMid ?: "0",
                    sdpMlineIndex = it.sdpMLineIndex,
                )
            },
        )
        signaling.patchIce(patch).onFailure {
            // A failed ICE PATCH is NON-FATAL and must never be promoted to terminal Failed: the
            // bundled answer candidates may already pair, and a later candidate re-patches. A 404 in
            // particular just means the server doesn't know this pc_id yet (or it was evicted on a
            // single-session teardown). Tearing the call down here would kill a session that ICE can
            // still complete. The real failure signal is the connection-state observer
            // (onConnectionChange/onIceConnectionChange FAILED) or the stats stall path
            // (MediaStalled) — both stay intact. So a trickle failure is dropped here, not emitted.
        }
    }

    /** Poll inbound-rtp.bytesReceived → MediaFlowing / MediaStalled (honest no-audio). */
    private fun startStatsPolling() {
        statsJob = scope.launch {
            var lastBytes = -1L
            var stalledTicks = 0
            var everFlowed = false
            var ticks = 0
            var noInitialMediaFired = false
            while (isActive && !closed.get()) {
                delay(1_000)
                ticks++
                val pc = peerConnection ?: break
                val bytes = collectInboundBytes(pc)
                // Initial-media deadline: if inbound audio NEVER arrives, the flat-line path below
                // can't fire (it gates on everFlowed). Without this, a connected-but-silent call
                // shows "Your turn" forever. Flag a one-time NoAudio once the window lapses.
                if (!everFlowed && !noInitialMediaFired && ticks >= INITIAL_MEDIA_DEADLINE_TICKS) {
                    noInitialMediaFired = true
                    emit(VoiceEvent.MediaStalled)
                }
                if (bytes < 0) continue // stats not ready yet
                if (lastBytes >= 0) {
                    if (bytes > lastBytes) {
                        everFlowed = true
                        // Recover from either a mid-call stall OR a late first-media arrival.
                        if (stalledTicks > 0 || noInitialMediaFired) emit(VoiceEvent.MediaFlowing)
                        noInitialMediaFired = false
                        stalledTicks = 0
                    } else {
                        stalledTicks++
                        // Grace: only flag NoAudio after media was expected (we've seen the loop run)
                        // and stays flat for >2s.
                        if (everFlowed && stalledTicks >= 3) emit(VoiceEvent.MediaStalled)
                    }
                }
                lastBytes = bytes
            }
        }
    }

    private suspend fun collectInboundBytes(pc: PeerConnection): Long {
        val result = kotlinx.coroutines.suspendCancellableCoroutine<Long> { cont ->
            pc.getStats { report ->
                val total = report.statsMap.values
                    .filter { it.type == "inbound-rtp" }
                    .sumOf { stat ->
                        (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    }
                if (cont.isActive) cont.resumeWith(Result.success(total))
            }
        }
        return result
    }

    private fun configureAudioRouting() {
        runCatching {
            val am = audioManager
            priorAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        // Glasses first: if the toggle handed us a supported router and it grabs the glasses' BT
        // device, Atlas speaks out the glasses. Otherwise fall back to the normal hands-free speaker.
        if (!(glassesAudio.isSupported && glassesAudio.routeSpeechToGlasses())) {
            routeAudio(toSpeaker = true) // default hands-free, like a speakerphone call
        }
    }

    private fun restoreAudioRouting() {
        runCatching { glassesAudio.restore() }
        runCatching {
            val am = audioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) am.isSpeakerphoneOn = false
            am.mode = priorAudioMode
        }
    }

    /**
     * Route call audio to the loudspeaker (true) or the earpiece (false). On API 31+ the only API
     * that actually works is [AudioManager.setCommunicationDevice]; `isSpeakerphoneOn` is deprecated
     * and a no-op on modern devices (which is why the speaker toggle did nothing). Falls back to the
     * legacy flag below API 31.
     */
    private fun routeAudio(toSpeaker: Boolean) {
        runCatching {
            val am = audioManager
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wantType =
                    if (toSpeaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = am.availableCommunicationDevices.firstOrNull { it.type == wantType }
                if (device != null) am.setCommunicationDevice(device) else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = toSpeaker
            }
        }
    }

    /**
     * Dispose this session's native objects WITHOUT killing the client. Safe to call before a
     * reconnect (unlike [hangUp], which also flips [closed] and cancels the scope). Cancels the
     * stats loop so a stale poller can't emit against the next session's peer connection.
     */
    private fun teardownSession() {
        // Invalidate any in-flight SDP/offer callbacks from the session being torn down.
        generation.incrementAndGet()
        statsJob?.cancel()
        statsJob = null
        runCatching { peerConnection?.dispose() }
        runCatching { micTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        peerConnection = null
        micTrack = null
        audioSource = null
        remoteAudioTrack = null
        pcId = null
        synchronized(candidateLock) { pendingCandidates.clear() }
    }

    override fun hangUp() {
        if (!closed.compareAndSet(false, true)) return
        teardownSession()
        restoreAudioRouting()
        // Terminal teardown: free the native graph that teardownSession() deliberately keeps alive
        // for a reconnect. Dispose the factory and the JavaAudioDeviceModule so the native AEC/NS
        // engine, capture path, and signaling client release their resources instead of leaking
        // until process death. Order: factory before its ADM (the factory owns references to it).
        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        runCatching { signaling.close() }
        factory = null
        audioDeviceModule = null
        emit(VoiceEvent.HangUp)
        scope.cancel()
    }

    override fun interrupt() {
        // Barge-in: stop Atlas's current playback and KEEP it stopped for this turn so the user
        // reclaims the floor (without tearing the session down). Audio returns on the next bot
        // turn: PcObserver.onAddTrack/onTrack assigns a fresh remoteAudioTrack and re-enables it.
        // (The previous version re-enabled the track on this same call, making barge-in a no-op.)
        runCatching { remoteAudioTrack?.setEnabled(false) }
        emit(VoiceEvent.Interrupt)
    }

    override fun setMicMuted(muted: Boolean) {
        // Gating the local track stops outbound RTP entirely — the truthful "Atlas can't hear me".
        runCatching { micTrack?.setEnabled(!muted) }
    }

    override fun setSpeakerphone(on: Boolean) {
        routeAudio(toSpeaker = on)
    }

    private fun ensureFactory() {
        if (factory != null) return
        synchronized(this) {
            if (factory != null) return
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        // Android's "weak host model" makes libwebrtc send VPN-routed packets from
                        // the wrong source address unless sockets are bound by interface name. Without
                        // this the tailscale0 tun is never offered as a host candidate, so media has
                        // no path home and the bot hears silence (spec §5 relies on 100.x ↔ 100.x).
                        .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                        .createInitializationOptions(),
                )
                initialized = true
            }
            // networkIgnoreMask = 0 → gather on ALL adapters, including the tailscale VPN tun.
            val options = PeerConnectionFactory.Options().apply { networkIgnoreMask = 0 }
            // REAL echo cancellation: the goog* AudioSource constraints are largely ignored by modern
            // libwebrtc — AEC/NS actually live on the AudioDeviceModule. Build one explicitly with the
            // platform hardware AEC + NS engaged (Samsung's is strong) and the VOICE_COMMUNICATION
            // capture path. This is what lets Atlas keep the mic OPEN while it speaks without hearing
            // herself — the prerequisite for barge-in (drop the half-duplex gate once verified).
            val adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            audioDeviceModule = adm
            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
        }
    }

    /** PeerConnection.Observer — all callbacks fire on the webrtc signaling thread → marshal. */
    private inner class PcObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val id = pcId
            if (id == null) {
                synchronized(candidateLock) { pendingCandidates.add(candidate) }
            } else {
                scope.launch { patch(id, listOf(candidate)) }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> emit(VoiceEvent.IceConnected)
                PeerConnection.PeerConnectionState.DISCONNECTED -> emit(VoiceEvent.Dropped)
                PeerConnection.PeerConnectionState.FAILED ->
                    emit(VoiceEvent.Failed("Connection failed"))
                else -> Unit
            }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> emit(VoiceEvent.IceConnected)
                PeerConnection.IceConnectionState.DISCONNECTED -> emit(VoiceEvent.Dropped)
                PeerConnection.IceConnectionState.FAILED ->
                    emit(VoiceEvent.Failed("ICE failed"))
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>?) {
            val track = receiver.track()
            if (track is AudioTrack) {
                remoteAudioTrack = track
                track.setEnabled(true)
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is AudioTrack) {
                remoteAudioTrack = track
                track.setEnabled(true)
            }
        }

        // Unused but required by the interface.
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private companion object {
        @Volatile
        var initialized = false

        // Seconds to wait after the stats loop starts for the FIRST inbound audio before
        // declaring an honest no-audio state (distinct from a mid-call stall).
        const val INITIAL_MEDIA_DEADLINE_TICKS = 8
    }
}

/** SdpObserver with no-op defaults so call sites override only what they need. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
