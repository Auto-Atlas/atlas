package app.eve.wearbridge

import app.eve.ASSISTANT_NAME
import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.ApprovalRepository
import app.eve.data.ApproveOutcome
import app.eve.data.DenyOutcome
import app.eve.data.EveConnection
import app.eve.data.models.Approval
import app.eve.data.models.ApprovalsResponse
import app.eve.data.models.SystemStatus
import app.eve.data.wear.ApprovalsSnapshot
import app.eve.data.wear.Outcome
import app.eve.data.wear.StatusSnapshot
import app.eve.data.wear.WearAction
import app.eve.data.wear.WearActionResult
import app.eve.data.wear.WearLink
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure [WearBridge] core with fakes (no GMS). Asserts the exact outcome mapping,
 * that a resolved item still refreshes both snapshots, that a fetch failure writes the honest
 * `serverReachable=false` snapshot, and that the refresh is a FRESH fetch AFTER the action.
 */
class WearBridgeTest {

    // A configured-looking ApiClient just to satisfy the ApprovalRepository super-ctor; every
    // repo method the tests exercise is overridden, so this engine is never actually hit.
    private fun fakeApi() = ApiClient(
        engine = MockEngine { respond("", HttpStatusCode.OK) },
        connection = { EveConnection("https://host.ts.net:8443", "tok") },
    )

    private inner class FakeRepo(
        private val order: MutableList<String>,
        var approveOutcome: ApproveOutcome = ApproveOutcome.Sent,
        var denyOutcome: DenyOutcome = DenyOutcome.Denied,
    ) : ApprovalRepository(fakeApi()) {
        val approveIds = mutableListOf<String>()
        val denyIds = mutableListOf<String>()
        override suspend fun approve(id: String): ApproveOutcome {
            order += "approve"; approveIds += id; return approveOutcome
        }
        override suspend fun deny(id: String): DenyOutcome {
            order += "deny"; denyIds += id; return denyOutcome
        }
    }

    private class FakeWriter : SnapshotWriter {
        val approvals = mutableListOf<ByteArray>()
        val status = mutableListOf<ByteArray>()
        val voiceDoor = mutableListOf<ByteArray>()
        override suspend fun writeApprovals(bytes: ByteArray) { approvals += bytes }
        override suspend fun writeStatus(bytes: ByteArray) { status += bytes }
        override suspend fun writeVoiceDoor(bytes: ByteArray) { voiceDoor += bytes }
    }

    private class FakeSender : ResultSender {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val talkReplies = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun sendResult(nodeId: String, bytes: ByteArray) { sent += nodeId to bytes }
        override suspend fun sendTalkReply(nodeId: String, bytes: ByteArray) { talkReplies += nodeId to bytes }
    }

    /** Default talk brain used by the approve/deny tests that never exercise the talk leg. */
    private val defaultAsk: suspend (String) -> ApiResult<String> = { ApiResult.Ok("ok") }

    private fun fixtureApprovals(): List<Approval> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("approvals_sample.json"))
            .bufferedReader().use { it.readText() }
        return ApiClient.DEFAULT_JSON.decodeFromString<ApprovalsResponse>(text).approvals
    }

    private fun okPending(list: List<Approval>): suspend () -> ApiResult<List<Approval>> = { ApiResult.Ok(list) }
    private fun okStatus(): suspend () -> ApiResult<SystemStatus> = { ApiResult.Ok(SystemStatus(desktopOnline = true, pendingApprovals = 2)) }

    @Test
    fun approve_happy_path_sends_APPROVED_and_refreshes_fresh() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order, approveOutcome = ApproveOutcome.Sent)
        val writer = FakeWriter()
        val sender = FakeSender()
        val fixture = fixtureApprovals()
        val fetchPending: suspend () -> ApiResult<List<Approval>> = { order += "fetchPending"; ApiResult.Ok(fixture) }
        val fetchStatus: suspend () -> ApiResult<SystemStatus> = { order += "fetchStatus"; ApiResult.Ok(SystemStatus(desktopOnline = true)) }
        val bridge = WearBridge(repo, fetchPending, fetchStatus, writer, sender, askEve = defaultAsk, clock = { 12345L })

        bridge.handleAction(WearLink.PATH_ACTION_APPROVE, WearAction("req1", "abc").toBytes(), "watchNode")

        // Result sent to the SOURCE node, mapped APPROVED, correlated back.
        assertEquals(1, sender.sent.size)
        assertEquals("watchNode", sender.sent[0].first)
        val result = WearActionResult.fromBytes(sender.sent[0].second)
        assertEquals(Outcome.APPROVED, result.outcome)
        assertEquals("req1", result.requestId)
        assertEquals("abc", result.approvalId)
        assertEquals("abc", repo.approveIds.single())

        // Both snapshots refreshed with serverReachable=true; the list came from the fresh fetch.
        val approvalsSnap = ApprovalsSnapshot.fromBytes(writer.approvals.last())
        assertTrue(approvalsSnap.serverReachable)
        assertEquals(fixture, approvalsSnap.approvals)
        assertEquals(12345L, approvalsSnap.fetchedAtEpochMs)
        assertTrue(StatusSnapshot.fromBytes(writer.status.last()).serverReachable)

        // The refresh fetch happened AFTER the action (fresh GET, not a cached list).
        assertEquals(listOf("approve", "fetchPending", "fetchStatus"), order)
    }

    @Test
    fun deny_sends_DENIED() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order, denyOutcome = DenyOutcome.Denied)
        val writer = FakeWriter()
        val sender = FakeSender()
        val bridge = WearBridge(repo, okPending(emptyList()), okStatus(), writer, sender, askEve = defaultAsk, clock = { 1L })

        bridge.handleAction(WearLink.PATH_ACTION_DENY, WearAction("r", "xyz").toBytes(), "node")

        val result = WearActionResult.fromBytes(sender.sent.single().second)
        assertEquals(Outcome.DENIED, result.outcome)
        assertEquals("xyz", repo.denyIds.single())
        assertEquals("deny", order.first())
        // Snapshots still refreshed after a deny.
        assertEquals(1, writer.approvals.size)
        assertEquals(1, writer.status.size)
    }

    @Test
    fun already_resolved_maps_and_still_refreshes_so_item_vanishes() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order, approveOutcome = ApproveOutcome.AlreadyResolved)
        val writer = FakeWriter()
        val sender = FakeSender()
        // The FRESH snapshot after a 409 no longer contains the resolved row.
        val bridge = WearBridge(repo, okPending(emptyList()), okStatus(), writer, sender, askEve = defaultAsk, clock = { 1L })

        bridge.handleAction(WearLink.PATH_ACTION_APPROVE, WearAction("r", "gone").toBytes(), "node")

        assertEquals(Outcome.ALREADY_RESOLVED, WearActionResult.fromBytes(sender.sent.single().second).outcome)
        val snap = ApprovalsSnapshot.fromBytes(writer.approvals.single())
        assertTrue(snap.serverReachable)
        assertTrue(snap.approvals.isEmpty(), "the resolved item must be gone from the watch's next snapshot")
    }

    @Test
    fun repo_network_failure_maps_SERVER_UNREACHABLE_with_real_detail() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order, approveOutcome = ApproveOutcome.Failed(ApiError.Offline("connection refused")))
        val writer = FakeWriter()
        val sender = FakeSender()
        val bridge = WearBridge(repo, okPending(emptyList()), okStatus(), writer, sender, askEve = defaultAsk, clock = { 1L })

        bridge.handleAction(WearLink.PATH_ACTION_APPROVE, WearAction("r", "abc").toBytes(), "node")

        val result = WearActionResult.fromBytes(sender.sent.single().second)
        assertEquals(Outcome.SERVER_UNREACHABLE, result.outcome)
        assertEquals("connection refused", result.detail, "the real network detail must surface, not a generic message")
    }

    @Test
    fun refresh_with_failing_fetch_writes_leg_down_snapshot() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order)
        val writer = FakeWriter()
        val sender = FakeSender()
        val failingPending: suspend () -> ApiResult<List<Approval>> = { ApiResult.Err(ApiError.Offline("boom")) }
        val bridge = WearBridge(repo, failingPending, okStatus(), writer, sender, askEve = defaultAsk, clock = { 99L })

        bridge.handleAction(WearLink.PATH_ACTION_REFRESH, ByteArray(0), "node")

        // Both snapshots still written; the approvals one carries the honest leg-down signal.
        assertEquals(1, writer.approvals.size)
        assertEquals(1, writer.status.size)
        val snap = ApprovalsSnapshot.fromBytes(writer.approvals.single())
        assertEquals(false, snap.serverReachable)
        assertEquals("cannot reach $ASSISTANT_NAME: boom", snap.errorDetail)
        assertTrue(snap.approvals.isEmpty())
        assertEquals(99L, snap.fetchedAtEpochMs)
        // A refresh never touches the repo approve/deny path.
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun refresh_writes_the_live_voice_door_with_configured_url_and_token() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order)
        val writer = FakeWriter()
        val sender = FakeSender()
        val bridge = WearBridge(
            repo, okPending(emptyList()), okStatus(), writer, sender,
            askEve = defaultAsk,
            fetchVoiceDoor = { app.eve.data.wear.VoiceDoorConfig("wss://door/v1/watch/voice", "tok-9") },
            clock = { 1L },
        )

        bridge.handleAction(WearLink.PATH_ACTION_REFRESH, ByteArray(0), "node")

        val door = app.eve.data.wear.VoiceDoorConfig.fromBytes(writer.voiceDoor.single())
        assertEquals("wss://door/v1/watch/voice", door.wsUrl)
        assertEquals("tok-9", door.token)
        assertTrue(door.isConfigured)
    }

    @Test
    fun refresh_writes_blank_voice_door_as_the_honest_not_configured_signal() = runTest {
        val writer = FakeWriter()
        val bridge = WearBridge(
            FakeRepo(mutableListOf()), okPending(emptyList()), okStatus(), writer, FakeSender(),
            askEve = defaultAsk,
            // No URL set yet, but the phone always has a token: the door is written blank, not skipped.
            fetchVoiceDoor = { app.eve.data.wear.VoiceDoorConfig("", "tok") },
            clock = { 1L },
        )

        bridge.handleAction(WearLink.PATH_ACTION_REFRESH, ByteArray(0), "node")

        val door = app.eve.data.wear.VoiceDoorConfig.fromBytes(writer.voiceDoor.single())
        assertEquals("", door.wsUrl)
        assertTrue(!door.isConfigured, "a blank URL is the honest not-configured signal, still written")
    }

    @Test
    fun refresh_without_a_wired_voice_door_writes_no_door() = runTest {
        // The default (older callers / unit tests): fetchVoiceDoor is null → the door is never written
        // (never a fake/blank overwrite), while approvals + status still refresh as before.
        val writer = FakeWriter()
        val bridge = WearBridge(
            FakeRepo(mutableListOf()), okPending(emptyList()), okStatus(), writer, FakeSender(),
            askEve = defaultAsk, clock = { 1L },
        )

        bridge.handleAction(WearLink.PATH_ACTION_REFRESH, ByteArray(0), "node")

        assertEquals(1, writer.approvals.size)
        assertEquals(1, writer.status.size)
        assertTrue(writer.voiceDoor.isEmpty(), "an unwired door must not be written")
    }

    @Test
    fun malformed_payload_with_recoverable_id_sends_ERROR_no_crash() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order)
        val writer = FakeWriter()
        val sender = FakeSender()
        val bridge = WearBridge(repo, okPending(emptyList()), okStatus(), writer, sender, askEve = defaultAsk, clock = { 1L })

        // Valid JSON, but missing the required approvalId → WearAction decode fails; requestId is
        // still recoverable, so an honest ERROR result must go back (never silent, never a crash).
        val malformed = """{"requestId":"req9"}""".toByteArray()
        bridge.handleAction(WearLink.PATH_ACTION_APPROVE, malformed, "node")

        val result = WearActionResult.fromBytes(sender.sent.single().second)
        assertEquals(Outcome.ERROR, result.outcome)
        assertEquals("req9", result.requestId)
        assertEquals("malformed action payload", result.detail)
        // No approve attempted, no snapshot written on a malformed action.
        assertTrue(repo.approveIds.isEmpty())
        assertTrue(writer.approvals.isEmpty())
    }

    @Test
    fun fully_garbage_payload_is_logged_not_answered_no_crash() = runTest {
        val order = mutableListOf<String>()
        val repo = FakeRepo(order)
        val writer = FakeWriter()
        val sender = FakeSender()
        val bridge = WearBridge(repo, okPending(emptyList()), okStatus(), writer, sender, askEve = defaultAsk, clock = { 1L })

        // No recoverable requestId → nothing to answer; must log loudly and not crash.
        bridge.handleAction(WearLink.PATH_ACTION_DENY, byteArrayOf(0x00, 0x01, 0x02, 0x03), "node")

        assertTrue(sender.sent.isEmpty(), "no correlation id → no result to send")
        assertTrue(repo.denyIds.isEmpty())
        assertTrue(writer.approvals.isEmpty())
    }

    // ---- talk leg (push-to-talk -> Atlas brain) -------------------------------

    /** Build a bridge whose talk brain returns [ask], with unused approve/deny fetch lambdas. */
    private fun talkBridge(sender: FakeSender, ask: suspend (String) -> ApiResult<String>): WearBridge {
        val repo = FakeRepo(mutableListOf())
        return WearBridge(repo, okPending(emptyList()), okStatus(), FakeWriter(), sender, askEve = ask, clock = { 1L })
    }

    @Test
    fun talk_happy_path_sends_OK_reply_on_the_talk_path_not_sendResult() = runTest {
        val sender = FakeSender()
        var asked: String? = null
        val bridge = talkBridge(sender) { text -> asked = text; ApiResult.Ok("You have a 3pm with Jamie.") }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, app.eve.data.wear.TalkRequest("t1", "what's on today?").toBytes(), "watchNode")

        assertEquals("what's on today?", asked)
        // Reply went out on the TALK reply channel, correlated, OK — and NEVER via sendResult.
        assertTrue(sender.sent.isEmpty(), "talk reply must NOT use the approvals sendResult path")
        assertEquals("watchNode", sender.talkReplies.single().first)
        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(Outcome.OK, reply.outcome)
        assertEquals("t1", reply.requestId)
        assertEquals("You have a 3pm with Jamie.", reply.reply)
    }

    @Test
    fun talk_offline_maps_SERVER_UNREACHABLE_with_real_detail_and_null_reply() = runTest {
        val sender = FakeSender()
        val bridge = talkBridge(sender) { ApiResult.Err(ApiError.Offline("connection refused")) }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, app.eve.data.wear.TalkRequest("t2", "hi").toBytes(), "node")

        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(Outcome.SERVER_UNREACHABLE, reply.outcome)
        assertEquals("connection refused", reply.detail)
        assertEquals(null, reply.reply)
    }

    @Test
    fun talk_unauthorized_maps_UNAUTHORIZED() = runTest {
        val sender = FakeSender()
        val bridge = talkBridge(sender) { ApiResult.Err(ApiError.Unauthorized) }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, app.eve.data.wear.TalkRequest("t3", "hi").toBytes(), "node")

        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(Outcome.UNAUTHORIZED, reply.outcome)
        assertEquals("unauthorized (401) — reconnect the phone", reply.detail)
    }

    @Test
    fun talk_http_error_maps_ERROR_with_status_detail() = runTest {
        val sender = FakeSender()
        val bridge = talkBridge(sender) { ApiResult.Err(ApiError.Http(502, "bad gateway")) }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, app.eve.data.wear.TalkRequest("t4", "hi").toBytes(), "node")

        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(Outcome.ERROR, reply.outcome)
        assertEquals("HTTP 502: bad gateway", reply.detail)
    }

    @Test
    fun talk_long_reply_is_truncated_with_a_visible_marker() = runTest {
        val sender = FakeSender()
        val huge = "x".repeat(WearBridge.MAX_REPLY_CHARS + 500)
        val bridge = talkBridge(sender) { ApiResult.Ok(huge) }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, app.eve.data.wear.TalkRequest("t5", "hi").toBytes(), "node")

        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(Outcome.OK, reply.outcome)
        assertEquals(WearBridge.MAX_REPLY_CHARS + "… [truncated]".length, reply.reply!!.length)
        assertTrue(reply.reply!!.endsWith("… [truncated]"), "truncation must be visible, never silent")
    }

    @Test
    fun talk_reply_under_the_cap_is_untouched() = runTest {
        val sender = FakeSender()
        val exact = "y".repeat(WearBridge.MAX_REPLY_CHARS)
        val bridge = talkBridge(sender) { ApiResult.Ok(exact) }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, app.eve.data.wear.TalkRequest("t6", "hi").toBytes(), "node")

        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(exact, reply.reply, "a reply at exactly the cap must not gain a marker")
    }

    @Test
    fun talk_malformed_payload_with_recoverable_id_sends_ERROR_no_crash() = runTest {
        val sender = FakeSender()
        var asked = false
        val bridge = talkBridge(sender) { asked = true; ApiResult.Ok("unused") }

        // Valid JSON but missing the required text → TalkRequest decode fails; requestId recoverable.
        bridge.handleAction(WearLink.PATH_ACTION_TALK, """{"requestId":"t7"}""".toByteArray(), "node")

        val reply = app.eve.data.wear.TalkReply.fromBytes(sender.talkReplies.single().second)
        assertEquals(Outcome.ERROR, reply.outcome)
        assertEquals("t7", reply.requestId)
        assertEquals("malformed talk payload", reply.detail)
        assertTrue(!asked, "the brain must not be called on a malformed payload")
    }

    @Test
    fun talk_fully_garbage_payload_is_logged_not_answered_no_crash() = runTest {
        val sender = FakeSender()
        val bridge = talkBridge(sender) { ApiResult.Ok("unused") }

        bridge.handleAction(WearLink.PATH_ACTION_TALK, byteArrayOf(0x00, 0x01, 0x02, 0x03), "node")

        assertTrue(sender.talkReplies.isEmpty(), "no correlation id → no talk reply to send")
        assertTrue(sender.sent.isEmpty())
    }

    // ---- Health v2: watch HR alert -> phone -> POST /v1/health/event -------------------------

    private fun healthBridge(
        post: suspend (app.eve.data.wear.HealthAlert) -> ApiResult<Unit>,
    ): WearBridge {
        val order = mutableListOf<String>()
        return WearBridge(
            FakeRepo(order), okPending(emptyList()), okStatus(), FakeWriter(), FakeSender(),
            askEve = defaultAsk, postHealthEvent = post, clock = { 1L },
        )
    }

    @Test
    fun health_alert_is_posted_to_the_sidecar() = runTest {
        val posted = mutableListOf<app.eve.data.wear.HealthAlert>()
        val bridge = healthBridge { alert -> posted += alert; ApiResult.Ok(Unit) }
        val alert = app.eve.data.wear.HealthAlert(
            requestId = "hr-9", type = "hr_high", bpm = 145, thresholdBpm = 120,
            observedAtEpochMs = 1_780_000_000_000,
        )

        bridge.handleAction(WearLink.PATH_ACTION_HEALTH_EVENT, alert.toBytes(), "watchNode")

        assertEquals(listOf(alert), posted)
    }

    @Test
    fun health_alert_malformed_payload_never_posts_never_crashes() = runTest {
        var posts = 0
        val bridge = healthBridge { posts++; ApiResult.Ok(Unit) }

        bridge.handleAction(WearLink.PATH_ACTION_HEALTH_EVENT, byteArrayOf(0x00, 0x7F), "node")

        assertEquals(0, posts)
    }

    @Test
    fun health_alert_post_failure_never_crashes_the_bridge() = runTest {
        // The failure is logged loudly inside the bridge; the Data-Layer listener must survive.
        val bridge = healthBridge { ApiResult.Err(ApiError.Offline("no route")) }
        val alert = app.eve.data.wear.HealthAlert(
            requestId = "hr-10", type = "hr_high", bpm = 150, thresholdBpm = 120,
            observedAtEpochMs = 1_780_000_000_000,
        )

        bridge.handleAction(WearLink.PATH_ACTION_HEALTH_EVENT, alert.toBytes(), "node")
        // reaching here without a throw IS the assertion
    }
}
