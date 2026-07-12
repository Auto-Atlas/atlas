package app.eve.data

import app.eve.data.models.ClearResult
import app.eve.data.models.FeedDto
import app.eve.data.models.FeedMode
import app.eve.data.models.FeedResult
import app.eve.data.models.SkillDto

/**
 * Thin typed wrapper over the skills + feed endpoints. Methods are `open` so the ViewModel
 * test can substitute a FakeRepo (mirrors ApprovalRepository / StatusRepository).
 */
open class SkillsRepository(private val api: ApiClient) {

    open suspend fun list(): ApiResult<List<SkillDto>> =
        api.skills().map { it.skills }

    open suspend fun feed(tool: String, mode: FeedMode): ApiResult<FeedResult> =
        api.feedSkill(tool, mode)

    open suspend fun pendingFeeds(): ApiResult<List<FeedDto>> =
        api.pendingFeeds().map { it.pending }

    open suspend fun unprime(tool: String): ApiResult<ClearResult> =
        api.unprime(tool)
}
