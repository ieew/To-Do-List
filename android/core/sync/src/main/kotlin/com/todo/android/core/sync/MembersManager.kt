package com.todo.android.core.sync

import com.todo.android.core.network.ApiResult
import com.todo.android.core.network.api.MemberApi
import com.todo.android.core.network.apiCall
import com.todo.android.core.network.session.SessionManager
import com.todo.android.core.network.wire.InviteRequest
import com.todo.android.core.network.wire.RemoveMemberRequest
import com.todo.android.core.network.wire.WireMember
import com.todo.android.core.network.wire.WireRoleRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 成员管理：走服务端专用接口而不是客户端推 op。
 * 服务端会按 role-at-HLC 校验权限并代为生成 membership / user op，
 * 所以写成功后必须立刻 `runOnce()` 把新 op 拉回本地再刷新 UI。
 */
@Singleton
class MembersManager @Inject constructor(
    private val memberApi: MemberApi,
    private val engine: SyncEngine,
    private val sessionManager: SessionManager,
    private val json: Json,
) {
    fun isSignedIn(): Boolean = sessionManager.cachedToken() != null

    suspend fun load(workspaceId: String): ApiResult<List<WireMember>> =
        when (val result = apiCall(json) { memberApi.members(workspaceId) }) {
            is ApiResult.Success -> ApiResult.Success(result.value.members)
            is ApiResult.Failure -> result
        }

    suspend fun invite(workspaceId: String, account: String, role: String): ApiResult<Unit> =
        mutate {
            apiCall(json) { memberApi.invite(InviteRequest(workspaceId, account.trim(), role)) }
        }

    suspend fun setRole(workspaceId: String, userId: String, role: String): ApiResult<Unit> =
        mutate {
            apiCall(json) { memberApi.setRole(WireRoleRequest(workspaceId, userId, role)) }
        }

    suspend fun remove(workspaceId: String, userId: String): ApiResult<Unit> =
        mutate {
            apiCall(json) { memberApi.remove(RemoveMemberRequest(workspaceId, userId)) }
        }

    /** 写成功后拉一轮同步，把服务端生成的 op 落到本地投影。 */
    private suspend fun mutate(block: suspend () -> ApiResult<*>): ApiResult<Unit> =
        when (val result = block()) {
            is ApiResult.Success -> {
                runCatching { engine.runOnce() }
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> result
        }
}
