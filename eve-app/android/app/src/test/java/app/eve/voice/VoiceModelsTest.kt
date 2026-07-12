package app.eve.voice

import app.eve.data.ApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serialization contract for the SmallWebRTC DTOs. Uses the SAME Json config the wire layer
 * runs ([ApiClient.DEFAULT_JSON], `explicitNulls = false`) — testing a serializer config the
 * app never uses would be a lie. The answer-parse + snake_case assertions are validated against
 * the live phone_bot fixture committed under test/resources (captured 2026-06-20).
 */
class VoiceModelsTest {

    private val json = ApiClient.DEFAULT_JSON

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun offer_serializes_to_pipecat_fields_and_omits_null_pc_id() {
        val req = SdpRequest(sdp = "v=0...", type = "offer")
        val s = json.encodeToString(SdpRequest.serializer(), req)
        assertTrue(s.contains("\"sdp\"") && s.contains("\"type\":\"offer\""), s)
        assertTrue(!s.contains("pc_id"), "null pc_id must be omitted (explicitNulls=false): $s")
        assertTrue(!s.contains("restart_pc"), "null restart_pc must be omitted: $s")
    }

    @Test
    fun offer_with_pc_id_uses_snake_case_key() {
        val req = SdpRequest(sdp = "v=0...", type = "offer", pcId = "abc123", restartPc = true)
        val s = json.encodeToString(SdpRequest.serializer(), req)
        assertTrue(s.contains("\"pc_id\":\"abc123\""), s)
        assertTrue(s.contains("\"restart_pc\":true"), s)
        assertTrue(!s.contains("pcId") && !s.contains("restartPc"), "camelCase keys must not leak: $s")
    }

    @Test
    fun answer_parses_the_live_fixture_shape() {
        val a = json.decodeFromString(SdpAnswer.serializer(), fixture("offer_answer_sample.json"))
        assertEquals("answer", a.type)
        assertTrue(a.pcId.isNotBlank(), "pc_id must parse from the live answer")
        assertTrue(a.sdp.startsWith("v=0"), "sdp must parse from the live answer")
    }

    @Test
    fun answer_parses_minimal_shape() {
        val a = json.decodeFromString(
            SdpAnswer.serializer(),
            """{"sdp":"v=0...","type":"answer","pc_id":"abc123"}""",
        )
        assertEquals("answer", a.type)
        assertEquals("abc123", a.pcId)
    }

    @Test
    fun ice_patch_serializes_snake_case_candidate_fields() {
        val p = IcePatch(
            pcId = "abc123",
            candidates = listOf(
                IceCandidatePatch(
                    candidate = "candidate:1 1 udp 2130706431 172.23.0.1 50100 typ host",
                    sdpMid = "0",
                    sdpMlineIndex = 0,
                ),
            ),
        )
        val s = json.encodeToString(IcePatch.serializer(), p)
        assertTrue(s.contains("\"pc_id\":\"abc123\""), s)
        assertTrue(s.contains("\"candidates\""), s)
        // Winston's critical catch: the server does IceCandidate(**c) against snake_case fields.
        assertTrue(s.contains("\"sdp_mid\":\"0\""), "must be snake_case sdp_mid: $s")
        assertTrue(s.contains("\"sdp_mline_index\":0"), "must be snake_case sdp_mline_index: $s")
        assertTrue(!s.contains("sdpMid") && !s.contains("sdpMlineIndex"), "camelCase must not leak: $s")
    }
}
