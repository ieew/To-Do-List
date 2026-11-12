package com.todo.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val ops = listOf(
        testOp("created", EntityType.TASK, "task-1", Fields.CREATED, str("2026-11-01T10:00:00+08:00"), 100),
        testOp("title-a", EntityType.TASK, "task-1", Fields.TITLE, str("A"), 200),
        testOp("title-b", EntityType.TASK, "task-1", Fields.TITLE, str("B"), 300),
        testOp("undo-b", EntityType.TASK, "task-1", Fields.UNDO_OF, str("title-b"), 400),
    )

    @Test
    fun `时间线按时间倒序并标记已撤销`() {
        val entries = Timeline.build(
            ops = ops,
            workspaceId = "ws-1",
            taskId = "task-1",
            currentUserId = "u-1",
            taskTitles = mapOf("task-1" to "写产品需求文档"),
        )

        assertEquals(listOf("undo-b", "title-b", "title-a", "created"), entries.map { it.opId })
        assertTrue("被撤销的 op 应标记 undone", entries.first { it.opId == "title-b" }.undone)
        assertFalse(entries.first { it.opId == "title-a" }.undone)
        assertEquals("写产品需求文档", entries.first().taskTitle)
    }

    @Test
    fun `diff 文本携带旧值和新值`() {
        val entries = Timeline.build(
            ops = ops,
            workspaceId = "ws-1",
            taskId = "task-1",
            currentUserId = "u-1",
            taskTitles = mapOf("task-1" to "写产品需求文档"),
        )
        val titleB = entries.first { it.opId == "title-b" }
        assertEquals("change", titleB.diffKind)
        assertEquals("A", titleB.diffOldText)
        assertEquals("B", titleB.diffNewText)
        assertEquals("title: ", titleB.diffPrefix)
        assertEquals("修改了标题", titleB.action)
        assertTrue(titleB.undoable)
    }

    @Test
    fun `创建 op 不可撤销且撤销 op 不重复撤销`() {
        val entries = Timeline.build(
            ops = ops,
            workspaceId = "ws-1",
            taskId = "task-1",
            currentUserId = "u-1",
        )
        assertFalse(entries.first { it.opId == "created" }.undoable)
        assertFalse(entries.first { it.opId == "undo-b" }.undoable)
    }

    @Test
    fun `AI 操作的 actor 显示为 AI 助手`() {
        val aiOps = listOf(
            testOp("created", EntityType.TASK, "task-1", Fields.CREATED, str("2026-11-01T10:00:00+08:00"), 100, source = OpSource.AI, model = "gpt-4o"),
            testOp("title", EntityType.TASK, "task-1", Fields.TITLE, str("AI 任务"), 200, source = OpSource.AI, model = "gpt-4o"),
        )
        val entries = Timeline.build(aiOps, "ws-1", "task-1", currentUserId = "u-1")
        assertTrue(entries.all { it.actorName == "AI 助手" })
        assertEquals("gpt-4o", entries.first().model)
    }

    @Test
    fun `过滤其他工作区与用户实体`() {
        val mixed = ops + listOf(
            testOp("other-ws", EntityType.TASK, "task-2", Fields.TITLE, str("别的"), 500, workspaceId = "ws-2"),
            testOp("user-name", EntityType.USER, "u-1", Fields.NAME, str("小明"), 600),
        )
        val entries = Timeline.build(mixed, "ws-1", null, currentUserId = "u-1")
        assertTrue(entries.none { it.opId == "other-ws" })
        assertTrue(entries.none { it.opId == "user-name" })
        assertEquals(4, entries.size)
    }

    @Test
    fun `limit 截断结果`() {
        val entries = Timeline.build(ops, "ws-1", null, limit = 2, currentUserId = "u-1")
        assertEquals(2, entries.size)
        assertEquals("undo-b", entries.first().opId)
    }
}
