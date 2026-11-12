package com.todo.android.core.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三端一致性门禁：5 个共享测试向量逐一断言。 */
class TestVectorTest {
    @Test
    fun `lww 字段并发写按四元组取最大`() {
        val vector = Vectors.load("lww.json")
        val state = MergeState.applyAll(Vectors.ops(vector))
        Vectors.assertFields(state, vector.expect())
    }

    @Test
    fun `orset 并发 add 胜出且不同元素全部保留`() {
        val vector = Vectors.load("orset_add_wins.json")
        val state = MergeState.applyAll(Vectors.ops(vector))
        Vectors.assertCollections(state, vector.expect())
    }

    @Test
    fun `子任务字段走 LWW 且集合存活元素正确`() {
        val vector = Vectors.load("subtask_fields.json")
        val state = MergeState.applyAll(Vectors.ops(vector))
        Vectors.assertFields(state, vector.expect())
        Vectors.assertCollections(state, vector.expect())
    }

    @Test
    fun `标签删除后任务关联保留、标签投影隐藏`() {
        val vector = Vectors.load("tag_association.json")
        val state = MergeState.applyAll(Vectors.ops(vector))
        Vectors.assertCollections(state, vector.expect())
        Vectors.assertProjectionFlags(state, vector.expect())
    }

    @Test
    fun `快照恢复只恢复目标中存在的实体`() {
        val vector = Vectors.load("restore.json")
        val target = Vectors.restoreSnapshot(vector, "target")
        val current = Vectors.restoreSnapshot(vector, "current")

        val actual = RestorePlanner.plan(target, current)
        val expected = vector.expect().getValue("changes").jsonArray

        assertEquals("恢复意图变更条数不符合预期", expected.size, actual.size)
        expected.forEachIndexed { index, raw ->
            val row = raw.jsonArray
            val change = actual[index]
            assertEquals("第 $index 条 entityId", row[0].asText(), change.entityId)
            assertEquals("第 $index 条 operation", row[1].asText(), change.operation.wire)
            assertEquals("第 $index 条 field", row[2].asText(), change.field)
            assertEquals("第 $index 条 value", row[3], change.value)
        }
    }

    @Test
    fun `测试向量目录已从仓库根同步`() {
        val dir = java.io.File("src/test/resources/testvectors")
        val files = dir.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("测试向量未同步", files.size >= 5)
    }

    private fun JsonObject.expect(): JsonObject = getValue("expect").jsonObject

    private fun kotlinx.serialization.json.JsonElement.asText(): String = (this as JsonPrimitive).content
}
