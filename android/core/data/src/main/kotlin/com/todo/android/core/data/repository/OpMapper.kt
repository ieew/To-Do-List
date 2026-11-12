package com.todo.android.core.data.repository

import com.todo.android.core.data.db.OpLogEntity
import com.todo.android.core.data.db.OpSyncState
import com.todo.android.core.domain.EntityType
import com.todo.android.core.domain.Hlc
import com.todo.android.core.domain.Op
import com.todo.android.core.domain.OpOperation
import com.todo.android.core.domain.OpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 领域 op ↔ 数据库行。只做字段搬运，不改写值。 */
internal object OpMapper {
    fun toEntity(
        op: Op,
        createdAt: Long,
        syncState: String = OpSyncState.PENDING,
        serverSeq: Long? = null,
        syncError: String? = null,
    ): OpLogEntity = OpLogEntity(
        opId = op.opId,
        workspaceId = op.workspaceId,
        entityType = op.entityType.wire,
        entityId = op.entityId,
        actorId = op.actorId,
        deviceId = op.deviceId,
        operation = op.operation.wire,
        field = op.field,
        valueJson = op.value.toString(),
        physicalMs = op.hlc.physicalMs,
        counter = op.hlc.counter,
        nodeId = op.hlc.nodeId,
        source = op.source.wire,
        model = op.model,
        createdAt = createdAt,
        syncState = syncState,
        serverSeq = serverSeq,
        syncError = syncError,
    )

    fun toDomain(entity: OpLogEntity): Op? {
        val entityType = EntityType.fromWire(entity.entityType) ?: return null
        val operation = OpOperation.fromWire(entity.operation) ?: return null
        return Op(
            opId = entity.opId,
            workspaceId = entity.workspaceId,
            entityType = entityType,
            entityId = entity.entityId,
            actorId = entity.actorId,
            deviceId = entity.deviceId,
            operation = operation,
            field = entity.field,
            value = decodeValue(entity.valueJson),
            hlc = Hlc(entity.physicalMs, entity.counter, entity.nodeId),
            source = OpSource.fromWire(entity.source) ?: OpSource.HUMAN,
            model = entity.model,
        )
    }

    private fun decodeValue(raw: String): JsonElement = runCatching {
        Json.parseToJsonElement(raw)
    }.getOrElse {
        kotlinx.serialization.json.JsonPrimitive(raw)
    }
}
