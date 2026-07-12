package app.eve.data

import app.eve.data.models.Today
import kotlinx.coroutines.flow.Flow

/**
 * Reads today's ritual (GET /v1/today) and owns the LOCAL, per-date checked-state for its
 * action items. Checking is local-only for now (no server write-back) — it persists in
 * [TodayChecks] (DataStore), keyed by date so yesterday's ticks never bleed into today.
 */
open class TodayRepository(
    private val api: ApiClient,
    private val checks: ActionItemChecks,
) {
    open suspend fun today(): ApiResult<Today> = api.getToday()

    /** The set of checked action-item indices for [date], live across check/uncheck. */
    open fun checkedItems(date: String): Flow<Set<Int>> = checks.checkedFor(date)

    open suspend fun setChecked(date: String, index: Int, checked: Boolean) =
        checks.setChecked(date, index, checked)
}
