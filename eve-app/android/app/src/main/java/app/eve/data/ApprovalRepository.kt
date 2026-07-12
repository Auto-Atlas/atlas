package app.eve.data

import app.eve.data.models.AgentTasksResponse
import app.eve.data.models.Approval

/** Domain outcomes for an approve/deny attempt — the UI layer maps these to card states. */
sealed interface ApproveOutcome {
    /** release() ran and returned ok:true — the tool actually fired. */
    data object Sent : ApproveOutcome

    /** Approved, but release() returned ok:false — the tool could not complete. NOT a success. */
    data object SendFailed : ApproveOutcome

    /** 409 — already consumed/denied/expired elsewhere, or tier/risk mismatch. */
    data object AlreadyResolved : ApproveOutcome

    /** Any transport/other failure (offline, 401, decode, ...). */
    data class Failed(val error: ApiError) : ApproveOutcome
}

sealed interface DenyOutcome {
    data object Denied : DenyOutcome
    data object AlreadyResolved : DenyOutcome
    data class Failed(val error: ApiError) : DenyOutcome
}

/** Owner cancels a running delegated task. HONEST states: a running agent is only ever
 *  cancel-REQUESTED until the stop is observed at its next check-in. */
sealed interface CancelOutcome {
    /** Cooperative: the agent gets the stop order at its next check-in. */
    data class Requested(val detail: String) : CancelOutcome

    /** Immediate: the task had never started; nothing was running. */
    data class Cancelled(val detail: String) : CancelOutcome

    /** 409 — already finished/failed/cancelled, or its result is landing right now. */
    data object NotCancellable : CancelOutcome

    data class Failed(val error: ApiError) : CancelOutcome
}

/** Owner steers a running delegated task with new instructions. */
sealed interface RedirectOutcome {
    /** Staged — lands at the agent's next check-in (the feed shows it landing). */
    data class Staged(val detail: String) : RedirectOutcome

    /** 409 — the task can't take a steer (finished, cancelling, or no talk-back channel). */
    data object NotSteerable : RedirectOutcome

    data class Failed(val error: ApiError) : RedirectOutcome
}

open class ApprovalRepository(private val api: ApiClient) {

    open suspend fun pending(): ApiResult<List<Approval>> =
        api.pendingApprovals().map { it.approvals }

    open suspend fun approve(id: String): ApproveOutcome = when (val r = api.approve(id)) {
        is ApiResult.Ok ->
            // Honest mapping: ok:true => Sent; ok:false => SendFailed (never a false "Sent").
            if (r.value.ok) ApproveOutcome.Sent else ApproveOutcome.SendFailed
        is ApiResult.Err -> when (r.error) {
            is ApiError.AlreadyResolved, is ApiError.NotFound -> ApproveOutcome.AlreadyResolved
            else -> ApproveOutcome.Failed(r.error)
        }
    }

    open suspend fun deny(id: String): DenyOutcome = when (val r = api.deny(id)) {
        is ApiResult.Ok ->
            if (r.value.denied) DenyOutcome.Denied else DenyOutcome.AlreadyResolved
        is ApiResult.Err -> when (r.error) {
            is ApiError.AlreadyResolved, is ApiError.NotFound -> DenyOutcome.AlreadyResolved
            else -> DenyOutcome.Failed(r.error)
        }
    }

    open suspend fun agentTasks(): ApiResult<AgentTasksResponse> = api.agentTasks()

    open suspend fun cancelTask(id: String): CancelOutcome =
        when (val r = api.cancelAgentTask(id)) {
            is ApiResult.Ok ->
                if (r.value.status == "cancelled") CancelOutcome.Cancelled(r.value.detail)
                else CancelOutcome.Requested(r.value.detail)
            is ApiResult.Err -> when (r.error) {
                is ApiError.AlreadyResolved, is ApiError.NotFound -> CancelOutcome.NotCancellable
                else -> CancelOutcome.Failed(r.error)
            }
        }

    open suspend fun redirectTask(id: String, instructions: String): RedirectOutcome =
        when (val r = api.redirectAgentTask(id, instructions)) {
            is ApiResult.Ok -> RedirectOutcome.Staged(r.value.detail)
            is ApiResult.Err -> when (r.error) {
                is ApiError.AlreadyResolved, is ApiError.NotFound -> RedirectOutcome.NotSteerable
                else -> RedirectOutcome.Failed(r.error)
            }
        }
}
