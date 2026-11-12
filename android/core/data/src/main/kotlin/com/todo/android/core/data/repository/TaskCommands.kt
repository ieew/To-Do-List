package com.todo.android.core.data.repository

import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.EntityType
import com.todo.android.core.domain.Fields
import com.todo.android.core.domain.HlcClock
import com.todo.android.core.domain.Op
import com.todo.android.core.domain.OpOperation
import com.todo.android.core.domain.OpSource
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 业务写操作的唯一装配点：所有 op 都经 [OpRepository.appendOps] 写入同一事务。
 * feature 层不自己生成 op_id，也不在 UI 里推进 HLC。
 */
@Singleton
class TaskCommands @Inject constructor(
    private val repository: OpRepository,
    private val wallClock: WallClock,
) {
    suspend fun createTask(
        workspaceId: String,
        listId: String,
        title: String,
        startAtIso: String,
        dueAtIso: String,
        priority: String = "mid",
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
    ): String {
        val taskId = newId("task")
        val now = wallClock.nowMs()
        append(workspaceId, source, model) { context ->
            listOf(
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.CREATED, JsonPrimitive(IsoTime.nowIso(now))),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.TITLE, JsonPrimitive(title.trim())),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.LIST_ID, JsonPrimitive(listId)),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.START_AT, JsonPrimitive(startAtIso)),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.DUE_AT, JsonPrimitive(dueAtIso)),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.PRIORITY, JsonPrimitive(priority)),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.ORDER, JsonPrimitive(now.toDouble())),
            )
        }
        return taskId
    }

    suspend fun updateField(
        taskId: String,
        workspaceId: String,
        field: String,
        value: JsonElement,
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
    ) {
        write(taskId, workspaceId, EntityType.TASK, OpOperation.SET, field, value, source, model)
    }

    suspend fun setDone(taskId: String, workspaceId: String, done: Boolean) {
        updateField(taskId, workspaceId, Fields.STATUS, JsonPrimitive(if (done) "done" else "todo"))
    }

    suspend fun setNotes(
        taskId: String,
        workspaceId: String,
        notes: String,
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
    ) {
        updateField(taskId, workspaceId, Fields.NOTES, JsonPrimitive(notes), source, model)
    }

    suspend fun addTag(
        taskId: String,
        workspaceId: String,
        tagId: String,
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
    ) {
        write(taskId, workspaceId, EntityType.TASK, OpOperation.ADD, Fields.TAG, JsonPrimitive(tagId), source, model)
    }

    suspend fun removeTag(taskId: String, workspaceId: String, tagId: String) {
        write(taskId, workspaceId, EntityType.TASK, OpOperation.REMOVE, Fields.TAG, JsonPrimitive(tagId))
    }

    suspend fun addSubtask(
        taskId: String,
        workspaceId: String,
        text: String,
        order: Double,
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
    ) {
        val subtaskId = newId("sub")
        append(workspaceId, source, model) { context ->
            listOf(
                context.op(taskId, EntityType.TASK, OpOperation.ADD, Fields.SUBTASK, JsonPrimitive(subtaskId)),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.subtaskText(subtaskId), JsonPrimitive(text.trim())),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.subtaskDone(subtaskId), JsonPrimitive(false)),
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.subtaskOrder(subtaskId), JsonPrimitive(order)),
            )
        }
    }

    suspend fun setSubtaskDone(taskId: String, workspaceId: String, subtaskId: String, done: Boolean) {
        updateField(taskId, workspaceId, Fields.subtaskDone(subtaskId), JsonPrimitive(done))
    }

    suspend fun deleteSubtask(taskId: String, workspaceId: String, subtaskId: String) {
        write(taskId, workspaceId, EntityType.TASK, OpOperation.REMOVE, Fields.SUBTASK, JsonPrimitive(subtaskId))
    }

    suspend fun renameList(listId: String, workspaceId: String, name: String) {
        write(listId, workspaceId, EntityType.LIST, OpOperation.SET, Fields.NAME, JsonPrimitive(name.trim()))
    }

    suspend fun deleteList(listId: String, workspaceId: String) {
        write(listId, workspaceId, EntityType.LIST, OpOperation.SET, Fields.DELETED, JsonPrimitive(true))
    }

    /** 新建工作区：工作区 + owner membership + 4 清单 + 5 标签，与服务端种子一致。 */
    suspend fun createWorkspace(name: String, userId: String): String {
        val workspaceId = newId("ws")
        val now = wallClock.nowMs()
        val lists = listOf(
            Triple("today", "今天", "#3B82F6"),
            Triple("work", "工作", "#F59E0B"),
            Triple("life", "生活", "#10B981"),
            Triple("study", "学习", "#8B5CF6"),
        )
        val tags = listOf(
            Quad("work", "工作", "#EFF6FF", "#2563EB"),
            Quad("life", "生活", "#ECFDF5", "#059669"),
            Quad("study", "学习", "#F5F3FF", "#7C3AED"),
            Quad("important", "重要", "#FEF3C7", "#B45309"),
            Quad("week", "本周", "#EFF6FF", "#3B82F6"),
        )
        val createdIso = IsoTime.nowIso(now)
        append(workspaceId) { context ->
            buildList {
                add(context.op(workspaceId, EntityType.WORKSPACE, OpOperation.SET, Fields.CREATED, JsonPrimitive(createdIso)))
                add(context.op(workspaceId, EntityType.WORKSPACE, OpOperation.SET, Fields.NAME, JsonPrimitive(name.trim())))
                add(
                    context.op(
                        "$workspaceId:$userId",
                        EntityType.MEMBERSHIP,
                        OpOperation.SET,
                        Fields.ROLE,
                        JsonPrimitive("owner"),
                    ),
                )
                add(context.op(userId, EntityType.USER, OpOperation.SET, Fields.NAME, JsonPrimitive("我")))
                add(context.op(userId, EntityType.USER, OpOperation.SET, Fields.INITIAL, JsonPrimitive("我")))
                add(context.op(userId, EntityType.USER, OpOperation.SET, Fields.COLOR, JsonPrimitive("#3B82F6")))
                lists.forEachIndexed { index, (suffix, listName, color) ->
                    val listId = "$workspaceId-$suffix"
                    add(context.op(listId, EntityType.LIST, OpOperation.SET, Fields.CREATED, JsonPrimitive(createdIso)))
                    add(context.op(listId, EntityType.LIST, OpOperation.SET, Fields.WORKSPACE_ID, JsonPrimitive(workspaceId)))
                    add(context.op(listId, EntityType.LIST, OpOperation.SET, Fields.NAME, JsonPrimitive(listName)))
                    add(context.op(listId, EntityType.LIST, OpOperation.SET, Fields.COLOR, JsonPrimitive(color)))
                    add(context.op(listId, EntityType.LIST, OpOperation.SET, Fields.ORDER, JsonPrimitive(index.toDouble())))
                }
                tags.forEach { tag ->
                    val tagId = "$workspaceId-tag-${tag.suffix}"
                    add(context.op(tagId, EntityType.TAG, OpOperation.SET, Fields.CREATED, JsonPrimitive(createdIso)))
                    add(context.op(tagId, EntityType.TAG, OpOperation.SET, Fields.WORKSPACE_ID, JsonPrimitive(workspaceId)))
                    add(context.op(tagId, EntityType.TAG, OpOperation.SET, Fields.NAME, JsonPrimitive(tag.name)))
                    add(context.op(tagId, EntityType.TAG, OpOperation.SET, Fields.BG, JsonPrimitive(tag.bg)))
                    add(context.op(tagId, EntityType.TAG, OpOperation.SET, Fields.FG, JsonPrimitive(tag.fg)))
                }
            }
        }
        repository.setActiveWorkspaceId(workspaceId)
        return workspaceId
    }

    suspend fun renameWorkspace(workspaceId: String, name: String) {
        write(workspaceId, workspaceId, EntityType.WORKSPACE, OpOperation.SET, Fields.NAME, JsonPrimitive(name.trim()))
    }

    suspend fun deleteWorkspace(workspaceId: String) {
        write(workspaceId, workspaceId, EntityType.WORKSPACE, OpOperation.SET, Fields.DELETED, JsonPrimitive(true))
    }

    /** 撤销：追加反向 op，再追加 `__undo_of` 指向被撤销的 op id。 */
    suspend fun undo(
        taskId: String,
        workspaceId: String,
        targetOpId: String,
        field: String,
        operation: OpOperation,
        oldValue: JsonElement?,
        collectionElement: String?,
    ) {
        append(workspaceId) { context ->
            val reverse: Op = when (operation) {
                OpOperation.ADD -> context.op(
                    taskId,
                    EntityType.TASK,
                    OpOperation.REMOVE,
                    field,
                    JsonPrimitive(collectionElement.orEmpty()),
                )

                OpOperation.REMOVE -> context.op(
                    taskId,
                    EntityType.TASK,
                    OpOperation.ADD,
                    field,
                    JsonPrimitive(collectionElement.orEmpty()),
                )

                OpOperation.SET -> context.op(
                    taskId,
                    EntityType.TASK,
                    OpOperation.SET,
                    field,
                    oldValue ?: JsonPrimitive(""),
                )
            }
            listOf(
                reverse,
                context.op(taskId, EntityType.TASK, OpOperation.SET, Fields.UNDO_OF, JsonPrimitive(targetOpId)),
            )
        }
    }

    private suspend fun write(
        entityId: String,
        workspaceId: String,
        entityType: EntityType,
        operation: OpOperation,
        field: String,
        value: JsonElement,
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
    ) {
        append(workspaceId, source, model) { context ->
            listOf(context.op(entityId, entityType, operation, field, value))
        }
    }

    private suspend fun append(
        workspaceId: String,
        source: OpSource = OpSource.HUMAN,
        model: String? = null,
        build: (OpContext) -> List<Op>,
    ) {
        val actor = repository.currentUserId()
        val device = repository.deviceId()
        repository.appendOps(workspaceId) { clock ->
            build(OpContext(actor, device, clock, workspaceId, source, model))
        }
    }

    private class OpContext(
        private val actor: String,
        private val device: String,
        private val clock: HlcClock,
        private val workspaceId: String,
        private val source: OpSource,
        private val model: String?,
    ) {
        fun op(
            entityId: String,
            entityType: EntityType,
            operation: OpOperation,
            field: String,
            value: JsonElement,
        ): Op = Op(
            opId = "op-${UUID.randomUUID().toString().replace("-", "").take(16)}",
            workspaceId = workspaceId,
            entityType = entityType,
            entityId = entityId,
            actorId = actor,
            deviceId = device,
            operation = operation,
            field = field,
            value = value,
            hlc = clock.next(),
            source = source,
            model = model,
        )
    }

    private data class Quad(val suffix: String, val name: String, val bg: String, val fg: String)

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().replace("-", "").take(12)}"
}
