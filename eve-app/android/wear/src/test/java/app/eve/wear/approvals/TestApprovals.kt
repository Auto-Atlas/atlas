package app.eve.wear.approvals

import app.eve.data.models.Approval
import app.eve.data.wear.ApprovalsSnapshot
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure Kotlin approval builders for the wear unit tests — no fixture files, no GMS. */
object TestApprovals {

    /** A create_invoice approval whose total is quantity*rate (matches the backend/args contract). */
    fun invoice(
        id: String,
        customer: String = "The Browns",
        quantity: Int = 2,
        rate: Double = 600.0,
        requester: String = "Jamie",
        tier: String = "known",
        risk: String = "high",
        expiresAt: Double = 1_750_014_400.0,
        secondsLeft: Double = 14_000.0,
    ): Approval = Approval(
        id = id,
        tool = "create_invoice",
        args = buildJsonObject {
            put("customer", buildJsonObject { put("name", customer) })
            put(
                "line_items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("description", "Deep clean")
                            put("quantity", quantity)
                            put("rate", rate)
                        },
                    )
                },
            )
        },
        requester = requester,
        requesterTier = tier,
        riskLevel = risk,
        summary = "$requester -> invoice $customer",
        status = "pending",
        effectiveStatus = "pending",
        createdAt = 1_750_000_000.0,
        ttlSeconds = 14_400,
        expiresAt = expiresAt,
        secondsLeft = secondsLeft,
    )

    /** A send_to_channel approval (no dollar amount). */
    fun channel(
        id: String,
        channel: String = "telegram",
        requester: String = "Jamie",
        tier: String = "known",
        risk: String = "low",
    ): Approval = Approval(
        id = id,
        tool = "send_to_channel",
        args = buildJsonObject {
            put("channel", channel)
            put("message", "Reminder: the invoice is ready.")
        },
        requester = requester,
        requesterTier = tier,
        riskLevel = risk,
        summary = "$requester -> message to $channel",
        status = "pending",
        effectiveStatus = "pending",
        createdAt = 1_750_000_000.0,
        ttlSeconds = 14_400,
        expiresAt = 1_750_014_500.0,
        secondsLeft = 14_100.0,
    )

    fun pendingSnapshot(approvals: List<Approval>, atMs: Long = 1_000L): ApprovalsSnapshot =
        ApprovalsSnapshot(approvals = approvals, fetchedAtEpochMs = atMs, serverReachable = true)

    fun serverDownSnapshot(detail: String, atMs: Long = 2_000L): ApprovalsSnapshot =
        ApprovalsSnapshot(
            approvals = emptyList(),
            fetchedAtEpochMs = atMs,
            serverReachable = false,
            errorDetail = detail,
        )
}
