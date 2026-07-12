package app.eve.data.wear

import app.eve.ASSISTANT_NAME
import app.eve.data.models.ApprovalsResponse
import app.eve.data.models.SystemStatus
import app.eve.data.models.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips every phone<->watch DTO over the SAME EveWireJson bytes both sides use, including a
 * real [app.eve.data.models.Approval] built from the committed backend fixture. Also proves every
 * [Outcome] survives the wire and that garbage bytes fail LOUDLY (never a fake decode).
 */
class WearLinkTest {

    private fun fixtureApprovals() =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json")) {
            "missing fixture approvals_sample.json"
        }.bufferedReader().use { r ->
            app.eve.data.EveWireJson.decodeFromString<ApprovalsResponse>(r.readText()).approvals
        }

    @Test
    fun approvals_snapshot_roundtrips_with_real_approval() {
        val original = ApprovalsSnapshot(
            approvals = fixtureApprovals(),
            fetchedAtEpochMs = 1_750_000_000_000L,
            serverReachable = true,
        )
        val back = ApprovalsSnapshot.fromBytes(original.toBytes())
        assertEquals(original, back)
        // The Approval's computed-from-args total must survive the wire intact.
        assertEquals(1200.0, back.approvals[0].totalDollars)
        assertEquals(true, back.serverReachable)
        assertNull(back.errorDetail)
    }

    @Test
    fun approvals_snapshot_carries_leg_down_signal() {
        // The honest "phone<->server leg down" snapshot: no list, serverReachable=false, real detail.
        val original = ApprovalsSnapshot(
            approvals = emptyList(),
            fetchedAtEpochMs = 42L,
            serverReachable = false,
            errorDetail = "cannot reach $ASSISTANT_NAME: connection refused",
        )
        val back = ApprovalsSnapshot.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals(false, back.serverReachable)
        assertEquals("cannot reach $ASSISTANT_NAME: connection refused", back.errorDetail)
    }

    @Test
    fun status_snapshot_roundtrips_with_status() {
        val original = StatusSnapshot(
            status = SystemStatus(desktopOnline = true, pendingApprovals = 3, telemetry = Telemetry(totalTokens = 99L)),
            fetchedAtEpochMs = 7L,
            serverReachable = true,
        )
        val back = StatusSnapshot.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals(3, back.status?.pendingApprovals)
    }

    @Test
    fun status_snapshot_roundtrips_with_null_status_when_leg_down() {
        val original = StatusSnapshot(
            status = null,
            fetchedAtEpochMs = 8L,
            serverReachable = false,
            errorDetail = "unauthorized (401)",
        )
        val back = StatusSnapshot.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertNull(back.status)
        assertEquals("unauthorized (401)", back.errorDetail)
    }

    @Test
    fun wear_action_roundtrips() {
        val original = WearAction(requestId = "req-1", approvalId = "a1b2c3d4")
        assertEquals(original, WearAction.fromBytes(original.toBytes()))
    }

    @Test
    fun wear_action_result_roundtrips_for_every_outcome() {
        for (outcome in Outcome.entries) {
            val original = WearActionResult(
                requestId = "req-$outcome",
                approvalId = "abc",
                outcome = outcome,
                detail = "detail for $outcome",
            )
            val back = WearActionResult.fromBytes(original.toBytes())
            assertEquals(original, back, "outcome $outcome must survive the wire")
            assertEquals(outcome, back.outcome)
        }
    }

    @Test
    fun outcome_encodes_lowercase_on_the_wire() {
        val bytes = WearActionResult("r", "a", Outcome.ALREADY_RESOLVED).toBytes()
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("already_resolved"), "enum must serialize to its @SerialName: $text")
    }

    @Test
    fun talk_request_roundtrips() {
        val original = TalkRequest(requestId = "talk-1", text = "what's on my calendar today?")
        val back = TalkRequest.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals("what's on my calendar today?", back.text)
    }

    @Test
    fun talk_reply_roundtrips_ok_with_answer_text() {
        val original = TalkReply(requestId = "talk-1", reply = "You have a 3pm with Jamie.", outcome = Outcome.OK)
        val back = TalkReply.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertEquals(Outcome.OK, back.outcome)
        assertEquals("You have a 3pm with Jamie.", back.reply)
        assertNull(back.detail)
    }

    @Test
    fun talk_reply_roundtrips_failure_leg_with_null_reply_and_real_detail() {
        // An honest failure leg: no answer text, the real reason in detail (never a fake OK).
        val original = TalkReply(
            requestId = "talk-2",
            reply = null,
            outcome = Outcome.SERVER_UNREACHABLE,
            detail = "cannot reach $ASSISTANT_NAME: connection refused",
        )
        val back = TalkReply.fromBytes(original.toBytes())
        assertEquals(original, back)
        assertNull(back.reply)
        assertEquals("cannot reach $ASSISTANT_NAME: connection refused", back.detail)
    }

    @Test
    fun ok_outcome_encodes_as_ok_on_the_wire() {
        val text = String(TalkReply("r", "hi", Outcome.OK).toBytes(), Charsets.UTF_8)
        assertTrue(text.contains("\"ok\""), "Outcome.OK must serialize to its @SerialName: $text")
    }

    @Test
    fun talk_dtos_fail_loudly_on_garbage() {
        assertFailsWith<Exception> { TalkRequest.fromBytes("nope".toByteArray()) }
        assertFailsWith<Exception> { TalkReply.fromBytes(byteArrayOf(0x00, 0x01, 0x02)) }
    }

    @Test
    fun garbage_bytes_fail_loudly_not_a_fake_result() {
        // A corrupt/garbage result must THROW, never silently decode to a fake success.
        assertFailsWith<Exception> { WearActionResult.fromBytes("12345".toByteArray()) }
        assertFailsWith<Exception> { WearActionResult.fromBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03)) }
        assertFailsWith<Exception> { ApprovalsSnapshot.fromBytes("not-an-object".toByteArray()) }
    }
}
