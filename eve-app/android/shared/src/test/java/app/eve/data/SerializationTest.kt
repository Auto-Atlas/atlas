package app.eve.data

import app.eve.data.models.ApprovalsResponse
import app.eve.data.models.ClearResult
import app.eve.data.models.FeedsResponse
import app.eve.data.models.MemoryAddResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips the EXACT backend JSON (the committed fixture captured from the ROW shape in
 * approval_store._row_to_dict) and asserts the computed total is derived from frozen args —
 * never from the summary string (the core design contract).
 */
class SerializationTest {

    // The canonical wire config itself — the same instance ApiClient.DEFAULT_JSON aliases.
    private val json = EveWireJson

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun parses_real_backend_approvals_payload() {
        val resp = json.decodeFromString<ApprovalsResponse>(fixture("approvals_sample.json"))
        assertEquals(2, resp.approvals.size)

        val invoice = resp.approvals[0]
        assertEquals("create_invoice", invoice.tool)
        assertEquals("known", invoice.requesterTier)
        assertEquals("high", invoice.riskLevel)
        assertEquals("Jamie", invoice.requester)
        assertEquals("pending", invoice.status)
        assertEquals(14400, invoice.ttlSeconds)
        assertNull(invoice.result)
    }

    @Test
    fun invoice_total_is_computed_from_args_not_summary() {
        val resp = json.decodeFromString<ApprovalsResponse>(fixture("approvals_sample.json"))
        val invoice = resp.approvals[0]

        // 2 * 480 + 1 * 240 = 1200, computed from line_items.
        assertEquals(1200.0, invoice.totalDollars)

        val args = requireNotNull(invoice.invoiceArgs)
        assertEquals("The Browns", args.customerName)
        assertEquals(2, args.lineItems.size)
        assertEquals(480.0, args.lineItems[0].rate)
        assertEquals(960.0, args.lineItems[0].amount)
    }

    @Test
    fun channel_row_has_no_amount_and_exposes_message() {
        val resp = json.decodeFromString<ApprovalsResponse>(fixture("approvals_sample.json"))
        val channel = resp.approvals[1]
        assertEquals("send_to_channel", channel.tool)
        assertNull(channel.totalDollars)
        val args = requireNotNull(channel.channelArgs)
        assertEquals("telegram", args.channel)
        assertTrue(args.message.startsWith("Reminder:"))
    }

    @Test
    fun roundtrip_is_lossless_for_args() {
        val resp = json.decodeFromString<ApprovalsResponse>(fixture("approvals_sample.json"))
        // Re-encode and re-decode; the computed total must survive intact (args preserved).
        val reencoded = json.encodeToString(ApprovalsResponse.serializer(), resp)
        val again = json.decodeFromString<ApprovalsResponse>(reencoded)
        assertEquals(1200.0, again.approvals[0].totalDollars)
        assertEquals("telegram", again.approvals[1].channelArgs?.channel)
    }

    @Test
    fun decodes_skills_and_feed_payloads() {
        val skills = json.decodeFromString<app.eve.data.models.SkillsResponse>(
            """{"skills":[{"tool":"get_weather","catalog":"Weather.","risk":"low","requires_confirmation":false}]}""",
        )
        assertEquals("get_weather", skills.skills[0].tool)
        assertEquals(false, skills.skills[0].requiresConfirmation)
        val feeds = json.decodeFromString<app.eve.data.models.FeedsResponse>(
            """{"pending":[{"tool":"create_invoice","mode":"next","status":"pending","seconds_left":120.0}]}""",
        )
        assertEquals("next", feeds.pending[0].mode)
    }

    /**
     * GET /v1/skills/feed wire shape: {"pending":[{tool,mode,status,seconds_left}]}.
     * Source serializer: approval_api.py:315-325 (list_skill_feed).
     */
    @Test
    fun decodes_real_skills_feed_payload() {
        val feeds = json.decodeFromString<FeedsResponse>(fixture("skills_feed_sample.json"))
        assertEquals(1, feeds.pending.size)
        assertEquals("create_invoice", feeds.pending[0].tool)
        assertEquals("next", feeds.pending[0].mode)
        assertEquals("pending", feeds.pending[0].status)
        assertEquals(86234.5, feeds.pending[0].secondsLeft)
    }

    /**
     * POST /v1/memory OWNER write wire shape: {"ok": true, "speaker": null, "remembered": "..."}.
     * Source serializer: approval_api.py:441 returns {"ok": True, "speaker": add.speaker,
     * "remembered": fact} where add.speaker is str|None and is None for the owner page. A
     * non-nullable MemoryAddResult.speaker crashed this decode (the bug this fix proves).
     */
    @Test
    fun decodes_real_memory_owner_write_with_null_speaker() {
        val result = json.decodeFromString<MemoryAddResult>(fixture("memory_owner_write_sample.json"))
        assertTrue(result.ok)
        assertNull(result.speaker) // owner write -> null; must not crash the decode
        assertEquals("the owner prefers concise morning briefings.", result.remembered)
    }

    /**
     * DELETE /v1/skills/feed/{tool} wire shape: {"ok": true, "cleared": <int>}.
     * Source serializer: approval_api.py:332 returns {"ok": True, "cleared": cleared} where
     * cleared is the int from skill_feed.clear_pending (skill_feed.py:77). Decoding this as
     * FeedResult crashed on the absent required tool/mode fields — ClearResult is the fix.
     */
    @Test
    fun decodes_real_clear_feed_payload() {
        val result = json.decodeFromString<ClearResult>(fixture("clear_feed_sample.json"))
        assertTrue(result.ok)
        assertEquals(1, result.cleared)
    }
}
