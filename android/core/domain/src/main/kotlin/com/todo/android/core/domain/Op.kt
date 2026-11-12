package com.todo.android.core.domain

import kotlinx.serialization.json.JsonElement

/** 实体类型，取值与 `shared/protocol/op_envelope.schema.json` 一致。 */
enum class EntityType(val wire: String) {
    WORKSPACE("workspace"),
    LIST("list"),
    TAG("tag"),
    TASK("task"),
    MEMBERSHIP("membership"),
    USER("user"),
    ;

    companion object {
        fun fromWire(raw: String): EntityType? = entries.firstOrNull { it.wire == raw }
    }
}

/** 字段级操作类型。 */
enum class OpOperation(val wire: String) {
    SET("set"),
    ADD("add"),
    REMOVE("remove"),
    ;

    companion object {
        fun fromWire(raw: String): OpOperation? = entries.firstOrNull { it.wire == raw }
    }
}

/** 操作来源；`system` 仅服务端生成。 */
enum class OpSource(val wire: String) {
    HUMAN("human"),
    AI("ai"),
    IMPORT("import"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromWire(raw: String): OpSource? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 一条不可变的 op。写入只能经 `OpRepository.appendOps`，领域层只负责合并。
 *
 * 全序为 `(hlc, opId)`：先比 HLC 四元组中的前三项，再用 opId 打破平局。
 */
data class Op(
    val opId: String,
    val workspaceId: String,
    val entityType: EntityType,
    val entityId: String,
    val actorId: String,
    val deviceId: String,
    val operation: OpOperation,
    val field: String,
    val value: JsonElement,
    val hlc: Hlc,
    val source: OpSource,
    val model: String? = null,
) : Comparable<Op> {
    override fun compareTo(other: Op): Int {
        val byHlc = hlc.compareTo(other.hlc)
        if (byHlc != 0) return byHlc
        return opId.compareTo(other.opId)
    }
}

/** 字段名常量，避免散落的字符串字面量拼错。 */
object Fields {
    const val CREATED = "__created"
    const val DELETED = "__deleted"
    const val UNDO_OF = "__undo_of"
    const val WORKSPACE_ID = "workspace_id"
    const val NAME = "name"
    const val COLOR = "color"
    const val ORDER = "order"
    const val BG = "bg"
    const val FG = "fg"
    const val SHOW_IN_SIDEBAR = "show_in_sidebar"
    const val TITLE = "title"
    const val NOTES = "notes"
    const val START_AT = "start_at"
    const val DUE_AT = "due_at"
    const val REMIND_AT = "remind_at"
    const val PRIORITY = "priority"
    const val STATUS = "status"
    const val LIST_ID = "list_id"
    const val TAG = "__tag"
    const val SUBTASK = "__subtask"
    const val ROLE = "__role"
    const val REMOVED = "__removed"
    const val INITIAL = "initial"
    const val EMAIL = "email"

    fun subtaskField(subtaskId: String, field: String): String = "__subtask:$subtaskId:$field"

    fun subtaskText(subtaskId: String): String = subtaskField(subtaskId, "text")

    fun subtaskDone(subtaskId: String): String = subtaskField(subtaskId, "done")

    fun subtaskOrder(subtaskId: String): String = subtaskField(subtaskId, "order")
}
