package app.eve.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceUrlTest {
    @Test
    fun override_wins() = assertEquals(
        "http://10.0.2.2:8789",
        deriveVoiceUrl("https://h.ts.net:8443", "http://10.0.2.2:8789"),
    )

    @Test
    fun derive_swaps_port() = assertEquals(
        "https://h.ts.net:8444",
        deriveVoiceUrl("https://h.ts.net:8443", ""),
    )

    @Test
    fun derive_handles_no_port() = assertEquals(
        "https://h.ts.net:8444",
        deriveVoiceUrl("https://h.ts.net", ""),
    )

    @Test
    fun blank_base_is_null() = assertNull(deriveVoiceUrl("", ""))

    @Test
    fun garbage_is_null() = assertNull(deriveVoiceUrl("not a url", ""))

    @Test
    fun blank_override_falls_through_to_derive() = assertEquals(
        "https://h.ts.net:8444",
        deriveVoiceUrl("https://h.ts.net:8443", "   "),
    )

    // ---- deriveWatchVoiceDoorUrl (automatic wrist pairing) ----

    @Test
    fun door_derives_wss_funnel_from_approval_base() = assertEquals(
        "wss://h.ts.net:10000/v1/watch/voice",
        deriveWatchVoiceDoorUrl("https://h.ts.net:8443", ""),
    )

    @Test
    fun door_derives_when_base_has_no_port() = assertEquals(
        "wss://h.ts.net:10000/v1/watch/voice",
        deriveWatchVoiceDoorUrl("https://h.ts.net", ""),
    )

    @Test
    fun door_override_wins_verbatim() = assertEquals(
        "wss://custom.example.net:444/voice",
        deriveWatchVoiceDoorUrl("https://h.ts.net:8443", "wss://custom.example.net:444/voice"),
    )

    @Test
    fun door_blank_base_is_null() = assertNull(deriveWatchVoiceDoorUrl("", ""))

    @Test
    fun door_garbage_base_is_null() = assertNull(deriveWatchVoiceDoorUrl("not a url", "  "))
}
