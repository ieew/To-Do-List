package com.todo.android.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** AI 返回的草案（字段名与提示词约定一致）。 */
@Serializable
data class AiDraftTask(
    val title: String = "",
    val notes: String = "",
    val priority: String = "mid",
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    val list: String? = null,
    val tags: List<String> = emptyList(),
    val subtasks: List<AiDraftSubtask> = emptyList(),
)

@Serializable
data class AiDraftSubtask(
    val text: String = "",
    val done: Boolean = false,
)

@Serializable
data class AiDraftResponse(val tasks: List<AiDraftTask> = emptyList())

/** 已解析为可写入的草案。 */
data class PlannedTask(
    val title: String,
    val notes: String,
    val priority: String,
    val startAtIso: String,
    val dueAtIso: String,
    val listId: String,
    val listName: String,
    val tagIds: List<String>,
    val tagNames: List<String>,
    val subtasks: List<String>,
    val warnings: List<String>,
)

data class AiPlan(
    val tasks: List<PlannedTask>,
    val warnings: List<String>,
)

sealed interface AiDraftResult {
    data class Success(val tasks: List<AiDraftTask>, val raw: String) : AiDraftResult

    data class Failure(val code: String, val message: String) : AiDraftResult
}
