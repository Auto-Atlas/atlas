package app.eve.data

import app.eve.data.models.ActivityDigest
import app.eve.data.models.ActivityFeed
import app.eve.data.models.ConversationDetailResponse

open class ActivityRepository(private val api: ApiClient) {
    /** Legacy per-day digest (kept for compatibility / tests). */
    open suspend fun day(day: String = "today"): ApiResult<ActivityDigest> = api.activity(day)

    /** Canonical conversation timeline proxied from OpenJarvis — the primary Activity feed. */
    open suspend fun feed(limit: Int = 25): ApiResult<ActivityFeed> = api.getActivityFeed(limit)

    /** One conversation's full message + delegation/tool timeline. */
    open suspend fun detail(convId: String): ApiResult<ConversationDetailResponse> =
        api.getActivityDetail(convId)
}
