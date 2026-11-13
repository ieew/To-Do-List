package com.todo.android.core.network

import com.todo.android.core.domain.DomainError
import com.todo.android.core.network.wire.ApiErrorEnvelope
import com.todo.android.core.network.wire.WireHlc
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** 服务端错误信封解析结果。只按 `code` 分支，禁止解析 `message` 做业务判断。 */
data class ApiFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val serverHlc: WireHlc? = null,
    val httpStatus: Int = 0,
    val details: kotlinx.serialization.json.JsonElement? = null,
) {
    fun toDomainError(): DomainError = DomainError.fromCode(code, message)
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>
}

/** 统一把 HTTP 错误、网络错误映射为 [ApiFailure]，禁止裸 try-catch 吞异常。 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: HttpException) {
    ApiResult.Failure(parseHttpError(json, e))
} catch (e: kotlinx.serialization.SerializationException) {
    // 响应字段与模型不一致时必须降级为失败，不能把异常抛给调用方（会在协程里直接闪退）。
    ApiResult.Failure(
        ApiFailure(
            code = "decode_error",
            message = "服务器响应无法识别：" + (e.message ?: e.javaClass.simpleName).take(160),
            retryable = false,
        ),
    )
} catch (e: IOException) {
    ApiResult.Failure(
        ApiFailure(
            code = "network_error",
            message = networkErrorMessage(e),
            retryable = true,
        ),
    )
}

/**
 * 网络异常按类型给出可操作的中文原因：
 * 明文被拦截 / DNS / 连接被拒 / 超时 各自的排查方向完全不同。
 */
private fun networkErrorMessage(e: IOException): String {
    val raw = e.message.orEmpty()
    return when {
        raw.contains("CLEARTEXT", ignoreCase = true) ->
            "明文 HTTP 被系统拦截，请在网络安全配置中放行该地址"

        e is java.net.UnknownHostException ->
            "域名解析失败：${raw.ifBlank { "未知主机" }}"

        e is java.net.SocketTimeoutException ->
            "连接超时，请检查服务器地址、端口与防火墙"

        e is java.net.ConnectException ->
            "无法连接服务器（连接被拒绝），请确认地址与端口正确且服务端已监听该网卡"

        e is java.net.SocketException ->
            "网络中断：${raw.ifBlank { e.javaClass.simpleName }}"

        else -> "网络连接失败：${e.javaClass.simpleName} ${raw.take(120)}".trim()
    }
}

fun parseHttpError(json: Json, error: HttpException): ApiFailure {
    val status = error.code()
    val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
    val envelope = raw?.let {
        runCatching { json.decodeFromString(ApiErrorEnvelope.serializer(), it) }.getOrNull()
    }
    val body = envelope?.error
    val fallbackCode = when (status) {
        401 -> "unauthorized"
        403 -> "forbidden"
        404 -> "not_found"
        413 -> "payload_too_large"
        429 -> "rate_limited"
        in 500..599 -> "internal_error"
        else -> "invalid_request"
    }
    return ApiFailure(
        code = body?.code ?: fallbackCode,
        message = body?.message ?: defaultMessage(fallbackCode),
        retryable = body?.retryable ?: (status == 429 || status >= 500),
        serverHlc = body?.serverHlc,
        httpStatus = status,
        details = body?.details,
    )
}

private fun defaultMessage(code: String): String = when (code) {
    "unauthorized" -> "请先登录"
    "forbidden" -> "当前角色无权执行此操作"
    "not_found" -> "资源不存在"
    "payload_too_large" -> "同步数据过大，请分批提交"
    "rate_limited" -> "操作过于频繁，请稍后重试"
    "internal_error" -> "服务端内部错误，请重试"
    else -> "请求参数不合法"
}
