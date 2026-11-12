package com.todo.android.core.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal fun testOp(
    opId: String,
    entityType: EntityType,
    entityId: String,
    field: String,
    value: JsonElement,
    physicalMs: Long,
    operation: OpOperation = OpOperation.SET,
    counter: Long = 0,
    nodeId: String = "node-a",
    workspaceId: String = "ws-1",
    actorId: String = "u-1",
    deviceId: String = "dev-1",
    source: OpSource = OpSource.HUMAN,
    model: String? = null,
): Op = Op(
    opId = opId,
    workspaceId = workspaceId,
    entityType = entityType,
    entityId = entityId,
    actorId = actorId,
    deviceId = deviceId,
    operation = operation,
    field = field,
    value = value,
    hlc = Hlc(physicalMs = physicalMs, counter = counter, nodeId = nodeId),
    source = source,
    model = model,
)

internal fun str(value: String): JsonElement = JsonPrimitive(value)

internal fun bool(value: Boolean): JsonElement = JsonPrimitive(value)

internal fun num(value: Double): JsonElement = JsonPrimitive(value)
