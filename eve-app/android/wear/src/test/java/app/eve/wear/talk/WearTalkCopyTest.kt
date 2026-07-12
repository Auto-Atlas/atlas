package app.eve.wear.talk

import app.eve.ASSISTANT_NAME
import app.eve.data.wear.Outcome
import app.eve.data.wear.VoiceTurnReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the EXACT user-facing strings for the v2 native voice path (the same discipline as
 * [app.eve.wear.approvals.WearActionCopyTest]). If any of these wordings change, this test must change
 * with them — copy is centralized in [WearTalkCopy] and asserted verbatim, never drifting.
 */
class WearTalkCopyTest {

    @Test
    fun native_path_copy_is_exact() {
        assertEquals("Listening…", WearTalkCopy.RECORDING)
        assertEquals("Sending…", WearTalkCopy.SENDING)
        assertEquals("Microphone permission needed", WearTalkCopy.MIC_PERMISSION)
        assertEquals("Microphone is busy — try again", WearTalkCopy.MIC_BUSY)
        assertEquals("Didn't hear anything — tap to retry.", WearTalkCopy.RECORDING_EMPTY)
        assertEquals("$ASSISTANT_NAME's voice is unavailable — text only", WearTalkCopy.VOICE_UNAVAILABLE)
        assertEquals("Couldn't play $ASSISTANT_NAME's voice — reply shown above", WearTalkCopy.PLAYBACK_FAILED)
        assertEquals("Google voice (fallback)", WearTalkCopy.FALLBACK_LABEL)
    }

    @Test
    fun countdown_reads_seconds_left() {
        assertEquals("5s left", WearTalkCopy.countdown(5))
        assertEquals("1s left", WearTalkCopy.countdown(1))
    }

    @Test
    fun channel_no_reply_carries_the_real_reason() {
        assertEquals("No reply from phone: channel closed", WearTalkCopy.channelNoReply("channel closed"))
    }

    @Test
    fun voice_reply_failure_mapping_matches_the_talk_vocabulary() {
        assertNull(WearTalkCopy.failureForVoice(VoiceTurnReply("r", reply = "hi", outcome = Outcome.OK)))
        assertEquals(
            "Phone can't reach $ASSISTANT_NAME: connection refused",
            WearTalkCopy.failureForVoice(VoiceTurnReply("r", outcome = Outcome.SERVER_UNREACHABLE, detail = "connection refused")),
        )
        assertEquals(
            "unauthorized (401) — reconnect the phone",
            WearTalkCopy.failureForVoice(VoiceTurnReply("r", outcome = Outcome.UNAUTHORIZED, detail = "unauthorized (401) — reconnect the phone")),
        )
        assertEquals(
            "Something went wrong",
            WearTalkCopy.failureForVoice(VoiceTurnReply("r", outcome = Outcome.ERROR, detail = null)),
        )
    }
}
