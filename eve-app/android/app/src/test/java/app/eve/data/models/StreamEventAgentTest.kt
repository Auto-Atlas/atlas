package app.eve.data.models

import app.eve.data.ApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-shape tests for the agent talk-back lifecycle events (live-delegation-approvals).
 * Each JSON body below is EXACTLY what the server emits (agent_delivery._broadcast,
 * jarvis_core._emit_assigned, a2a_fabric/approval_api cancel+redirect broadcasts) — the
 * transcript forwarder republishes them verbatim with ts/src added, which
 * ignoreUnknownKeys must tolerate.
 */
class StreamEventAgentTest {

    private fun decode(json: String): StreamEvent =
        ApiClient.DEFAULT_JSON.decodeFromString(StreamEvent.serializer(), json)

    @Test
    fun agent_progress_parses_task_identity_and_text() {
        val e = decode(
            """{"ts":"2026-07-10T20:00:00.000","src":"local","type":"agent_progress",
                "agent":"hermes","summary":"long research job","text":"crawling the site",
                "cid":"abc123","task_id":"abc123","status":"pending"}""",
        )
        assertTrue(e.isAgentProgress)
        assertTrue(e.isAgentTaskEvent)
        assertEquals("abc123", e.taskId)
        assertEquals("hermes", e.agent)
        assertEquals("crawling the site", e.text)
        assertEquals("long research job", e.summary)
    }

    @Test
    fun agent_task_assigned_parses_task_text() {
        val e = decode(
            """{"type":"agent_task_assigned","agent":"hermes","task_id":"c1","cid":"c1",
                "task":"check the deploy","summary":"check the deploy","status":"pending"}""",
        )
        assertTrue(e.isAgentTaskAssigned)
        assertEquals("check the deploy", e.task)
    }

    @Test
    fun agent_question_result_blocker_cancelled_redirected_all_classify() {
        assertTrue(decode("""{"type":"agent_question","task_id":"t"}""").isAgentQuestion)
        assertTrue(decode("""{"type":"agent_result","task_id":"t"}""").isAgentResult)
        assertTrue(decode("""{"type":"agent_blocker","task_id":"t"}""").isAgentBlocker)
        val c = decode("""{"type":"agent_task_cancelled","task_id":"t","status":"cancel_requested"}""")
        assertTrue(c.isAgentTaskCancelled)
        assertEquals("cancel_requested", c.status)
        val r = decode(
            """{"type":"agent_task_redirected","task_id":"t","text":"new steer",
                "status":"redirect_delivered"}""",
        )
        assertTrue(r.isAgentTaskRedirected)
        assertEquals("new steer", r.text)
    }

    @Test
    fun non_agent_events_do_not_classify_as_agent() {
        assertFalse(decode("""{"type":"tool_call","name":"check_email"}""").isAgentTaskEvent)
        assertFalse(decode("""{"type":"approval_resolved","id":"a1"}""").isAgentTaskEvent)
    }
}
