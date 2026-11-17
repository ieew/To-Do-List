package com.todo.android.core.ai

import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.Fields
import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.WorkspaceProjection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 一次 AI 写入的结果：opIds 供后续批量撤销，taskIds 用于提示与跳转。 */
data class AiApplyResult(
    val opIds: List<String>,
    val taskIds: List<String>,
    val applied: Int,
    val warnings: List<String>,
)

/**
 * 把已确认的意图操作转成普通 op 写入（全部 `source=ai`）。
 *
 * 单条操作失败只记 warning，不影响后续操作；与 Windows 端「一次事务写入 +
 * 返回 opIds」行为对齐（Android 侧每条 op 都是独立事务）。
 */
@Singleton
class AiIntentApplier @Inject constructor(
    private val commands: TaskCommands,
) {
    suspend fun apply(
        workspaceId: String,
        operations: List<AiOperation>,
        model: String,
        projection: WorkspaceProjection,
        nowMs: Long = System.currentTimeMillis(),
    ): AiApplyResult {
        val opIds = mutableListOf<String>()
        val taskIds = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var applied = 0

        operations.forEach { operation ->
            runCatching {
                when (operation) {
                    is AiOperation.CreateTask -> {
                        val (taskId, ids) = commands.createTaskWithOps(
                            workspaceId = workspaceId,
                            listId = operation.listId,
                            title = operation.title,
                            startAtIso = IsoTime.nowIso(nowMs),
                            dueAtIso = operation.dueAtIso,
                            priority = operation.priority,
                            source = OpSource.AI,
                            model = model,
                        )
                        opIds += ids
                        taskIds += taskId
                        if (operation.notes.isNotBlank()) {
                            opIds += commands.setNotes(taskId, workspaceId, operation.notes, OpSource.AI, model)
                        }
                        operation.tagIds.forEach { tagId ->
                            opIds += commands.addTag(taskId, workspaceId, tagId, OpSource.AI, model)
                        }
                        operation.subtasks.forEachIndexed { index, text ->
                            opIds += commands.addSubtask(taskId, workspaceId, text, index.toDouble(), OpSource.AI, model)
                        }
                    }

                    is AiOperation.UpdateFields -> {
                        operation.patch.forEach { (field, value) ->
                            opIds += commands.updateField(
                                operation.taskId,
                                workspaceId,
                                field,
                                JsonPrimitive(value),
                                OpSource.AI,
                                model,
                            )
                        }
                        operation.clearFields.forEach { field ->
                            opIds += commands.updateField(
                                operation.taskId,
                                workspaceId,
                                field,
                                JsonNull,
                                OpSource.AI,
                                model,
                            )
                        }
                        taskIds += operation.taskId
                    }

                    is AiOperation.AddSubtasks -> {
                        var order = projection.tasks
                            .firstOrNull { it.id == operation.taskId }
                            ?.subtasks
                            ?.maxOfOrNull { it.order }
                            ?: 0.0
                        operation.texts.forEach { text ->
                            order += 1.0
                            opIds += commands.addSubtask(
                                operation.taskId,
                                workspaceId,
                                text,
                                order,
                                OpSource.AI,
                                model,
                            )
                        }
                        taskIds += operation.taskId
                    }

                    is AiOperation.SetSubtaskDone -> {
                        opIds += commands.setSubtaskDone(
                            operation.taskId,
                            workspaceId,
                            operation.subtaskId,
                            operation.done,
                        )
                        taskIds += operation.taskId
                    }

                    is AiOperation.AddTags -> {
                        operation.tagIds.forEach { tagId ->
                            opIds += commands.addTag(operation.taskId, workspaceId, tagId, OpSource.AI, model)
                        }
                        taskIds += operation.taskId
                    }

                    is AiOperation.RemoveTags -> {
                        operation.tagIds.forEach { tagId ->
                            opIds += commands.removeTag(operation.taskId, workspaceId, tagId)
                        }
                        taskIds += operation.taskId
                    }

                    is AiOperation.SetStatus -> {
                        opIds += commands.updateField(
                            operation.taskId,
                            workspaceId,
                            Fields.STATUS,
                            JsonPrimitive(operation.status),
                            OpSource.AI,
                            model,
                        )
                        taskIds += operation.taskId
                    }

                    is AiOperation.DeleteTask -> {
                        opIds += commands.updateField(
                            operation.taskId,
                            workspaceId,
                            Fields.DELETED,
                            JsonPrimitive(true),
                            OpSource.AI,
                            model,
                        )
                        taskIds += operation.taskId
                    }
                }
                applied++
            }.onFailure { error ->
                warnings += "${operation.summary} 写入失败：${error.message ?: "未知错误"}"
            }
        }

        return AiApplyResult(
            opIds = opIds,
            taskIds = taskIds.distinct(),
            applied = applied,
            warnings = warnings,
        )
    }
}
