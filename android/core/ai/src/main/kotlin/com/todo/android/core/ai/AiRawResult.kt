package com.todo.android.core.ai

/** 意图通道的原始返回：只做 HTTP 与内容提取，JSON 解析交给 `AiIntentParser`。 */
sealed interface AiRawResult {
    data class Success(val content: String) : AiRawResult

    data class Failure(val code: String, val message: String) : AiRawResult
}
