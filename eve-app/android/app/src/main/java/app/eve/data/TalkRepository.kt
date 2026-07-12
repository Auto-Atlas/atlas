package app.eve.data

/**
 * Domain wrapper over the `POST /v1/ask` call — the phone's half of the watch push-to-talk flow.
 * The watch never talks HTTP; the [app.eve.wearbridge.WearBridge] calls [ask] with the on-watch STT
 * transcript, and this repo returns Atlas's reply text (or the honest [ApiError] for a broken leg).
 *
 * Thin by design (mirrors [ApprovalRepository.pending] mapping `.map { it.approvals }`): the reply
 * envelope is unwrapped to its text; every failure stays an [ApiResult.Err] the bridge maps to a
 * named [app.eve.data.wear.Outcome] — never a fake blank answer.
 */
open class TalkRepository(private val api: ApiClient) {

    open suspend fun ask(text: String): ApiResult<String> =
        api.ask(text).map { it.reply }
}
