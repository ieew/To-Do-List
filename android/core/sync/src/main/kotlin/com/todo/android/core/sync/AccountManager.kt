package com.todo.android.core.sync

import com.todo.android.core.data.repository.ResetResult
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.dsh.DshManager
import com.todo.android.core.network.ApiFailure
import com.todo.android.core.network.ApiResult
import com.todo.android.core.network.BaseUrl
import com.todo.android.core.network.ServerConfig
import com.todo.android.core.network.api.AuthApi
import com.todo.android.core.network.apiCall
import com.todo.android.core.network.session.SessionManager
import com.todo.android.core.network.session.SessionTokens
import com.todo.android.core.network.wire.LoginRequest
import com.todo.android.core.network.wire.LogoutRequest
import com.todo.android.core.network.wire.RegisterRequest
import com.todo.android.core.network.wire.UserOut
import com.todo.android.core.network.wire.WireTokens
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** 登录结果：换账号且有未推送数据时必须先经用户确认，不能静默清库。 */
sealed interface LoginOutcome {
    /** [reset] 非空表示这次登录清空过本地库，界面必须把备份路径展示出来。 */
    data class Success(val reset: ResetResult?) : LoginOutcome

    /** 需要用户确认「换了账号，本地还有 N 条没推上去」，默认不继续。 */
    data class NeedsConfirm(val preview: SwitchPreview) : LoginOutcome

    data class Failure(val failure: ApiFailure) : LoginOutcome
}

/** 换账号确认框的内容：N 条未推送的修改会被移出工作区。 */
data class SwitchPreview(
    val serverUrl: String,
    val username: String,
    val pendingCount: Int,
)

/**
 * 账户生命周期：登录/注册/登出。
 * 不同账户登录前整库备份并清空 op_log 与同步 meta（保留 node_id / device_id）；
 * 同账户同地址再登录不清库。
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
    private val dsh: DshManager,
) {
    /** 已校验凭证、等待用户确认换账号的那次登录；只活一次。 */
    private var pendingSwitch: PendingSwitch? = null

    private class PendingSwitch(
        val user: UserOut,
        val tokens: WireTokens,
        val deviceId: String,
        val activeWorkspaceId: String?,
        val accountKey: String,
        val serverUrl: String,
        val username: String,
    )

    suspend fun login(username: String, password: String, serverUrl: String): LoginOutcome {
        serverConfig.update(serverUrl)
        val deviceId = repository.deviceId()
        return when (val result = apiCall(json) { authApi.login(LoginRequest(username, password, deviceId)) }) {
            is ApiResult.Success -> {
                val body = result.value
                adopt(body.user, body.tokens, deviceId, body.workspaces.firstOrNull()?.workspaceId)
            }

            is ApiResult.Failure -> LoginOutcome.Failure(result.failure)
        }
    }

    suspend fun register(
        username: String,
        password: String,
        displayName: String?,
        serverUrl: String,
    ): LoginOutcome {
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
                val body = result.value
                adopt(body.user, body.tokens, deviceId, body.defaultWorkspaceId)
            }

            is ApiResult.Failure -> LoginOutcome.Failure(result.failure)
        }
    }

    /** 确认框选「仍然切换」：清库（事务内记住新 accountKey）后绑定会话。 */
    suspend fun confirmSwitch(): LoginOutcome {
        val pending = pendingSwitch
            ?: return LoginOutcome.Failure(
                ApiFailure(
                    code = "switch_expired",
                    message = "登录已超时，请重新登录",
                    retryable = false,
                ),
            )
        pendingSwitch = null
        serverConfig.update(pending.serverUrl)
        val reset = repository.resetForAccount(pending.accountKey)
        bindSession(pending.user, pending.tokens, pending.deviceId, pending.activeWorkspaceId, pending.accountKey)
        return LoginOutcome.Success(reset)
    }

    /** 确认框选「取消」：丢弃本次登录，本地库与会话都不动。 */
    fun cancelSwitch() {
        pendingSwitch = null
    }

    /** 确认框里的「先导出备份」：立即落盘一份并返回路径。 */
    suspend fun exportBackup(): String? = repository.backupNow()

    suspend fun logout() {
        val session = sessionManager.load()
        if (session != null) {
            apiCall(json) { authApi.logout(LogoutRequest(session.refreshToken)) }
        }
        sessionManager.clear()
        // 账号退出后通道凭证随之失效：清空本地游标并停掉 MQTT。
        dsh.reset()
        dsh.stop()
        // 保留本地数据与 sync_account，仅清除登录态。
    }

    /** 启动时恢复登录态：有令牌即视为已登录，同步由 SyncEngine 负责。 */
    suspend fun restore(): Boolean = sessionManager.load() != null

    /**
     * 凭证已校验：判断是否需要换账号。
     *
     * - 同 accountKey：直接绑定，不动本地数据；
     * - 不同且没有未推送修改：静默清库（服务端可 pull 回来），但要回传备份信息；
     * - 不同且还有 pending：交给界面确认，绝不静默清库（A15）。
     */
    private suspend fun adopt(
        user: UserOut,
        tokens: WireTokens,
        deviceId: String,
        activeWorkspaceId: String?,
    ): LoginOutcome {
        val accountKey = accountKey(user.userId)
        if (repository.syncAccount() == accountKey) {
            bindSession(user, tokens, deviceId, activeWorkspaceId, accountKey)
            return LoginOutcome.Success(null)
        }
        val counts = repository.counts()
        if (counts.pending > 0) {
            pendingSwitch = PendingSwitch(
                user = user,
                tokens = tokens,
                deviceId = deviceId,
                activeWorkspaceId = activeWorkspaceId,
                accountKey = accountKey,
                serverUrl = serverConfig.baseUrl,
                username = user.username,
            )
            return LoginOutcome.NeedsConfirm(
                SwitchPreview(
                    serverUrl = serverConfig.baseUrl,
                    username = user.username,
                    pendingCount = counts.pending,
                ),
            )
        }
        val reset = repository.resetForAccount(accountKey)
        bindSession(user, tokens, deviceId, activeWorkspaceId, accountKey)
        return LoginOutcome.Success(reset)
    }

    private suspend fun bindSession(
        user: UserOut,
        tokens: WireTokens,
        deviceId: String,
        activeWorkspaceId: String?,
        accountKey: String,
    ) {
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

    /** accountKey 必须用规范化地址：多写一个 `/` 不该被当成另一台服务器（验收 §9.7-④）。 */
    private fun accountKey(userId: String): String = "${BaseUrl.normalize(serverConfig.baseUrl)}|$userId"
}
