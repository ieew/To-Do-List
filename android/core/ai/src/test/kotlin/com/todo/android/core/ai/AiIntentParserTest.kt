package com.todo.android.core.ai

import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.SubtaskItem
import com.todo.android.core.domain.TagItem
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TodoList
import com.todo.android.core.domain.WorkspaceItem
import com.todo.android.core.domain.WorkspaceProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 意图解析：模型输出不可靠，解析层必须只放行合法操作。 */
class AiIntentParserTest {

    private val now = 1_763_000_000_000L // 2025-11-11 前后，足够做相对时间断言

    private fun projection(): WorkspaceProjection = WorkspaceProjection(
        activeWorkspaceId = "ws",
        currentUserId = "user-1",
        workspaces = listOf(WorkspaceItem("ws", "工作区")),
        members = emptyList(),
        lists = listOf(
            TodoList("ws-today", "今天", "#3B82F6", 0.0, true),
            TodoList("ws-work", "工作", "#F59E0B", 1.0, false),
        ),
        tags = listOf(TagItem("ws-tag-work", "工作", "#EFF6FF", "#2563EB", true)),
        tasks = listOf(
            TaskItem(
                id = "task-1",
                title = "写周报",
                notes = "",
                startAt = null,
                dueAt = "2026-11-21T10:00:00+08:00",
                remindAt = null,
                priority = "mid",
                status = "todo",
                listId = "ws-work",
                tagIds = emptyList(),
                subtasks = listOf(SubtaskItem("sub-1", "收集数据", false, 0.0)),
                source = OpSource.HUMAN,
                createdAt = null,
                createdAtMs = 0,
                completedAtMs = null,
                order = 0.0,
                model = null,
            ),
        ),
    )

    @Test
    fun createTaskResolvesListAndTagsByName() {
        val text = """
            {"message":"加一条",
             "operations":[{"op":"create_task","title":"准备评审","list":"工作","tags":["工作","不存在"],
             "priority":"high","due_at":"2026-11-20T18:00:00+08:00","subtasks":["写材料"]}]}
        """.trimIndent()

        val plan = AiIntentParser.parse(text, projection(), now)

        assertEquals(1, plan.operations.size)
        val create = plan.operations.first() as AiOperation.CreateTask
        assertEquals("准备评审", create.title)
        assertEquals("ws-work", create.listId)
        assertEquals(listOf("ws-tag-work"), create.tagIds)
        assertEquals(listOf("写材料"), create.subtasks)
        assertEquals("high", create.priority)
        assertTrue("未知标签应给出提示", plan.warnings.any { it.contains("不存在") })
    }

    @Test
    fun legacyTasksArrayIsTreatedAsCreate() {
        val plan = AiIntentParser.parse("""{"tasks":[{"title":"旧格式任务"}]}""", projection(), now)

        assertEquals(1, plan.operations.size)
        val create = plan.operations.first() as AiOperation.CreateTask
        assertEquals("旧格式任务", create.title)
        assertEquals("ws-today", create.listId)
        assertTrue("缺失截止时间应补默认值", create.dueAtIso.isNotBlank())
    }

    @Test
    fun updateFieldsDropsUnknownFieldAndBadTime() {
        val text = """
            {"operations":[{"op":"update_fields","task_id":"task-1",
             "patch":{"priority":"high","due_at":"不是时间","secret":"x"}}]}
        """.trimIndent()

        val plan = AiIntentParser.parse(text, projection(), now)

        val update = plan.operations.single() as AiOperation.UpdateFields
        assertEquals(setOf("priority"), update.patch.keys)
        assertEquals("high", update.patch["priority"])
        assertTrue(plan.warnings.any { it.contains("secret") })
        assertTrue(plan.warnings.any { it.contains("due_at") })
    }

    @Test
    fun unknownTaskIdIsIgnored() {
        val plan = AiIntentParser.parse(
            """{"operations":[{"op":"delete_task","task_id":"task-404"}]}""",
            projection(),
            now,
        )

        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.warnings.any { it.contains("task-404") })
    }

    @Test
    fun deleteAndCompleteAreMarkedDangerous() {
        val plan = AiIntentParser.parse(
            """
            {"operations":[
              {"op":"delete_task","task_id":"task-1"},
              {"op":"set_status","task_id":"task-1","status":"done"}
            ]}
            """.trimIndent(),
            projection(),
            now,
        )

        assertEquals(2, plan.operations.size)
        assertTrue(plan.hasDangerous)
        assertTrue(plan.operations.all { it.dangerous })
    }

    @Test
    fun setSubtaskDoneRequiresKnownSubtask() {
        val okPlan = AiIntentParser.parse(
            """{"operations":[{"op":"set_subtask_done","task_id":"task-1","subtask_id":"sub-1","done":true}]}""",
            projection(),
            now,
        )
        val op = okPlan.operations.single() as AiOperation.SetSubtaskDone
        assertEquals("收集数据", op.subtaskText)
        assertTrue(op.done)

        val badPlan = AiIntentParser.parse(
            """{"operations":[{"op":"set_subtask_done","task_id":"task-1","subtask_id":"sub-9"}]}""",
            projection(),
            now,
        )
        assertTrue(badPlan.operations.isEmpty())
        assertFalse(badPlan.hasDangerous)
    }

    @Test
    fun invalidStatusAndUnknownOpAreReported() {
        val plan = AiIntentParser.parse(
            """
            {"operations":[
              {"op":"set_status","task_id":"task-1","status":"blocked"},
              {"op":"reorder_task","task_id":"task-1"}
            ]}
            """.trimIndent(),
            projection(),
            now,
        )

        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.warnings.any { it.contains("blocked") })
        assertTrue(plan.warnings.any { it.contains("reorder_task") })
    }

    @Test
    fun brokenJsonReturnsWarningInsteadOfCrash() {
        val plan = AiIntentParser.parse("模型今天不想输出 JSON", projection(), now)

        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.warnings.isNotEmpty())
    }
}
