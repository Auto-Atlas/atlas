package app.eve.data

import app.eve.data.models.Health
import app.eve.data.models.SystemStatus

open class StatusRepository(private val api: ApiClient) {

    open suspend fun health(): ApiResult<Health> = api.health()

    /** Real engine/cost/session telemetry proxied from OpenJarvis. */
    open suspend fun status(): ApiResult<SystemStatus> = api.getStatus()

    open suspend fun remoteApprovalEnabled(): ApiResult<Boolean> =
        api.getSettings().map { it.remoteApprovalEnabled }

    open suspend fun setRemoteApproval(enabled: Boolean): ApiResult<Boolean> =
        api.setRemoteApproval(enabled).map { it.remoteApprovalEnabled }

    open suspend fun setThinking(enabled: Boolean): ApiResult<Boolean> =
        api.setThinking(enabled).map { it.thinkingEnabled }

    open suspend fun setBargeIn(enabled: Boolean): ApiResult<Boolean> =
        api.setBargeIn(enabled).map { it.bargeInEnabled }
}
