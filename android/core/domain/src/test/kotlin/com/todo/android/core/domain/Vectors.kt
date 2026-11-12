package com.todo.android.core.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals

/** 读取并解析 `src/test/resources/testvectors` 下的 JSON 测试向量。 */
internal object Vectors {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(name: String): JsonObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/testvectors/$name")) {
            "找不到测试向量 $name（应由 Gradle Copy 从 shared/testvectors 同步）"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    fun ops(root: JsonObject): List<Op> = root.getValue("ops").jsonArray.map(::toOp)

    fun toOp(element: JsonElement): Op {
        val obj = element.jsonObject
        val hlc = obj.getValue("hlc").jsonObject
        val model = obj["source_meta"]?.jsonObject?.get("model")
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive
            ?.content
        return Op(
            opId = obj.getValue("op_id").jsonPrimitive.content,
            workspaceId = obj.getValue("workspace_id").jsonPrimitive.content,
            entityType = requireNotNull(EntityType.fromWire(obj.getValue("entity_type").jsonPrimitive.content)),
            entityId = obj.getValue("entity_id").jsonPrimitive.content,
            actorId = obj.getValue("actor_id").jsonPrimitive.content,
            deviceId = obj.getValue("device_id").jsonPrimitive.content,
            operation = requireNotNull(OpOperation.fromWire(obj.getValue("operation").jsonPrimitive.content)),
            field = obj.getValue("field").jsonPrimitive.content,
            value = obj.getValue("value"),
            hlc = Hlc(
                physicalMs = hlc.getValue("physical_ms").jsonPrimitive.long,
                counter = hlc.getValue("counter").jsonPrimitive.long,
                nodeId = hlc.getValue("node_id").jsonPrimitive.content,
            ),
            source = OpSource.fromWire(obj.getValue("source").jsonPrimitive.content) ?: OpSource.HUMAN,
            model = model,
        )
    }

    fun assertFields(state: MergeState, expect: JsonObject) {
        val fields = expect["fields"]?.jsonObject ?: return
        fields.forEach { (rawKey, expected) ->
            val parts = rawKey.split(":", limit = 3)
            val entityType = requireNotNull(EntityType.fromWire(parts[0])) { "非法实体类型 ${parts[0]}" }
            assertEquals("字段 $rawKey 的合并结果不符合预期", expected, state.raw(entityType, parts[1], parts[2]))
        }
    }

    fun assertCollections(state: MergeState, expect: JsonObject) {
        val collections = expect["collections"]?.jsonObject ?: return
        collections.forEach { (rawKey, expected) ->
            val parts = rawKey.split(":", limit = 3)
            val entityType = requireNotNull(EntityType.fromWire(parts[0]))
            val expectedIds = expected.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                "集合 $rawKey 的存活元素不符合预期",
                expectedIds,
                state.elements(entityType, parts[1], parts[2]),
            )
        }
    }

    fun assertProjectionFlags(state: MergeState, expect: JsonObject) {
        val projection = expect["projection"]?.jsonObject ?: return
        projection["tags_empty"]?.jsonPrimitive?.booleanOrNull?.let { expectEmpty ->
            val aliveTags = state.entitiesOf(EntityType.TAG).filterNot { state.deleted(EntityType.TAG, it) }
            assertEquals("标签投影存活状态不符合预期", expectEmpty, aliveTags.isEmpty())
        }
        projection["task_tag_ids"]?.let { expected ->
            val expectedIds = (expected as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(
                "任务标签关联不符合预期",
                expectedIds,
                state.elements(EntityType.TASK, "task-1", Fields.TAG),
            )
        }
    }

    fun restoreChanges(expect: JsonObject): List<List<JsonElement>> =
        expect.getValue("changes").jsonArray.map { row -> row.jsonArray }

    fun restoreSnapshot(root: JsonObject, key: String): RestoreSnapshot {
        val obj = root.getValue(key).jsonObject
        val lists = obj["lists"]?.jsonArray.orEmpty().map { raw ->
            val list = raw.jsonObject
            RestoreList(
                id = list.getValue("id").jsonPrimitive.content,
                name = list.getValue("name").jsonPrimitive.content,
                color = list.getValue("color").jsonPrimitive.content,
                order = list.getValue("order").jsonPrimitive.content.toDouble(),
            )
        }
        val tasks = obj["tasks"]?.jsonArray.orEmpty().map { raw ->
            val task = raw.jsonObject
            RestoreTask(
                id = task.getValue("id").jsonPrimitive.content,
                title = task.getValue("title").jsonPrimitive.content,
                notes = task.getValue("notes").jsonPrimitive.content,
                priority = task.getValue("priority").jsonPrimitive.content,
                status = task.getValue("status").jsonPrimitive.content,
                listId = task.getValue("list_id").jsonPrimitive.content,
                order = task.getValue("order").jsonPrimitive.content.toDouble(),
                tagIds = task["tag_ids"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                subtasks = task["subtasks"]?.jsonArray.orEmpty().map { subtaskRaw ->
                    val subtask = subtaskRaw.jsonObject
                    RestoreSubtask(
                        id = subtask.getValue("id").jsonPrimitive.content,
                        text = subtask.getValue("text").jsonPrimitive.content,
                        done = subtask.getValue("done").jsonPrimitive.content.toBoolean(),
                        order = subtask.getValue("order").jsonPrimitive.content.toDouble(),
                    )
                },
            )
        }
        return RestoreSnapshot(lists = lists, tasks = tasks)
    }
}
