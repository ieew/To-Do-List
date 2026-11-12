package com.todo.android.core.network.session

import javax.inject.Inject
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import kotlinx.coroutines.runBlocking

/** 给除登录/刷新之外的请求附加 Bearer 令牌。 */
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isAuthEndpoint()) return chain.proceed(request)
        val token = sessionManager.cachedToken() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/** 401 时刷新令牌并重放一次；刷新失败则结束（上层转 auth_required）。 */
class TokenAuthenticator @Inject constructor(
    private val sessionManager: SessionManager,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.isAuthEndpoint()) return null
        if (responseCount(response) >= 2) return null

        if (!runBlocking { sessionManager.refresh() }) return null
        val token = sessionManager.cachedToken() ?: return null
        val previous = request.header("Authorization")
        if (previous == "Bearer $token") return null
        return request.newBuilder().header("Authorization", "Bearer $token").build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

private fun Request.isAuthEndpoint(): Boolean =
    url.encodedPath.contains("/auth/login") ||
        url.encodedPath.contains("/auth/register") ||
        url.encodedPath.contains("/auth/refresh")
