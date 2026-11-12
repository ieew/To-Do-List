package com.todo.android.core.domain

import kotlinx.serialization.json.JsonElement

/**
 * 字段级 reducer 状态：标量字段 LWW，集合字段 OR-Set（add-wins）。
 *
 * 只依赖传入 op 的全序，不读时钟；相同 op 集合在任意输入顺序下产出相同状态。
 */
class MergeState private constructor() {
    internal data class FieldKey(val entityType: EntityType, val entityId: String, val field: String)

    internal data class EntityKey(val entityType: EntityType, val entityId: String)

    internal class Cell(
        val value: JsonElement,
        val hlc: Hlc,
        val opId: String,
    )

    internal class ElementState {
        var addHlc: Hlc? = null
        var addOpId: String? = null
        var removeHlc: Hlc? = null
        var removeOpId: String? = null

        val alive: Boolean
            get() {
                val add = addHlc ?: return false
                val remove = removeHlc ?: return true
                val byHlc = add.compareTo(remove)
                if (byHlc != 0) return byHlc > 0
                return (addOpId ?: "").compareTo(removeOpId ?: "") >= 0
            }
    }

    internal class EntityMeta(
        var firstMs: Long,
        var source: OpSource,
        var model: String?,
    )

    internal val fields: MutableMap<FieldKey, Cell> = HashMap()
    internal val collections: MutableMap<FieldKey, MutableMap<String, ElementState>> = HashMap()
    internal val meta: MutableMap<EntityKey, EntityMeta> = HashMap()
    internal val order: MutableList<Op> = ArrayList()

    private fun apply(op: Op) {
        touchMeta(op)

        when (op.operation) {
            OpOperation.ADD, OpOperation.REMOVE -> applyCollection(op)
            OpOperation.SET -> applyField(op)
        }

        order.add(op)
    }

    private fun touchMeta(op: Op) {
        val key = EntityKey(op.entityType, op.entityId)
        val existing = meta[key]
        if (existing == null) {
            meta[key] = EntityMeta(op.hlc.physicalMs, op.source, op.model)
        } else {
            if (op.hlc.physicalMs < existing.firstMs) existing.firstMs = op.hlc.physicalMs
            if (op.field == Fields.CREATED) {
                existing.source = op.source
                existing.model = op.model
            }
        }
    }

    private fun applyCollection(op: Op) {
        val key = FieldKey(op.entityType, op.entityId, op.field)
        val bucket = collections.getOrPut(key) { HashMap() }
        val elementId = OpValues.elementId(op.value)
        val element = bucket.getOrPut(elementId) { ElementState() }
        if (op.operation == OpOperation.ADD) {
            val existingHlc = element.addHlc
            val existingOpId = element.addOpId
            if (existingHlc == null || op.hlc > existingHlc || (op.hlc == existingHlc && op.opId > existingOpId.orEmpty())) {
                element.addHlc = op.hlc
                element.addOpId = op.opId
            }
        } else {
            val existingHlc = element.removeHlc
            val existingOpId = element.removeOpId
            if (existingHlc == null || op.hlc > existingHlc || (op.hlc == existingHlc && op.opId > existingOpId.orEmpty())) {
                element.removeHlc = op.hlc
                element.removeOpId = op.opId
            }
        }
    }

    private fun applyField(op: Op) {
        val key = FieldKey(op.entityType, op.entityId, op.field)
        val existing = fields[key]
        val shouldReplace = existing == null ||
            op.hlc > existing.hlc ||
            (op.hlc == existing.hlc && op.opId > existing.opId)
        if (shouldReplace) {
            fields[key] = Cell(op.value, op.hlc, op.opId)
        }
    }

    internal fun raw(entityType: EntityType, entityId: String, field: String): JsonElement? =
        fields[FieldKey(entityType, entityId, field)]?.value

    internal fun text(entityType: EntityType, entityId: String, field: String): String? =
        raw(entityType, entityId, field).let { OpValues.stringOrNull(it ?: return null) }

    internal fun bool(entityType: EntityType, entityId: String, field: String): Boolean =
        raw(entityType, entityId, field)?.let(OpValues::bool) ?: false

    internal fun number(entityType: EntityType, entityId: String, field: String, fallback: Double): Double =
        raw(entityType, entityId, field)?.let { OpValues.double(it, fallback) } ?: fallback

    internal fun deleted(entityType: EntityType, entityId: String): Boolean =
        bool(entityType, entityId, Fields.DELETED)

    internal fun fieldHlc(entityType: EntityType, entityId: String, field: String): Hlc? =
        fields[FieldKey(entityType, entityId, field)]?.hlc

    /** 集合存活元素，按元素 id 排序保证投影确定性。 */
    internal fun elements(entityType: EntityType, entityId: String, field: String): List<String> =
        collections[FieldKey(entityType, entityId, field)]
            ?.filterValues { it.alive }
            ?.keys
            ?.sorted()
            .orEmpty()

    internal fun metaOf(entityType: EntityType, entityId: String): EntityMeta? =
        meta[EntityKey(entityType, entityId)]

    internal fun entitiesOf(entityType: EntityType): List<String> =
        meta.keys.filter { it.entityType == entityType }.map { it.entityId }.distinct().sorted()

    companion object {
        fun empty(): MergeState = MergeState()

        /** 必须先按 (hlc, opId) 全序排序再逐条 apply；禁止依赖输入顺序。 */
        fun applyAll(ops: List<Op>): MergeState {
            val state = MergeState()
            ops.sorted().forEach(state::apply)
            return state
        }
    }
}
