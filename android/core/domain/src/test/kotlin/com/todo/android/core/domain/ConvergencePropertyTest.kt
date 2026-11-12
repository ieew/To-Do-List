package com.todo.android.core.domain

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** 性质测试：收敛性、幂等性、平局确定性。 */
class ConvergencePropertyTest {
    private val ops = listOf(
        testOp("ws-created", EntityType.WORKSPACE, "ws-1", Fields.CREATED, str("2026-11-01T09:00:00+08:00"), 100),
        testOp("ws-name", EntityType.WORKSPACE, "ws-1", Fields.NAME, str("我的空间"), 101),
        testOp("member", EntityType.MEMBERSHIP, "ws-1:u-1", Fields.ROLE, str("owner"), 102),
        testOp("list-ws", EntityType.LIST, "list-1", Fields.WORKSPACE_ID, str("ws-1"), 103),
        testOp("task-created", EntityType.TASK, "task-1", Fields.CREATED, str("2026-11-01T10:00:00+08:00"), 200),
        testOp("task-list", EntityType.TASK, "task-1", Fields.LIST_ID, str("list-1"), 201),
        testOp("title-a", EntityType.TASK, "task-1", Fields.TITLE, str("A"), 300),
        testOp("title-b", EntityType.TASK, "task-1", Fields.TITLE, str("B"), 300, nodeId = "node-b"),
        testOp("tag-add", EntityType.TASK, "task-1", Fields.TAG, str("tag-1"), 400, OpOperation.ADD),
        testOp("tag-remove", EntityType.TASK, "task-1", Fields.TAG, str("tag-1"), 400, OpOperation.REMOVE, nodeId = "node-b"),
        testOp("sub-add", EntityType.TASK, "task-1", Fields.SUBTASK, str("s1"), 500, OpOperation.ADD),
        testOp("sub-text", EntityType.TASK, "task-1", Fields.subtaskText("s1"), str("写文档"), 501),
        testOp("done", EntityType.TASK, "task-1", Fields.STATUS, str("done"), 600),
    )

    @Test
    fun `任意排列的同一 op 集合收敛到同一投影`(): Unit = runBlocking {
        val baseline = Projection.build(ops, "ws-1", "u-1")
        checkAll(Arb.long()) { seed ->
            val shuffled = ops.shuffled(Random(seed))
            assertEquals(baseline, Projection.build(shuffled, "ws-1", "u-1"))
        }
    }

    @Test
    fun `重复投递同一批 op 结果不变`(): Unit = runBlocking {
        val baseline = Projection.build(ops, "ws-1", "u-1")
        checkAll(Arb.long()) { seed ->
            val duplicated = buildList {
                addAll(ops)
                addAll(ops.shuffled(Random(seed)))
            }
            assertEquals(baseline, Projection.build(duplicated, "ws-1", "u-1"))
        }
    }

    @Test
    fun `平局裁决与两端位置无关`(): Unit = runBlocking {
        val left = testOp("op-a", EntityType.TASK, "task-1", Fields.TITLE, str("A"), 1000)
        val right = testOp("op-b", EntityType.TASK, "task-1", Fields.TITLE, str("B"), 1000, nodeId = "node-b")
        checkAll(Arb.long()) { seed ->
            val first = if (seed % 2 == 0L) listOf(left, right) else listOf(right, left)
            val state = MergeState.applyAll(first)
            assertEquals(str("B"), state.raw(EntityType.TASK, "task-1", Fields.TITLE))
        }
    }
}
