package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * One approval ROW, matching approval_store._row_to_dict EXACTLY (see approval_api.py).
 *
 * `args` is deliberately NOT a discriminated sealed class at the serialization boundary,
 * because the backend's `args` schema differs by `tool` but is not self-tagged. Instead the
 * raw `args` JsonObject is kept verbatim and decoded on demand via [invoiceArgs] / [channelArgs]
 * keyed off [tool]. This keeps round-trips lossless (no field is dropped) and makes the
 * tool->args mapping explicit and testable.
 *
 * CRITICAL (design contract): the displayed amount is computed from `args` via [totalDollars],
 * NEVER from `summary`.
 */
@Serializable
data class Approval(
    val id: String,
    val tool: String,
    val args: JsonObject,
    val requester: String? = null,
    @SerialName("requester_tier") val requesterTier: String,
    @SerialName("risk_level") val riskLevel: String,
    val summary: String,
    val status: String,
    @SerialName("effective_status") val effectiveStatus: String? = null,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("ttl_s") val ttlSeconds: Int,
    @SerialName("expires_at") val expiresAt: Double,
    @SerialName("seconds_left") val secondsLeft: Double,
    @SerialName("decided_at") val decidedAt: Double? = null,
    val result: JsonObject? = null,
) {
    val isInvoice: Boolean get() = tool == "create_invoice"
    val isChannel: Boolean get() = tool == "send_to_channel"

    /** True when the backend's computed effective_status says this row has expired. */
    val isExpired: Boolean get() = (effectiveStatus ?: status) == "expired"

    /** Decoded invoice args, or null if this isn't a create_invoice row. */
    val invoiceArgs: InvoiceArgs?
        get() = if (isInvoice) InvoiceArgs.from(args) else null

    /** Decoded channel args, or null if this isn't a send_to_channel row. */
    val channelArgs: ChannelArgs?
        get() = if (isChannel) ChannelArgs.from(args) else null

    /**
     * The dollar total, computed from frozen args. Invoice => sum(quantity * rate) where rate
     * is in DOLLARS (per the backend contract). Channel messages have no amount => null.
     */
    val totalDollars: Double?
        get() = invoiceArgs?.let { inv -> inv.lineItems.sumOf { it.quantity * it.rate } }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.intValue(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.doubleValue(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

/** create_invoice args: { customer:{name}, line_items:[{description, quantity, rate}] , ... } */
data class InvoiceArgs(
    val customerName: String,
    val lineItems: List<LineItem>,
) {
    companion object {
        fun from(args: JsonObject): InvoiceArgs {
            val customer = (args["customer"] as? JsonObject)?.string("name") ?: "Unknown"
            val items = (args["line_items"]?.jsonArray ?: emptyList()).mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                LineItem(
                    description = obj.string("description") ?: "",
                    quantity = obj.intValue("quantity") ?: 0,
                    rate = obj.doubleValue("rate") ?: 0.0,
                )
            }
            return InvoiceArgs(customerName = customer, lineItems = items)
        }
    }
}

data class LineItem(
    val description: String,
    val quantity: Int,
    val rate: Double,
) {
    val amount: Double get() = quantity * rate
}

/** send_to_channel args: { channel, message } */
data class ChannelArgs(
    val channel: String,
    val message: String,
) {
    companion object {
        fun from(args: JsonObject): ChannelArgs = ChannelArgs(
            channel = args.string("channel") ?: "",
            message = args.string("message") ?: "",
        )
    }
}

@Serializable
data class ApprovalsResponse(val approvals: List<Approval>)
