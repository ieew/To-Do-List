package com.todo.android.core.sync

import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.network.ApiResult
import com.todo.android.core.network.ServerConfig
import com.todo.android.core.network.api.AuthApi
import com.todo.android.core.network.apiCall
import com.todo.android.core.network.session.SessionManager
import com.todo.android.core.network.session.SessionTokens
import com.todo.android.core.network.wire.LoginRequest
import com.todo.android.core.network.wire.LoginResponse
import com.todo.android.core.network.wire.LogoutRequest
import com.todo.android.core.network.wire.RegisterRequest
import com.todo.android.core.network.wire.RegisterResponse
import com.todo.android.core.network.wire.UserOut
import com.todo.android.core.network.wire.WireTokens
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 账户生命周期：登录/注册/登出。
 * 不同账户登录前整库备份并清空 op_log 与同步 meta（保留 node_id）；同账户再登录不清库。
 */
@Singleton
class AccountManager @Inject constructor(
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val repository: OpRepository,
    private val engine: SyncEngine,
    private val settingsStore: SettingsStore,
    private val serverConfig: ServerConfig,
    private val json: Json,
) {
    suspend fun login(username: String, password: String, serverUrl: String): ApiResult<Unit> {
        serverConfig.update(serverUrl)
        val deviceId = repository.deviceId()
        return when (val result = apiCall(json) { authApi.login(LoginRequest(username, password, deviceId)) }) {
            is ApiResult.Success -> {
                val body = result.value
                bindSession(body.user, body.tokens, deviceId, body.workspaces.firstOrNull()?.workspaceId)
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> result
        }
    }

    suspend fun register(
        username: String,
        password: String,
        displayName: String?,
        serverUrl: String,
    ): ApiResult<Unit> {
        serverConfig.update(serverUrl)
        val deviceId = repository.deviceId()
        val request = RegisterRequest(
            username = username,
            password = password,
            displayName = displayName?.takeIf { it.isNotBlank() },
            deviceId = deviceId,
        )
        return when (val result = apiCall(json) { authApi.register(request) }) {
            is ApiResult.Success -> {
                val body: RegisterResponse = result.value
                bindSession(body.user, body.tokens, deviceId, body.defaultWorkspaceId)
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> result
        }
    }

    suspend fun logout() {
        val session = sessionManager.load()
        if (session != null) {
            apiCall(json) { authApi.logout(LogoutRequest(session.refreshToken)) }
        }
        sessionManager.clear()
        // 保留本地数据与 sync_account，仅清除登录态。
    }

    /** 启动时恢复登录态：有令牌即视为已登录，同步由 SyncEngine 负责。 */
    suspend fun restore(): Boolean = sessionManager.load() != null

    private suspend fun bindSession(
        user: UserOut,
        tokens: WireTokens,
        deviceId: String,
        activeWorkspaceId: String?,
    ) {
        val accountKey = "${serverConfig.baseUrl}|${user.userId}"
        if (repository.syncAccount() != accountKey) {
            repository.resetForAccount()
        }
        // 地址必须落库：SyncEngine.runOnce 会用设置里的地址覆盖 ServerConfig，
        // 不保存就会在登录后立刻退回默认的 127.0.0.1。
        settingsStore.update { it.copy(serverUrl = serverConfig.baseUrl) }
        sessionManager.save(
            SessionTokens(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessExpiresAt = tokens.accessExpiresAt,
                refreshExpiresAt = tokens.refreshExpiresAt,
                userId = user.userId,
                username = user.username,
                displayName = user.displayName,
                serverUrl = serverConfig.baseUrl,
                deviceId = deviceId,
            ),
        )
        repository.setSyncAccount(accountKey)
        repository.setCurrentUserId(user.userId)
        activeWorkspaceId?.let { repository.setActiveWorkspaceId(it) }
        engine.bootstrap()
        engine.runOnce()
    }
}
