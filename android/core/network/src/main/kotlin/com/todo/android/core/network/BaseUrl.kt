package com.todo.android.core.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** 服务器地址归一化：容忍尾部 `/` 与已含 `/v1`，接口方法自带 `v1/...`，避免 `/v1/v1`。 */
object BaseUrl {
    const val DEFAULT = "http://127.0.0.1:8000"

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty().ifEmpty { DEFAULT }
        var url = trimmed.trimEnd('/')
        if (url.endsWith("/v1")) url = url.dropLast(3).trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    fun toHttpUrl(raw: String?): HttpUrl? = normalize(raw).toHttpUrlOrNull()
}

/** 运行时可变服务器地址；设置页修改后由同步入口更新。 */
@Singleton
class ServerConfig @Inject constructor() {
    @Volatile
    var baseUrl: String = BaseUrl.DEFAULT
        private set

    fun update(url: String?) {
        baseUrl = BaseUrl.normalize(url)
    }
}

/**
 * Retrofit 的 baseUrl 是固定的占位地址，这里把请求重写到用户配置的服务器。
 * 只替换 scheme/host/port 与路径前缀，保留查询参数。
 */
class BaseUrlInterceptor @Inject constructor(
    private val config: ServerConfig,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = BaseUrl.toHttpUrl(config.baseUrl) ?: return chain.proceed(request)
        val builder = target.newBuilder()
        request.url.encodedPathSegments
            .filter { it.isNotEmpty() }
            .forEach(builder::addPathSegment)
        request.url.encodedQuery?.let(builder::encodedQuery)
        val rewritten = request.newBuilder().url(builder.build()).build()
        return chain.proceed(rewritten)
    }
}
