package com.todo.android.core.ai

import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TodoList
import com.todo.android.core.domain.WorkspaceProjection
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务召回：只送相关任务、按时间接近度排序、条数可配置。 */
class TaskRetrieverTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 固定时钟（本地时间 2026-11-17 10:00），避免测试受真实时间影响。 */
    private val now: Long = Instant.ofEpochMilli(
        java.time.LocalDate.of(2026, 11, 17).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
    ).toEpochMilli()

    private fun afterDays(days: Long, hour: Int = 18): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .plusDays(days).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun task(
        id: String,
        title: String,
        dueMs: Long? = null,
        status: String = "todo",
        notes: String = "",
        priority: String = "mid",
        createdAtMs: Long = now,
    ): TaskItem = TaskItem(
        id = id,
        title = title,
        notes = notes,
        startAt = null,
        dueAt = null,
        remindAt = null,
        dueAtMs = dueMs,
        priority = priority,
        status = status,
        listId = "ws-work",
        tagIds = emptyList(),
        subtasks = emptyList(),
        source = OpSource.HUMAN,
        createdAt = null,
        createdAtMs = createdAtMs,
        completedAtMs = if (status == "done") now else null,
        order = 0.0,
        model = null,
    )

    private fun projection(tasks: List<TaskItem>): WorkspaceProjection = WorkspaceProjection(
        activeWorkspaceId = "ws",
        currentUserId = "user-1",
        workspaces = emptyList(),
        members = emptyList(),
        lists = listOf(TodoList("ws-work", "工作", "#000", 0.0, false)),
        tags = emptyList(),
        tasks = tasks,
    )

    @Test
    fun recallsAllRelevantAndSortsByDue() {
        val tasks = listOf(
            task("t-late", "写周报 终稿", afterDays(5)),
            task("t-soon", "写周报 初稿", afterDays(1)),
            task("t-unrelated", "买牛奶", afterDays(1)),
        )

        val result = TaskRetriever.retrieve(projection(tasks), "写周报", now)

        assertEquals(listOf("t-soon", "t-late"), result.tasks.map { it.id })
        assertFalse(result.usedFallback)
    }

    @Test
    fun excludesTasksWithoutAnyMatch() {
        val tasks = listOf(
            task("t1", "写周报", afterDays(1)),
            task("t2", "买牛奶", afterDays(1)),
            task("t3", "看图纸", afterDays(2)),
        )

        val result = TaskRetriever.retrieve(projection(tasks), "周报", now)

        assertEquals(listOf("t1"), result.tasks.map { it.id })
    }

    @Test
    fun respectsConfiguredLimit() {
        val tasks = (1..40).map { task("t$it", "排版任务 $it", afterDays(it.toLong())) }

        val result = TaskRetriever.retrieve(projection(tasks), "排版", now, limit = 20)

        assertEquals(20, result.tasks.size)
        // 时间最近的先
        assertEquals("t1", result.tasks.first().id)
        assertEquals(40, result.summary.openTotal)
    }

    @Test
    fun limitIsClampedToSafeRange() {
        val tasks = (1..10).map { task("t$it", "排版任务 $it", afterDays(it.toLong())) }

        assertEquals(5, TaskRetriever.retrieve(projection(tasks), "排版", now, limit = 1).tasks.size)
    }

    @Test
    fun fallsBackToUrgentTasksWhenNoKeywordMatches() {
        val tasks = listOf(
            task("overdue", "很久以前的任务", afterDays(-3)),
            task("high", "重要的事", afterDays(30), priority = "high"),
            task("far", "很久以后", afterDays(60)),
        )

        val result = TaskRetriever.retrieve(projection(tasks), "帮我规划今天", now)

        assertTrue(result.usedFallback)
        val ids = result.tasks.map { it.id }
        assertTrue("逾期任务应召回：$ids", ids.contains("overdue"))
        assertTrue("高优先级任务应召回：$ids", ids.contains("high"))
        assertFalse("远期普通任务不该召回：$ids", ids.contains("far"))
    }

    @Test
    fun tokenizeSplitsChineseBigramsAndAsciiWords() {
        val tokens = TaskRetriever.tokenize("MHC1500 排版 图")

        assertTrue(tokens.contains("mhc1500"))
        assertTrue(tokens.contains("排版"))
        assertTrue(tokens.contains("图"))
    }

    @Test
    fun summaryCountsOpenAndOverdue() {
        val tasks = listOf(
            task("a", "逾期的事", afterDays(-1)),
            task("b", "今天的事", afterDays(0)),
            task("c", "以后的事", afterDays(10)),
            task("d", "做完的事", afterDays(-1), status = "done"),
        )

        val summary = TaskRetriever.retrieve(projection(tasks), "事", now).summary

        assertEquals(3, summary.openTotal)
        assertEquals(1, summary.overdue)
        assertEquals(1, summary.dueToday)
        assertEquals(1, summary.doneTotal)
    }
}
