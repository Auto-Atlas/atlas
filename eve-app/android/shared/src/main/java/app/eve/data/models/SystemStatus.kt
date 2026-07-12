package app.eve.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /v1/status — real engine/cost/session status proxied from OpenJarvis.
 *
 * [desktopOnline] is false when the brain is down; the telemetry/budget then arrive as their
 * empty defaults and the Status screen shows a quiet "Desktop offline" note for that section
 * (the local approval controls stay live regardless). Every field is optional with a default,
 * and DEFAULT_JSON ignores the many extra telemetry keys the engine may add.
 */
@Serializable
data class SystemStatus(
    @SerialName("desktop_online") val desktopOnline: Boolean = false,
    @SerialName("pending_approvals") val pendingApprovals: Int = 0,
    val telemetry: Telemetry = Telemetry(),
    val budget: Budget? = null,
)

/**
 * Engine cost/throughput telemetry. The brain emits a wide set of energy/latency keys; we model
 * the ones the UI shows and let ignoreUnknownKeys carry the rest. All optional — a fresh session
 * reports zeros, which render honestly (no fabricated numbers).
 */
@Serializable
data class Telemetry(
    @SerialName("total_tokens") val totalTokens: Long = 0L,
    @SerialName("total_cost") val totalCost: Double = 0.0,
    @SerialName("total_latency") val totalLatency: Double = 0.0,
    @SerialName("total_requests") val totalRequests: Long = 0L,
    @SerialName("avg_throughput_tok_per_sec") val avgThroughput: Double = 0.0,
    @SerialName("avg_gpu_utilization_pct") val avgGpuUtilization: Double = 0.0,
    @SerialName("avg_mean_itl_ms") val avgMeanItlMs: Double = 0.0,
    @SerialName("avg_p95_itl_ms") val avgP95ItlMs: Double = 0.0,
    @SerialName("total_energy_joules") val totalEnergyJoules: Double = 0.0,
    @SerialName("avg_energy_per_output_token_joules") val avgEnergyPerToken: Double = 0.0,
) {
    /** Avg latency per request in seconds — total_latency is summed seconds across requests. */
    val avgLatencyS: Double get() = if (totalRequests > 0) totalLatency / totalRequests else 0.0
}

@Serializable
data class Budget(
    val limits: BudgetLimits = BudgetLimits(),
    val usage: BudgetUsage = BudgetUsage(),
)

@Serializable
data class BudgetLimits(
    @SerialName("max_tokens_per_day") val maxTokensPerDay: Long? = null,
    @SerialName("max_requests_per_hour") val maxRequestsPerHour: Long? = null,
)

@Serializable
data class BudgetUsage(
    @SerialName("tokens_today") val tokensToday: Long = 0L,
    @SerialName("requests_this_hour") val requestsThisHour: Long = 0L,
)
