package app.eve.glasses

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Routes EVE's spoken (TTS) audio out the glasses' speaker. Per Meta's DAT docs there is **no
 * toolkit audio API** — a DAT session shares the glasses' mic/speaker with the system Bluetooth
 * stack, so playing to the glasses is exactly standard Android Bluetooth routing (A2DP for output,
 * HFP/SCO or BLE for the bidirectional voice call). We therefore select the glasses as the
 * `AudioManager` *communication device*, which is real and works WITHOUT the DAT SDK bundled — the
 * glasses just have to be paired as a Bluetooth audio device.
 *
 * Two impls: [BluetoothGlassesAudioRouter] (real, default when a Context is available) and
 * [NoGlassesAudioRouter] (inert). The pure device-type choice lives in [GlassesAudioRouting] so it
 * can be unit-tested without an AudioManager.
 */
interface GlassesAudioRouter {
    /** Whether routing to glasses is possible at all in this build (false for the inert stub). */
    val isSupported: Boolean

    /**
     * Try to route in-call (communication) audio to a glasses / Bluetooth device. Returns true only
     * if a suitable device was actually selected; false (no-op) if none is present so the caller
     * keeps its normal speaker/earpiece routing.
     */
    fun routeSpeechToGlasses(): Boolean

    /** Undo any glasses routing this router applied. Idempotent; safe if nothing was routed. */
    fun restore()
}

/** Inert router: used when the glasses toggle is off or no Context is available. Never routes. */
object NoGlassesAudioRouter : GlassesAudioRouter {
    override val isSupported: Boolean = false
    override fun routeSpeechToGlasses(): Boolean = false
    override fun restore() {}
}

/**
 * Real router. Selects a paired glasses/Bluetooth communication device via
 * [AudioManager.setCommunicationDevice] (API 31+) so the WebRTC voice call's playback rides the
 * glasses' speaker. Below API 31 it falls back to the legacy Bluetooth SCO path. Everything is
 * wrapped so an audio-stack hiccup can never crash the voice session.
 */
class BluetoothGlassesAudioRouter(context: Context) : GlassesAudioRouter {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var routed = false

    override val isSupported: Boolean = true

    override fun routeSpeechToGlasses(): Boolean {
        return runCatching {
            val am = audioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val available = am.availableCommunicationDevices
                val wantType = GlassesAudioRouting.pickCommunicationDeviceType(available.map { it.type })
                    ?: return@runCatching false
                val device = available.firstOrNull { it.type == wantType } ?: return@runCatching false
                val ok = am.setCommunicationDevice(device)
                routed = ok
                ok
            } else {
                @Suppress("DEPRECATION")
                run {
                    am.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = true
                    routed = true
                    true
                }
            }
        }.getOrElse {
            Log.w(TAG, "routeSpeechToGlasses failed", it)
            false
        }
    }

    override fun restore() {
        if (!routed) return
        runCatching {
            val am = audioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                run {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
        }
        routed = false
    }

    private companion object {
        const val TAG = "GlassesAudio"
    }
}

/**
 * Pure choice of which communication-device type to prefer for glasses audio, given the types the
 * system currently offers. No Android AudioManager needed → unit-testable. Preference order:
 * a BLE headset (Meta glasses present as LE Audio on newer stacks) then classic Bluetooth SCO.
 * Returns null when neither is available (so the caller leaves routing untouched).
 */
object GlassesAudioRouting {
    fun pickCommunicationDeviceType(availableTypes: List<Int>): Int? {
        val preferred = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        return preferred.firstOrNull { it in availableTypes }
    }
}
