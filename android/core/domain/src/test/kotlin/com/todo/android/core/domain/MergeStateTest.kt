package com.todo.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MergeStateTest {
    @Test
    fun `标量字段取四元组最大的 op`() {
        val ops = listOf(
            testOp("a", EntityType.TASK, "t1", Fields.TITLE, str("A"), 1000),
            testOp("b", EntityType.TASK, "t1", Fields.TITLE, str("B"), 2000, nodeId = "node-b"),
            testOp("c", EntityType.TASK, "t1", Fields.TITLE, str("C"), 2000, counter = 1),
        )
        val state = MergeState.applyAll(ops)
        assertEquals(str("C"), state.raw(EntityType.TASK, "t1", Fields.TITLE))
    }

    @Test
    fun `并发 add 与 remove 时 add 胜出`() {
        val ops = listOf(
            testOp("a", EntityType.TASK, "t1", Fields.TAG, str("tag-1"), 3000, OpOperation.ADD),
            testOp("r", EntityType.TASK, "t1", Fields.TAG, str("tag-1"), 2000, OpOperation.REMOVE),
        )
        val state = MergeState.applyAll(ops)
        assertEquals(listOf("tag-1"), state.elements(EntityType.TASK, "t1", Fields.TAG))
    }

    @Test
    fun `remove 晚于 add 时元素被移除`() {
        val ops = listOf(
            testOp("a", EntityType.TASK, "t1", Fields.TAG, str("tag-1"), 1000, OpOperation.ADD),
            testOp("r", EntityType.TASK, "t1", Fields.TAG, str("tag-1"), 2000, OpOperation.REMOVE),
        )
        val state = MergeState.applyAll(ops)
        assertTrue(state.elements(EntityType.TASK, "t1", Fields.TAG).isEmpty())
    }

    @Test
    fun `子任务字段各自独立 LWW`() {
        val ops = listOf(
            testOp("a", EntityType.TASK, "t1", Fields.subtaskText("s1"), str("写文档"), 1000),
            testOp("b", EntityType.TASK, "t1", Fields.subtaskText("s1"), str("写设计文档"), 2000),
            testOp("c", EntityType.TASK, "t1", Fields.subtaskDone("s1"), bool(true), 1500),
        )
        val state = MergeState.applyAll(ops)
        assertEquals("写设计文档", state.text(EntityType.TASK, "t1", Fields.subtaskText("s1")))
        assertTrue(state.bool(EntityType.TASK, "t1", Fields.subtaskDone("s1")))
    }

    @Test
    fun `墓碑字段生效`() {
        val state = MergeState.applyAll(
            listOf(testOp("d", EntityType.TASK, "t1", Fields.DELETED, bool(true), 1000)),
        )
        assertTrue(state.deleted(EntityType.TASK, "t1"))
        assertFalse(state.deleted(EntityType.TASK, "t2"))
    }

    @Test
    fun `任意输入顺序得到相同状态`() {
        val ops = buildList {
            add(testOp("created", EntityType.TASK, "t1", Fields.CREATED, str("2026-11-01T09:00:00+08:00"), 1000))
            add(testOp("title-a", EntityType.TASK, "t1", Fields.TITLE, str("A"), 2000))
            add(testOp("title-b", EntityType.TASK, "t1", Fields.TITLE, str("B"), 2000, nodeId = "node-b"))
            add(testOp("tag-a", EntityType.TASK, "t1", Fields.TAG, str("tag-1"), 3000, OpOperation.ADD))
            add(testOp("tag-r", EntityType.TASK, "t1", Fields.TAG, str("tag-1"), 2500, OpOperation.REMOVE))
            repeat(10) { index ->
                add(testOp("sub-$index", EntityType.TASK, "t1", Fields.subtaskOrder("s1"), num(index.toDouble()), 4000L + index))
            }
        }
        val baseline = MergeState.applyAll(ops)
        val expected = baseline.raw(EntityType.TASK, "t1", Fields.subtaskOrder("s1"))

        repeat(30) { seed ->
            val shuffled = ops.shuffled(Random(seed))
            val state = MergeState.applyAll(shuffled)
            assertEquals(expected, state.raw(EntityType.TASK, "t1", Fields.subtaskOrder("s1")))
            assertEquals(
                baseline.elements(EntityType.TASK, "t1", Fields.TAG),
                state.elements(EntityType.TASK, "t1", Fields.TAG),
            )
        }
    }
}
