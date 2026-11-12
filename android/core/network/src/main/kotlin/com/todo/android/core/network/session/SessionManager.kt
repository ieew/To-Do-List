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

/**
 * 登录态管理：请求前 60 秒主动刷新；refresh 必须单飞；
 * refresh token 每次轮换后立即保存新值。
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: TokenStore,
    private val authApi: Provider<AuthApi>,
    private val json: Json,
) {
    @Volatile
    private var cached: SessionTokens? = null

    private val refreshMutex = Mutex()

    fun cachedToken(): String? = cached?.accessToken

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

    suspend fun ensureFresh(nowMs: Long = System.currentTimeMillis()): SessionTokens? {
        val session = cached ?: store.current()?.also { cached = it } ?: return null
        if (session.accessExpiresAt - nowMs > REFRESH_AHEAD_MS) return session
        refresh()
        return cached
    }

    /** 返回是否刷新成功；并发调用共享同一次刷新结果。 */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        val session = cached ?: store.current()?.also { cached = it } ?: return@withLock false
        when (val result = apiCall(json) { authApi.get().refresh(RefreshRequest(session.refreshToken, session.deviceId)) }) {
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
                true
            }

            is ApiResult.Failure -> {
                if (!result.failure.retryable) {
                    store.clear()
                    cached = null
                }
                false
            }
        }
    }

    companion object {
        const val REFRESH_AHEAD_MS = 60_000L
    }
}
