package com.todo.android.core.network.session

import com.todo.android.core.network.ApiResult
import com.todo.android.core.network.api.AuthApi
import com.todo.android.core.network.apiCall
import com.todo.android.core.network.wire.RefreshRequest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** 刷新结果：调用方必须区分「网络问题」与「凭证确实失效」，否则会把网络抖动当成掉线。 */
enum class RefreshResult {
    /** 拿到新令牌。 */
    SUCCESS,

    /** 网络/服务端暂时不可用，凭证仍然有效，稍后重试。 */
    NETWORK,

    /** 服务端明确判定 refresh token 失效（已失效/被吊销/重用），本地凭证已清。 */
    INVALID,
}

/**
 * 登录态管理：请求前 60 秒主动刷新；refresh 必须单飞；
 * refresh token 每次轮换后立即保存新值。
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: SessionStore,
    private val authApi: Provider<AuthApi>,
    private val json: Json,
) {
    @Volatile
    private var cached: SessionTokens? = null

    private val refreshMutex = Mutex()

    fun cachedToken(): String? = cached?.accessToken?.takeIf { it.isNotBlank() }

    suspend fun load(): SessionTokens? {
        val session = store.current()
        cached = session
        return session
    }

    suspend fun save(session: SessionTokens) {
        store.save(session)
        cached = session
    }

    suspend fun clear() {
        store.clear()
        cached = null
    }

    /**
     * 只作废 access token，保留 refresh token。
     *
     * access 过期/网关误判 401 时如果直接 [clear]，会把仍然有效的 refresh token 一起删掉，
     * 用户被迫重新输密码（A18）；这里退化成「下次请求前必然先刷新」。
     */
    suspend fun invalidateAccess() {
        refreshMutex.withLock {
            val session = cached ?: store.current()?.also { cached = it } ?: return@withLock
            val next = session.copy(accessToken = "", accessExpiresAt = 0L)
            store.save(next)
            cached = next
        }
    }

    /**
     * 取一个可用的会话。
     *
     * 刷新失败必须返回 null —— 返回已过期的令牌会让调用方带着它请求、拿到 401，
     * 再顺理成章地把有效 refresh token 清掉（A18 的数据丢失路径）。
     */
    suspend fun ensureFresh(nowMs: Long = System.currentTimeMillis()): SessionTokens? {
        val session = cached ?: store.current()?.also { cached = it } ?: return null
        if (isFresh(session, nowMs)) return session
        return if (refresh() == RefreshResult.SUCCESS) cached else null
    }

    /**
     * 刷新令牌；[force] 为真时忽略本地过期时间强制刷新（拿到 401 后使用）。
     * 并发调用共享同一次刷新结果。
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = refreshMutex.withLock {
        val session = cached ?: store.current()?.also { cached = it } ?: return@withLock RefreshResult.INVALID
        if (!force && isFresh(session, System.currentTimeMillis())) return@withLock RefreshResult.SUCCESS
        if (session.refreshToken.isBlank()) {
            markInvalidLocked(session)
            return@withLock RefreshResult.INVALID
        }
        val result = apiCall(json) { authApi.get().refresh(RefreshRequest(session.refreshToken, session.deviceId)) }
        when (result) {
            is ApiResult.Success -> {
                val tokens = result.value.tokens
                val next = session.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    accessExpiresAt = tokens.accessExpiresAt,
                    refreshExpiresAt = tokens.refreshExpiresAt,
                )
                store.save(next)
                cached = next
                RefreshResult.SUCCESS
            }

            is ApiResult.Failure -> when (result.failure.code) {
                // 只有服务端明确说「这个 refresh token 不再有效」才丢弃本地凭证。
                // 服务端 refresh 端点的权威错误码是 invalid_refresh_token
                // （server/app/features/auth/usecase.py:310）。
                // token_expired 表示 access 过期（errors.py:39，retryable=true），
                // unauthorized 是通用 401 —— 这两个都**不能**当作 refresh 失效，
                // 否则网络抖动/网关误判一次就删掉有效 refresh token（A18）。
                "invalid_refresh_token", "invalid_token", "invalid_grant" -> {
                    markInvalidLocked(session)
                    RefreshResult.INVALID
                }

                else -> RefreshResult.NETWORK
            }
        }
    }

    /**
     * 服务端明确判定 refresh token 失效时调用。
     *
     * 这里**故意不 [clear]**：清掉整个会话会让 `bootstrap()` 判成 `SIGNED_OUT`，
     * 界面退回「未登录」，A17 要求的「登录已过期 · N 条修改待推送」横幅就没有依据，
     * 用户也看不到自己还有多少改动没推上去。
     * 只作废令牌、保留 userId/serverUrl/username 这些身份信息，
     * 由 [SessionTokens.hasCredentials] 为假来表达「需要重新登录」。
     */
    private suspend fun markInvalidLocked(session: SessionTokens) {
        val next = session.copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAt = 0L,
            refreshExpiresAt = 0L,
        )
        store.save(next)
        cached = next
    }

    private fun isFresh(session: SessionTokens, nowMs: Long): Boolean =
        session.accessToken.isNotBlank() && session.accessExpiresAt - nowMs > REFRESH_AHEAD_MS

    companion object {
        const val REFRESH_AHEAD_MS = 60_000L
    }
}
