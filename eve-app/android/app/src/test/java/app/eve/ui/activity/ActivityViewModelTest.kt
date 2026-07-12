package app.eve.ui.activity

import app.eve.data.ActivityRepository
import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.EveConnection
import app.eve.data.models.ActivityFeed
import app.eve.data.models.ConversationDetail
import app.eve.data.models.ConversationDetailResponse
import app.eve.data.models.ConversationMessage
import app.eve.data.models.ConversationSummary
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private class FakeRepo(
        var feedResult: ApiResult<ActivityFeed> = ApiResult.Ok(LOADED_FEED),
        var detailResult: ApiResult<ConversationDetailResponse> = ApiResult.Ok(LOADED_DETAIL),
    ) : ActivityRepository(
        ApiClient(
            engine = MockEngine { respond("", HttpStatusCode.OK) },
            connection = { EveConnection("", "") },
        ),
    ) {
        override suspend fun feed(limit: Int): ApiResult<ActivityFeed> = feedResult
        override suspend fun detail(convId: String): ApiResult<ConversationDetailResponse> = detailResult
    }

    @Test
    fun load_success_emitsLoaded() {
        val scope = TestScope()
        val vm = ActivityViewModel(FakeRepo(), injectedScope = scope)
        vm.load()
        scope.runCurrent()
        val s = assertIs<ActivityUiState.Loaded>(vm.state.value)
        assertEquals(1, s.conversations.size)
        assertEquals("voice:phone:1", s.conversations.first().id)
        scope.cancel()
    }

    @Test
    fun load_desktopDown_emitsOffline() {
        val scope = TestScope()
        val feed = ActivityFeed(desktopOnline = false, conversations = emptyList())
        val vm = ActivityViewModel(FakeRepo(ApiResult.Ok(feed)), injectedScope = scope)
        vm.load()
        scope.runCurrent()
        assertIs<ActivityUiState.Offline>(vm.state.value)
        scope.cancel()
    }

    @Test
    fun load_onlineButEmpty_emitsEmpty() {
        val scope = TestScope()
        val feed = ActivityFeed(desktopOnline = true, conversations = emptyList())
        val vm = ActivityViewModel(FakeRepo(ApiResult.Ok(feed)), injectedScope = scope)
        vm.load()
        scope.runCurrent()
        assertIs<ActivityUiState.Empty>(vm.state.value)
        scope.cancel()
    }

    @Test
    fun load_offline_emitsError() {
        val scope = TestScope()
        val vm = ActivityViewModel(FakeRepo(ApiResult.Err(ApiError.Offline("no route"))), injectedScope = scope)
        vm.load()
        scope.runCurrent()
        val s = assertIs<ActivityUiState.Error>(vm.state.value)
        assertEquals("Off the tailnet.", s.message)
        scope.cancel()
    }

    @Test
    fun open_success_emitsLoadedDetail_andCloseClears() {
        val scope = TestScope()
        val vm = ActivityViewModel(FakeRepo(), injectedScope = scope)
        vm.open("voice:phone:1")
        scope.runCurrent()
        val d = assertIs<DetailUiState.Loaded>(vm.detail.value)
        assertEquals(2, d.detail.messages.size)
        vm.closeDetail()
        assertEquals(null, vm.detail.value)
        scope.cancel()
    }

    @Test
    fun open_desktopDown_emitsDetailOffline() {
        val scope = TestScope()
        val resp = ConversationDetailResponse(desktopOnline = false, conversation = null)
        val vm = ActivityViewModel(FakeRepo(detailResult = ApiResult.Ok(resp)), injectedScope = scope)
        vm.open("voice:phone:1")
        scope.runCurrent()
        assertIs<DetailUiState.Offline>(vm.detail.value)
        scope.cancel()
    }

    private companion object {
        val LOADED_FEED = ActivityFeed(
            desktopOnline = true,
            source = "openjarvis",
            conversations = listOf(
                ConversationSummary(
                    id = "voice:phone:1",
                    source = "phone-voice",
                    title = "Hey EVE",
                    startedAt = 1_782_301_594_451L,
                    endedAt = 1_782_304_211_755L,
                    msgCount = 51,
                    toolCount = 2,
                    totalTokens = 116_819L,
                ),
            ),
        )

        val LOADED_DETAIL = ConversationDetailResponse(
            desktopOnline = true,
            conversation = ConversationDetail(
                id = "voice:phone:1",
                source = "phone-voice",
                title = "Hey EVE",
                messages = listOf(
                    ConversationMessage(seq = 0, role = "assistant", text = "Hi Jonny", meta = JsonObject(emptyMap())),
                    ConversationMessage(seq = 1, role = "user", text = "Hi", meta = JsonObject(emptyMap())),
                ),
            ),
        )
    }
}
