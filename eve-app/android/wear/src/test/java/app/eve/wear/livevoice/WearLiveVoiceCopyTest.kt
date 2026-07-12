package app.eve.wear.livevoice

import app.eve.ASSISTANT_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the exact user-facing copy (the same discipline as WearTalkCopyTest): the failure-surface
 * strings and the per-state orb labels are asserted verbatim so the screen, VM, controller and codec
 * can never drift on wording.
 */
class WearLiveVoiceCopyTest {

    @Test fun not_configured_copy_is_exact() {
        assertEquals("No voice door configured — set it in phone Settings.", WearLiveVoiceCopy.NOT_CONFIGURED)
    }

    @Test fun failure_leg_copy_is_exact() {
        assertEquals("Can't reach $ASSISTANT_NAME — the voice door didn't answer.", WearLiveVoiceCopy.CONNECT_TIMED_OUT)
        assertEquals("$ASSISTANT_NAME isn't responding.", WearLiveVoiceCopy.THINK_TIMED_OUT)
        assertEquals("Lost connection to $ASSISTANT_NAME.", WearLiveVoiceCopy.CONNECTION_LOST)
        assertEquals("No network — the watch can't reach the voice door.", WearLiveVoiceCopy.NO_NETWORK)
        assertEquals("Microphone unavailable — check permission and try again.", WearLiveVoiceCopy.MIC_UNAVAILABLE)
        assertEquals("$ASSISTANT_NAME sent something the watch couldn't read.", WearLiveVoiceCopy.BAD_CONTROL_FRAME)
    }

    @Test fun server_error_copy_embeds_the_real_detail() {
        assertTrue(WearLiveVoiceCopy.serverError("TTS crashed").contains("TTS crashed"))
    }

    @Test fun orb_labels_are_exact_per_state() {
        assertEquals("No voice door configured — set it in phone Settings.", orbContentDescription(VoiceState.NotConfigured))
        assertEquals("Tap to talk to $ASSISTANT_NAME", orbContentDescription(VoiceState.Idle))
        assertEquals("Connecting to $ASSISTANT_NAME", orbContentDescription(VoiceState.Connecting))
        assertEquals("Go ahead, I'm listening", orbContentDescription(VoiceState.YourTurn))
        assertEquals("Hearing you", orbContentDescription(VoiceState.Hearing(0.5f)))
        assertEquals("$ASSISTANT_NAME is thinking", orbContentDescription(VoiceState.Thinking))
        assertEquals("$ASSISTANT_NAME is speaking", orbContentDescription(VoiceState.Speaking))
        assertEquals("Reconnecting", orbContentDescription(VoiceState.Reconnecting))
        assertEquals("Connected, but no audio is getting through", orbContentDescription(VoiceState.NoAudio))
        assertEquals("Connection problem: nope", orbContentDescription(VoiceState.Error("nope")))
    }
}
