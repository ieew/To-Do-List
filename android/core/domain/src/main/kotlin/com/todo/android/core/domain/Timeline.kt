package com.todo.android.core.domain

import kotlinx.serialization.json.JsonElement

/** 一条历史记录（任务级时间线）。时间以毫秒暴露，格式化由 feature 层完成。 */
data class HistoryEntry(
    val opId: String,
    val entityType: EntityType,
    val entityId: String,
    val taskId: String,
    val taskTitle: String,
    val field: String,
    val operation: OpOperation,
    val oldValue: JsonElement?,
    val newValue: JsonElement,
    val atMs: Long,
    val actorId: String,
    val actorName: String,
    val source: OpSource,
    val model: String?,
    val action: String,
    val diffKind: String,
    val diffField: String,
    val diffOldText: String?,
    val diffNewText: String,
    val diffPrefix: String,
    val undoable: Boolean,
    val undone: Boolean,
) {
    val system: Boolean get() = source == OpSource.SYSTEM
}

/**
 * 历史时间线：日志只追加，撤销通过追加更高 HLC 的 `__undo_of` op 表达。
 * `undone` 判定：存在更高四元组的 `task.__undo_of` 指向该 opId。
 */
object Timeline {
    fun build(
        ops: List<Op>,
        workspaceId: String,
        taskId: String? = null,
        limit: Int = 100,
        currentUserId: String = "",
        memberNames: Map<String, String> = emptyMap(),
        taskTitles: Map<String, String> = emptyMap(),
    ): List<HistoryEntry> {
        if (workspaceId.isEmpty()) return emptyList()
        val state = MergeState.applyAll(ops)
        val undone = ops.asSequence()
            .filter { it.field == Fields.UNDO_OF }
            .mapNotNull { OpValues.stringOrNull(it.value) }
            .toSet()

        val lastValues = HashMap<MergeState.FieldKey, JsonElement>()
        val rows = ArrayList<HistoryEntry>()

        ops.sorted().forEach { op ->
            if (op.workspaceId != workspaceId) return@forEach
            if (taskId != null && (op.entityType != EntityType.TASK || op.entityId != taskId)) return@forEach
            if (op.entityType == EntityType.USER) return@forEach
            if (op.field == "__workspace") return@forEach

            val key = MergeState.FieldKey(op.entityType, op.entityId, op.field)
            val oldValue = lastValues.put(key, op.value)

            val title = taskTitles[op.entityId]
                ?: state.text(EntityType.WORKSPACE, op.entityId, Fields.NAME)
                ?: "工作区"

            val actorName = when {
                op.source == OpSource.AI -> "AI 助手"
                op.actorId == currentUserId -> "我"
                else -> memberNames[op.actorId] ?: op.actorId
            }

            val diffKind = when (op.operation) {
                OpOperation.ADD -> "add"
                OpOperation.REMOVE -> "remove"
                OpOperation.SET -> if (oldValue == null) "set" else "change"
            }
            val diffFieldLabel = when (op.field) {
                Fields.CREATED -> Fields.TITLE
                else -> op.field
            }
            val diffPrefix = when (op.operation) {
                OpOperation.ADD -> "$diffFieldLabel += "
                OpOperation.REMOVE -> "$diffFieldLabel -= "
                OpOperation.SET -> if (oldValue == null) "$diffFieldLabel = " else "$diffFieldLabel: "
            }
            val diffNewText = if (op.operation == OpOperation.SET) {
                displayValue(op.field, op.value)
            } else {
                OpValues.displayText(op.value)
            }
            val diffOldText = oldValue?.let { displayValue(op.field, it) }

            val undoable = op.operation == OpOperation.SET &&
                op.field != Fields.CREATED &&
                op.field != Fields.DELETED &&
                op.field != Fields.UNDO_OF &&
                op.source != OpSource.SYSTEM

            rows += HistoryEntry(
                opId = op.opId,
                entityType = op.entityType,
                entityId = op.entityId,
                taskId = if (op.entityType == EntityType.TASK) op.entityId else "",
                taskTitle = title,
                field = op.field,
                operation = op.operation,
                oldValue = oldValue,
                newValue = op.value,
                atMs = op.hlc.physicalMs,
                actorId = op.actorId,
                actorName = actorName,
                source = op.source,
                model = op.model,
                action = actionOf(op, state),
                diffKind = diffKind,
                diffField = op.field,
                diffOldText = diffOldText,
                diffNewText = diffNewText,
                diffPrefix = diffPrefix,
                undoable = undoable,
                undone = undone.contains(op.opId),
            )
        }

        return rows
            .sortedWith(compareByDescending<HistoryEntry> { it.atMs }.thenByDescending { it.opId })
            .take(limit)
    }

    private fun actionOf(op: Op, state: MergeState): String {
        if (op.field == Fields.CREATED) {
            return when (op.entityType) {
                EntityType.WORKSPACE -> "创建了工作区"
                EntityType.LIST -> "创建了清单"
                EntityType.TAG -> "创建了标签"
                else -> "创建了任务"
            }
        }
        if (op.field == Fields.DELETED) {
            return when (op.entityType) {
                EntityType.WORKSPACE -> "删除了工作区"
                EntityType.LIST -> "删除了清单"
                EntityType.TAG -> "删除了标签"
                else -> "删除了任务"
            }
        }
        if (op.field == Fields.UNDO_OF) return "生成了撤销 op"
        if (op.entityType == EntityType.MEMBERSHIP) {
            return when (op.field) {
                Fields.ROLE -> "变更了成员角色"
                Fields.REMOVED -> "移除了成员"
                else -> "更新了成员权限"
            }
        }
        if (op.entityType == EntityType.WORKSPACE) return "重命名了工作区"
        if (op.operation == OpOperation.ADD) {
            return when (op.field) {
                Fields.TAG -> "添加了标签"
                Fields.SUBTASK -> "添加了子任务"
                else -> "添加了内容"
            }
        }
        if (op.operation == OpOperation.REMOVE) {
            return when (op.field) {
                Fields.TAG -> "移除了标签"
                Fields.SUBTASK -> "删除了子任务"
                else -> "移除了内容"
            }
        }
        if (op.field.startsWith("__subtask:")) {
            if (op.field.endsWith(":done")) {
                return if (OpValues.bool(op.value)) "完成了子任务" else "恢复子任务"
            }
            if (op.field.endsWith(":text")) return "修改了子任务"
        }
        return when (op.field) {
            Fields.TITLE -> "修改了标题"
            Fields.NOTES -> "修改了备注"
            Fields.START_AT -> "修改了开始时间"
            Fields.DUE_AT -> "修改了截止时间"
            Fields.REMIND_AT -> "修改了提醒时间"
            Fields.PRIORITY -> "修改了优先级"
            Fields.STATUS -> if (state.text(EntityType.TASK, op.entityId, Fields.STATUS) == "done") {
                "完成了任务"
            } else {
                "恢复为待办"
            }
            Fields.LIST_ID -> "移动了清单"
            else -> "更新了任务"
        }
    }

    private fun displayValue(field: String, value: JsonElement): String {
        val text = OpValues.displayText(value)
        return when (field) {
            Fields.PRIORITY -> when (text) {
                "high" -> "高"
                "mid" -> "中"
                "low" -> "低"
                else -> text
            }
            Fields.STATUS -> when (text) {
                "done" -> "已完成"
                "todo" -> "未完成"
                else -> text
            }
            else -> text
        }
    }
}
