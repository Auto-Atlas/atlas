package app.eve.ui.talk

import app.eve.data.ApiClient
import app.eve.data.models.StreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reducer + wire-decode contract for the live tool-call / delegation feed. Pure JVM (no
 * Compose/Android), so it runs under `./gradlew test`. Mirrors the desktop useJarvisBridge rules —
 * most importantly that a `working` heartbeat refreshes only the live label and never appends a step.
 */
class TalkActivityTest {

    private val json = ApiClient.DEFAULT_JSON

    // ---- tool_call / tool_result lifecycle ----

    @Test
    fun tool_call_then_result_measures_latency_and_flips_status() {
        var s = LiveActivity.IDLE
        s = reduceActivity(s, StreamEvent(type = "tool_call", tool = "check_email", args = "{}", status = "running"), nowMs = 1_000)
        assertEquals(ToolStatus.RUNNING, s.tool?.status)
        assertEquals("check_email", s.tool?.tool)
        assertTrue(s.isWorking)

        s = reduceActivity(s, StreamEvent(type = "tool_result", tool = "check_email", ok = true, detail = "3 new"), nowMs = 1_450)
        assertEquals(ToolStatus.OK, s.tool?.status)
        assertEquals(450L, s.tool?.latencyMs) // 1450 - 1000
        assertEquals("3 new", s.tool?.detail)
        assertFalse(s.isWorking)
    }

    @Test
    fun tool_result_failure_maps_to_error() {
        var s = reduceActivity(LiveActivity.IDLE, StreamEvent(type = "tool_call", tool = "get_news", args = "{}"), nowMs = 0)
        s = reduceActivity(s, StreamEvent(type = "tool_result", tool = "get_news", ok = false, detail = "no feed"), nowMs = 10)
        assertEquals(ToolStatus.ERROR, s.tool?.status)
    }

    // ---- delegation waterfall ----

    @Test
    fun delegation_flow_start_try_working_answer_end() {
        var s = LiveActivity.IDLE
        s = reduceActivity(s, StreamEvent(type = "delegation_start", delegId = "d1", task = "research X", brains = listOf("codex", "glm", "local")), nowMs = 0)
        assertEquals("d1", s.delegation?.delegId)
        assertEquals(3, s.delegation?.brains?.size)
        assertFalse(s.delegation?.done ?: true)
        assertTrue(s.isWorking)

        // try → a step is appended and the active brain is set.
        s = reduceActivity(s, StreamEvent(type = "delegation_step", delegId = "d1", brain = "codex", phase = "try"), nowMs = 0)
        assertEquals(1, s.delegation?.steps?.size)
        assertEquals("codex", s.delegation?.activeBrain)

        // working heartbeat → updates the live label ONLY, never appends a step.
        s = reduceActivity(s, StreamEvent(type = "delegation_step", delegId = "d1", brain = "codex", phase = "working", detail = "16s"), nowMs = 0)
        assertEquals(1, s.delegation?.steps?.size) // unchanged
        assertEquals("16s", s.delegation?.activeDetail)
        assertEquals("codex", s.delegation?.activeBrain)

        // answer → step appended, live label cleared.
        s = reduceActivity(s, StreamEvent(type = "delegation_step", delegId = "d1", brain = "codex", phase = "answer", latencyMs = 1600), nowMs = 0)
        assertEquals(2, s.delegation?.steps?.size)
        assertNull(s.delegation?.activeBrain)

        // end → done, winner, total latency; no longer "working".
        s = reduceActivity(s, StreamEvent(type = "delegation_end", delegId = "d1", brain = "codex", ok = true, totalLatencyMs = 1800), nowMs = 0)
        assertTrue(s.delegation?.done ?: false)
        assertEquals("codex", s.delegation?.winner)
        assertEquals(1800L, s.delegation?.totalLatencyMs)
        assertFalse(s.isWorking)
    }

    @Test
    fun stale_delegation_step_for_other_id_is_ignored() {
        var s = reduceActivity(LiveActivity.IDLE, StreamEvent(type = "delegation_start", delegId = "d1", brains = listOf("codex")), nowMs = 0)
        s = reduceActivity(s, StreamEvent(type = "delegation_step", delegId = "OTHER", brain = "glm", phase = "try"), nowMs = 0)
        assertEquals(0, s.delegation?.steps?.size) // the stray step never corrupts the waterfall
    }

    @Test
    fun rows_keep_chain_order_and_mark_untried_brains_queued() {
        var s = reduceActivity(LiveActivity.IDLE, StreamEvent(type = "delegation_start", delegId = "d1", brains = listOf("codex", "glm", "local")), nowMs = 0)
        s = reduceActivity(s, StreamEvent(type = "delegation_step", delegId = "d1", brain = "codex", phase = "fail"), nowMs = 0)
        val rows = s.delegation!!.rows()
        assertEquals(listOf("codex", "glm", "local"), rows.map { it.brain })
        assertEquals("fail", rows[0].phase)
        assertEquals("queued", rows[1].phase) // glm not tried yet
    }

    // ---- wire decode (the republished JSONL lines, with extra ts/src/tokens fields) ----

    @Test
    fun decodes_delegation_step_with_snake_case_and_ignores_extra_fields() {
        val line = """{"ts":"2026-06-22T10:00:00.000","src":"phone","type":"delegation_step","deleg_id":"abc","brain":"codex","phase":"working","detail":"16s","latency_ms":1234,"tokens":7}"""
        val e = json.decodeFromString<StreamEvent>(line)
        assertEquals("delegation_step", e.type)
        assertEquals("abc", e.delegId)
        assertEquals("codex", e.brain)
        assertEquals("working", e.phase)
        assertEquals(1234L, e.latencyMs)
        assertTrue(e.isDelegationStep)
    }

    @Test
    fun decodes_tool_result_and_unknown_event_type_survives() {
        val toolLine = """{"type":"tool_result","tool":"get_weather","ok":true,"detail":"72F","ts":"x","src":"phone"}"""
        val e = json.decodeFromString<StreamEvent>(toolLine)
        assertTrue(e.isToolResult)
        assertEquals("get_weather", e.tool)
        assertEquals(true, e.ok)

        // A future/unknown event type must still decode (every field optional + ignoreUnknownKeys).
        val unknown = json.decodeFromString<StreamEvent>("""{"type":"some_future_event","whatever":42}""")
        assertEquals("some_future_event", unknown.type)
    }

    // ---- toolVisuals port ----

    @Test
    fun tool_visuals_phrase_known_and_fallback_tools() {
        assertEquals("Handing this to the agent…", toolVisual("jarvis_agent").running)
        assertEquals("Agent", toolVisual("jarvis_agent").title)
        assertEquals("Checking your email…", toolVisual("check_email").running)
        // Unknown id → title-cased name + "Running …".
        val unknown = toolVisual("do_a_barrel_roll")
        assertEquals("Do A Barrel Roll", unknown.title)
        assertEquals("Running Do A Barrel Roll…", unknown.running)
    }

    @Test
    fun thinking_event_toggles_thinking_flag() {
        var s = reduceActivity(LiveActivity.IDLE, StreamEvent(type = "thinking", active = true), nowMs = 0)
        assertTrue(s.thinking)
        s = reduceActivity(s, StreamEvent(type = "thinking", active = false), nowMs = 0)
        assertFalse(s.thinking)
    }
}
