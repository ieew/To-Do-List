package com.todo.android.core.domain

import kotlinx.serialization.json.JsonPrimitive

/**
 * 恢复意图（`shared/testvectors/restore.json`）：
 * 只恢复目标快照中存在的实体；标量字段做 LWW set，集合做 add/remove 差集；
 * 不删除快照之后新增的实体。返回的变更顺序稳定，便于逐条断言。
 */
data class RestoreSubtask(
    val id: String,
    val text: String,
    val done: Boolean,
    val order: Double,
)

data class RestoreTask(
    val id: String,
    val title: String,
    val notes: String,
    val priority: String,
    val status: String,
    val listId: String,
    val order: Double,
    val tagIds: List<String>,
    val subtasks: List<RestoreSubtask>,
)

data class RestoreList(
    val id: String,
    val name: String,
    val color: String,
    val order: Double,
)

data class RestoreSnapshot(
    val lists: List<RestoreList>,
    val tasks: List<RestoreTask>,
)

data class RestoreChange(
    val entityId: String,
    val operation: OpOperation,
    val field: String,
    val value: kotlinx.serialization.json.JsonElement,
)

object RestorePlanner {
    /** 目标快照 → 当前投影 的最小变更集；实体不存在于当前投影时跳过。 */
    fun plan(target: RestoreSnapshot, current: RestoreSnapshot): List<RestoreChange> {
        val changes = ArrayList<RestoreChange>()
        val currentTasks = current.tasks.associateBy { it.id }
        val currentLists = current.lists.associateBy { it.id }

        target.lists.forEach { list ->
            val now = currentLists[list.id] ?: return@forEach
            if (list.name != now.name) {
                changes += set(list.id, "name", JsonPrimitive(list.name))
            }
            if (list.color != now.color) {
                changes += set(list.id, "color", JsonPrimitive(list.color))
            }
            if (list.order != now.order) {
                changes += set(list.id, "order", JsonPrimitive(list.order))
            }
        }

        target.tasks.forEach { task ->
            val now = currentTasks[task.id] ?: return@forEach

            if (task.title != now.title) changes += set(task.id, Fields.TITLE, JsonPrimitive(task.title))
            if (task.notes != now.notes) changes += set(task.id, Fields.NOTES, JsonPrimitive(task.notes))
            if (task.priority != now.priority) {
                changes += set(task.id, Fields.PRIORITY, JsonPrimitive(task.priority))
            }
            if (task.status != now.status) changes += set(task.id, Fields.STATUS, JsonPrimitive(task.status))
            if (task.listId != now.listId) changes += set(task.id, Fields.LIST_ID, JsonPrimitive(task.listId))
            if (task.order != now.order) changes += set(task.id, Fields.ORDER, JsonPrimitive(task.order))

            val currentSubtasks = now.subtasks.associateBy { it.id }
            task.subtasks.forEach { subtask ->
                val existing = currentSubtasks[subtask.id] ?: return@forEach
                if (subtask.text != existing.text) {
                    changes += set(task.id, Fields.subtaskText(subtask.id), JsonPrimitive(subtask.text))
                }
                if (subtask.done != existing.done) {
                    changes += set(task.id, Fields.subtaskDone(subtask.id), JsonPrimitive(subtask.done))
                }
                if (subtask.order != existing.order) {
                    changes += set(task.id, Fields.subtaskOrder(subtask.id), JsonPrimitive(subtask.order))
                }
            }

            val targetTags = task.tagIds.toSortedSet()
            val currentTags = now.tagIds.toSortedSet()
            (targetTags - currentTags).forEach { tagId ->
                changes += RestoreChange(task.id, OpOperation.ADD, Fields.TAG, JsonPrimitive(tagId))
            }
            (currentTags - targetTags).forEach { tagId ->
                changes += RestoreChange(task.id, OpOperation.REMOVE, Fields.TAG, JsonPrimitive(tagId))
            }

            val targetSubtaskIds = task.subtasks.map { it.id }.toSortedSet()
            val currentSubtaskIds = now.subtasks.map { it.id }.toSortedSet()
            (targetSubtaskIds - currentSubtaskIds).forEach { subtaskId ->
                changes += RestoreChange(task.id, OpOperation.ADD, Fields.SUBTASK, JsonPrimitive(subtaskId))
            }
            (currentSubtaskIds - targetSubtaskIds).forEach { subtaskId ->
                changes += RestoreChange(task.id, OpOperation.REMOVE, Fields.SUBTASK, JsonPrimitive(subtaskId))
            }
        }

        return changes
    }

    private fun set(entityId: String, field: String, value: kotlinx.serialization.json.JsonElement): RestoreChange =
        RestoreChange(entityId, OpOperation.SET, field, value)
}
