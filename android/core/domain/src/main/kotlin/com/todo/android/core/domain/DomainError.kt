package com.todo.android.core.domain

/**
 * 领域错误。`code` 为英文机器码，`message` 为可直接展示的中文文案。
 * 可重试（网络 / 429 / 5xx）与不可重试（4xx 业务）在类型上区分。
 */
sealed class DomainError(
    val code: String,
    message: String,
) : Exception(message) {
    val retryable: Boolean
        get() = this is Retryable

    sealed class Retryable(code: String, message: String) : DomainError(code, message) {
        class Network(message: String = "网络连接失败，请稍后重试") : Retryable("network_error", message)

        class RateLimited(message: String = "操作过于频繁，请稍后重试") : Retryable("rate_limited", message)

        class Server(message: String = "服务端内部错误，请重试") : Retryable("internal_error", message)
    }

    sealed class Terminal(code: String, message: String) : DomainError(code, message) {
        class Unauthorized(message: String = "请先登录") : Terminal("unauthorized", message)

        class Forbidden(message: String = "当前角色无权执行此操作") : Terminal("forbidden", message)

        class InvalidValue(message: String) : Terminal("invalid_value", message)

        class InvalidOp(message: String = "同步数据格式不正确") :
            Terminal("invalid_op_envelope", message)

        class NotFound(message: String = "资源不存在") : Terminal("not_found", message)
    }

    companion object {
        /** 按服务端 `code` 归类；未知 code 一律按不可重试处理。 */
        fun fromCode(code: String, message: String): DomainError = when (code) {
            "network_error" -> Retryable.Network(message)
            "rate_limited" -> Retryable.RateLimited(message)
            "internal_error" -> Retryable.Server(message)
            "unauthorized", "actor_mismatch", "token_expired", "invalid_refresh_token" ->
                Terminal.Unauthorized(message)
            "forbidden", "workspace_access_revoked" -> Terminal.Forbidden(message)
            "workspace_not_found", "member_not_found", "account_not_found", "snapshot_not_found" ->
                Terminal.NotFound(message)
            else -> Terminal.InvalidValue(message)
        }
    }
}
