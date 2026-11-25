package com.todo.android.core.network.session

import com.todo.android.core.network.JsonConverterFactory
import com.todo.android.core.network.api.AuthApi
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * A18 的回归测试：刷新失败时**不能**把仍然有效的 refresh token 一起删掉。
 *
 * 这些用例覆盖 §9.7 验收 ②③ 的凭证分支；真机验收负责 UI 与端到端部分。
 */
class SessionManagerTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun authApi(): AuthApi {
        server.start()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(JsonConverterFactory(json))
            .build()
            .create(AuthApi::class.java)
    }

    private fun manager(store: SessionStore, api: AuthApi) =
        SessionManager(store, Provider { api }, json)

    private fun tokens(
        access: String = "access-old",
        refresh: String = "refresh-old",
        accessExpiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ) = SessionTokens(
        accessToken = access,
        refreshToken = refresh,
        accessExpiresAt = accessExpiresAt,
        refreshExpiresAt = System.currentTimeMillis() + 86_400_000,
        userId = "u-1",
        username = "alice",
        deviceId = "dev-1",
    )

    private fun errorResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setBody("""{"error":{"code":"$code","message":"测试","retryable":false,"details":null}}""")

    private fun okResponse(access: String, refresh: String) = MockResponse()
        .setResponseCode(200)
        .setBody(
            """
            {"tokens":{"token_type":"bearer","access_token":"$access",
            "access_expires_at":${System.currentTimeMillis() + 3_600_000},
            "refresh_token":"$refresh",
            "refresh_expires_at":${System.currentTimeMillis() + 86_400_000}}}
            """.trimIndent(),
        )

    @Test
    fun `token_expired 只是 access 过期 不清凭证`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())
        server.enqueue(errorResponse(401, "token_expired"))

        assertEquals(RefreshResult.NETWORK, manager.refresh(force = true))
        assertEquals("refresh-old", store.session?.refreshToken)
        assertEquals(0, store.clears)
    }

    @Test
    fun `unauthorized 是通用 401 不清凭证`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())
        server.enqueue(errorResponse(401, "unauthorized"))

        assertEquals(RefreshResult.NETWORK, manager.refresh(force = true))
        assertEquals("refresh-old", store.session?.refreshToken)
        assertEquals(0, store.clears)
    }

    @Test
    fun `网络错误不清凭证`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())
        server.shutdown()

        assertEquals(RefreshResult.NETWORK, manager.refresh(force = true))
        assertEquals("refresh-old", store.session?.refreshToken)
        assertEquals(0, store.clears)
    }

    @Test
    fun `invalid_refresh_token 才清凭证`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())
        server.enqueue(errorResponse(401, "invalid_refresh_token"))

        assertEquals(RefreshResult.INVALID, manager.refresh(force = true))
        assertNull(store.session)
        assertEquals(1, store.clears)
    }

    @Test
    fun `刷新成功会轮换并保存新令牌`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())
        server.enqueue(okResponse(access = "access-new", refresh = "refresh-new"))

        assertEquals(RefreshResult.SUCCESS, manager.refresh(force = true))
        assertEquals("access-new", store.session?.accessToken)
        assertEquals("refresh-new", store.session?.refreshToken)
        assertEquals("access-new", manager.cachedToken())
    }

    /** 这条是 A18 数据丢失的具体路径：旧实现返回过期令牌 → 401 → clear() 连 refresh 一起删。 */
    @Test
    fun `access 过期且刷新失败时 ensureFresh 返回 null 而不是过期令牌`() = runBlocking {
        val store = MemorySessionStore(tokens(accessExpiresAt = System.currentTimeMillis() - 1_000))
        val manager = manager(store, authApi())
        server.enqueue(errorResponse(503, "unavailable"))

        assertNull(manager.ensureFresh())
        assertEquals("refresh-old", store.session?.refreshToken)
        assertEquals(0, store.clears)
    }

    @Test
    fun `access 未过期时 ensureFresh 不发请求`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())

        val session = manager.ensureFresh()

        assertNotNull(session)
        assertEquals("access-old", session?.accessToken)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `force 刷新即使未过期也会请求服务端`() = runBlocking {
        val store = MemorySessionStore(tokens())
        val manager = manager(store, authApi())
        server.enqueue(okResponse(access = "access-forced", refresh = "refresh-forced"))

        assertEquals(RefreshResult.SUCCESS, manager.refresh(force = true))
        assertEquals(1, server.requestCount)
        assertEquals("access-forced", store.session?.accessToken)
    }

    /** 拿不到 refresh token 时无法恢复，视为失效（但没有可删的凭证）。 */
    @Test
    fun `refresh token 为空视为失效`() = runBlocking {
        val store = MemorySessionStore(tokens(refresh = ""))
        val manager = manager(store, authApi())

        assertEquals(RefreshResult.INVALID, manager.refresh(force = true))
        assertEquals(0, server.requestCount)
        assertTrue(store.session == null)
    }

    private class MemorySessionStore(var session: SessionTokens? = null) : SessionStore {
        var clears = 0

        override suspend fun current(): SessionTokens? = session

        override suspend fun save(tokens: SessionTokens) {
            session = tokens
        }

        override suspend fun clear() {
            session = null
            clears++
        }
    }
}
