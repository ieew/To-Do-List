package com.todo.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {
    private val workspaceId = "ws-1"
    private val userId = "u-1"

    private val baseOps = listOf(
        testOp("ws-created", EntityType.WORKSPACE, workspaceId, Fields.CREATED, str("2026-11-01T09:00:00+08:00"), 100, workspaceId = workspaceId),
        testOp("ws-name", EntityType.WORKSPACE, workspaceId, Fields.NAME, str("我的空间"), 101, workspaceId = workspaceId),
        testOp("member-role", EntityType.MEMBERSHIP, "$workspaceId:$userId", Fields.ROLE, str("owner"), 102, workspaceId = workspaceId),
        testOp("user-name", EntityType.USER, userId, Fields.NAME, str("小明"), 103, workspaceId = workspaceId),
        testOp("user-initial", EntityType.USER, userId, Fields.INITIAL, str("明"), 104, workspaceId = workspaceId),
        testOp("user-color", EntityType.USER, userId, Fields.COLOR, str("#3B82F6"), 105, workspaceId = workspaceId),
        testOp("user-email", EntityType.USER, userId, Fields.EMAIL, str("ming@todo.local"), 106, workspaceId = workspaceId),
        testOp("list-today-created", EntityType.LIST, "$workspaceId-today", Fields.CREATED, str("2026-11-01T09:00:00+08:00"), 107, workspaceId = workspaceId),
        testOp("list-today-ws", EntityType.LIST, "$workspaceId-today", Fields.WORKSPACE_ID, str(workspaceId), 108, workspaceId = workspaceId),
        testOp("list-today-name", EntityType.LIST, "$workspaceId-today", Fields.NAME, str("今天"), 109, workspaceId = workspaceId),
        testOp("list-today-order", EntityType.LIST, "$workspaceId-today", Fields.ORDER, num(0.0), 110, workspaceId = workspaceId),
        testOp("list-work-ws", EntityType.LIST, "list-work", Fields.WORKSPACE_ID, str(workspaceId), 111, workspaceId = workspaceId),
        testOp("list-work-name", EntityType.LIST, "list-work", Fields.NAME, str("工作"), 112, workspaceId = workspaceId),
        testOp("list-work-order", EntityType.LIST, "list-work", Fields.ORDER, num(1.0), 113, workspaceId = workspaceId),
        testOp("tag-created", EntityType.TAG, "tag-1", Fields.CREATED, str("2026-11-01T09:00:00+08:00"), 114, workspaceId = workspaceId),
        testOp("tag-ws", EntityType.TAG, "tag-1", Fields.WORKSPACE_ID, str(workspaceId), 115, workspaceId = workspaceId),
        testOp("tag-name", EntityType.TAG, "tag-1", Fields.NAME, str("重要"), 116, workspaceId = workspaceId),
        testOp("task-created", EntityType.TASK, "task-1", Fields.CREATED, str("2026-11-01T10:00:00+08:00"), 200, workspaceId = workspaceId, source = OpSource.AI, model = "gpt-4o"),
        testOp("task-list", EntityType.TASK, "task-1", Fields.LIST_ID, str("list-work"), 201, workspaceId = workspaceId),
        testOp("task-title", EntityType.TASK, "task-1", Fields.TITLE, str("写产品需求文档"), 202, workspaceId = workspaceId, source = OpSource.AI, model = "gpt-4o"),
        testOp("task-tag", EntityType.TASK, "task-1", Fields.TAG, str("tag-1"), 203, OpOperation.ADD, workspaceId = workspaceId),
        testOp("task-done", EntityType.TASK, "task-1", Fields.STATUS, str("done"), 204, workspaceId = workspaceId),
    )

    @Test
    fun `投影输出工作区、成员、清单、标签与任务`() {
        val projection = Projection.build(baseOps, workspaceId, userId)

        assertEquals(workspaceId, projection.activeWorkspaceId)
        assertEquals(listOf("我的空间"), projection.workspaces.map { it.name })
        assertEquals(listOf("owner"), projection.members.map { it.role })
        assertEquals("小明", projection.members.first().name)
        assertEquals(listOf("今天", "工作"), projection.lists.map { it.name })
        assertTrue(projection.lists.first().smart)
        assertEquals(listOf("重要"), projection.tags.map { it.name })

        val task = projection.tasks.single()
        assertEquals("写产品需求文档", task.title)
        assertEquals(listOf("tag-1"), task.tagIds)
        assertEquals(OpSource.AI, task.source)
        assertEquals("gpt-4o", task.model)
        assertEquals(204L, task.completedAtMs)
        assertEquals("2026-11-01T10:00:00+08:00", task.createdAt)
    }

    @Test
    fun `已删除标签仍保留任务关联`() {
        val ops = baseOps + testOp(
            "tag-deleted",
            EntityType.TAG,
            "tag-1",
            Fields.DELETED,
            bool(true),
            300,
            workspaceId = workspaceId,
        )
        val projection = Projection.build(ops, workspaceId, userId)
        assertTrue(projection.tags.isEmpty())
        assertEquals(listOf("tag-1"), projection.tasks.single().tagIds)
    }

    @Test
    fun `任务随所属清单过滤`() {
        val ops = baseOps + listOf(
            testOp("other-ws-created", EntityType.WORKSPACE, "ws-2", Fields.CREATED, str("2026-11-01T09:00:00+08:00"), 400, workspaceId = "ws-2"),
            testOp("other-ws-name", EntityType.WORKSPACE, "ws-2", Fields.NAME, str("另一个"), 401, workspaceId = "ws-2"),
            testOp("other-member", EntityType.MEMBERSHIP, "ws-2:$userId", Fields.ROLE, str("editor"), 402, workspaceId = "ws-2"),
            testOp("other-list-ws", EntityType.LIST, "list-other", Fields.WORKSPACE_ID, str("ws-2"), 403, workspaceId = "ws-2"),
            testOp("other-task-list", EntityType.TASK, "task-other", Fields.LIST_ID, str("list-other"), 404, workspaceId = "ws-2"),
            testOp("other-task-title", EntityType.TASK, "task-other", Fields.TITLE, str("别的任务"), 405, workspaceId = "ws-2"),
        )

        val projection = Projection.build(ops, workspaceId, userId)
        assertEquals(listOf("task-1"), projection.tasks.map { it.id })
        assertEquals(listOf("另一个", "我的空间"), projection.workspaces.map { it.name })
    }

    @Test
    fun `请求不存在的工作区时回落到第一个`() {
        val projection = Projection.build(baseOps, "ws-missing", userId)
        assertEquals(workspaceId, projection.activeWorkspaceId)
    }

    @Test
    fun `没有工作区时投影为空`() {
        val projection = Projection.build(emptyList(), null, userId)
        assertEquals("", projection.activeWorkspaceId)
        assertTrue(projection.tasks.isEmpty())
        assertNull(projection.activeWorkspace)
    }
}
