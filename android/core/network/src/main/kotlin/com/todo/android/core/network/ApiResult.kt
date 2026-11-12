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
} catch (e: IOException) {
    ApiResult.Failure(
        ApiFailure(
            code = "network_error",
            message = "网络连接失败，请稍后重试",
            retryable = true,
        ),
    )
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
