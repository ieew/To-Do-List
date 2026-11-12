package com.todo.android.core.ai

import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.domain.OpSource
import javax.inject.Inject
import javax.inject.Singleton

/** 把已确认的草案写入 op 管道，全部标记为 `source=ai`。 */
@Singleton
class AiWriter @Inject constructor(
    private val commands: TaskCommands,
) {
    suspend fun write(workspaceId: String, tasks: List<PlannedTask>, model: String): Int {
        var written = 0
        tasks.forEach { planned ->
            val taskId = commands.createTask(
                workspaceId = workspaceId,
                listId = planned.listId,
                title = planned.title,
                startAtIso = planned.startAtIso,
                dueAtIso = planned.dueAtIso,
                priority = planned.priority,
                source = OpSource.AI,
                model = model,
            )
            if (planned.notes.isNotBlank()) {
                commands.setNotes(taskId, workspaceId, planned.notes, OpSource.AI, model)
            }
            planned.tagIds.forEach { tagId ->
                commands.addTag(taskId, workspaceId, tagId, OpSource.AI, model)
            }
            planned.subtasks.forEachIndexed { index, text ->
                commands.addSubtask(taskId, workspaceId, text, index.toDouble(), OpSource.AI, model)
            }
            written++
        }
        return written
    }
}
