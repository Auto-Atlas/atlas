package app.eve.glasses

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure device-type choice for glasses audio (no AudioManager). Meta's DAT exposes no audio API,
 * so EVE's TTS rides the glasses as a standard Bluetooth device — we prefer LE Audio (BLE headset)
 * then classic Bluetooth SCO, and leave routing untouched when neither is present.
 */
class GlassesAudioRoutingTest {

    @Test
    fun `prefers BLE headset when available`() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, GlassesAudioRouting.pickCommunicationDeviceType(types))
    }

    @Test
    fun `falls back to bluetooth SCO when no BLE`() {
        val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, GlassesAudioRouting.pickCommunicationDeviceType(types))
    }

    @Test
    fun `returns null when no bluetooth audio device present`() {
        val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        assertNull(GlassesAudioRouting.pickCommunicationDeviceType(types))
        assertNull(GlassesAudioRouting.pickCommunicationDeviceType(emptyList()))
    }
}
