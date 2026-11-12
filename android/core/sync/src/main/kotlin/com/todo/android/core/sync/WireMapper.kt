package com.todo.android.core.sync

import com.todo.android.core.domain.Hlc
import com.todo.android.core.domain.Op
import com.todo.android.core.network.wire.WireHlc
import com.todo.android.core.network.wire.WireOpIn
import com.todo.android.core.network.wire.WireOpOut
import com.todo.android.core.network.wire.WireSourceMeta

/**
 * 领域 op ↔ wire。只做字段改名与嵌套，不改写值、不补默认值、不裁剪字段。
 * `model` 落在 `source_meta.model`；`prompt_hash` 固定为 null。
 */
object WireMapper {
    fun toOut(op: Op): WireOpOut = WireOpOut(
        opId = op.opId,
        workspaceId = op.workspaceId,
        entityType = op.entityType.wire,
        entityId = op.entityId,
        actorId = op.actorId,
        deviceId = op.deviceId,
        operation = op.operation.wire,
        field = op.field,
        value = op.value,
        hlc = WireHlc(op.hlc.physicalMs, op.hlc.counter, op.hlc.nodeId),
        source = op.source.wire,
        sourceMeta = WireSourceMeta(model = op.model, promptHash = null),
    )

    fun toDomain(wire: WireOpIn): Op? {
        val entityType = com.todo.android.core.domain.EntityType.fromWire(wire.entityType) ?: return null
        val operation = com.todo.android.core.domain.OpOperation.fromWire(wire.operation) ?: return null
        val source = com.todo.android.core.domain.OpSource.fromWire(wire.source)
            ?: com.todo.android.core.domain.OpSource.HUMAN
        return Op(
            opId = wire.opId,
            workspaceId = wire.workspaceId,
            entityType = entityType,
            entityId = wire.entityId,
            actorId = wire.actorId,
            deviceId = wire.deviceId,
            operation = operation,
            field = wire.field,
            value = wire.value,
            hlc = Hlc(wire.hlc.physicalMs, wire.hlc.counter, wire.hlc.nodeId),
            source = source,
            model = wire.sourceMeta?.model,
        )
    }

    fun toDomain(wire: WireHlc): Hlc = Hlc(wire.physicalMs, wire.counter, wire.nodeId)
}
