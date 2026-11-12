package com.todo.android.feature.calendar

import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.TaskItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthGridTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val november = YearMonth.of(2026, 11)

    private fun ms(year: Int, month: Int, day: Int, hour: Int = 9): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun task(id: String, dueMs: Long?, done: Boolean = false) = TaskItem(
        id = id,
        title = id,
        notes = "",
        startAt = null,
        dueAt = null,
        remindAt = null,
        dueAtMs = dueMs,
        priority = "mid",
        status = if (done) "done" else "todo",
        listId = "list-today",
        tagIds = emptyList(),
        subtasks = emptyList(),
        source = OpSource.HUMAN,
        createdAt = null,
        createdAtMs = 0L,
        completedAtMs = null,
        order = 0.0,
        model = null,
    )

    @Test
    fun `月网格固定为 6 周 7 天且周一起始`() {
        val grid = MonthGrid.build(
            month = november,
            tasks = emptyList(),
            nowMs = ms(2026, 11, 12),
            today = LocalDate.of(2026, 11, 12),
            zone = zone,
        )

        assertEquals(MonthGrid.WEEKS, grid.size)
        grid.forEach { week -> assertEquals(MonthGrid.DAYS, week.size) }
        // 2026-11-01 是周日，所以网格从 2026-10-26（周一）起。
        assertEquals(LocalDate.of(2026, 10, 26), grid.first().first().date)
        assertEquals(LocalDate.of(2026, 12, 6), grid.last().last().date)
    }

    @Test
    fun `跨月补位在月外的格子里被标记`() {
        val grid = MonthGrid.build(
            month = november,
            tasks = emptyList(),
            nowMs = ms(2026, 11, 12),
            today = LocalDate.of(2026, 11, 12),
            zone = zone,
        )
        val firstWeek = grid.first()

        assertEquals(6, firstWeek.count { !it.inMonth })
        assertTrue(firstWeek.last().inMonth)
        assertEquals(LocalDate.of(2026, 11, 1), firstWeek.last().date)

        val today = grid.flatten().single { it.isToday }
        assertEquals(LocalDate.of(2026, 11, 12), today.date)
        assertTrue(today.inMonth)
    }

    @Test
    fun `计数只统计未完成且逾期单独标记`() {
        val grid = MonthGrid.build(
            month = november,
            tasks = listOf(
                task("overdue", ms(2026, 11, 5)),
                task("done", ms(2026, 11, 5), done = true),
                task("future", ms(2026, 11, 20)),
                task("no-due", null),
            ),
            nowMs = ms(2026, 11, 12),
            today = LocalDate.of(2026, 11, 12),
            zone = zone,
        )
        val cells = grid.flatten().associateBy { it.date }

        val fifth = cells.getValue(LocalDate.of(2026, 11, 5))
        assertEquals(1, fifth.count)
        assertTrue(fifth.hasOverdue)

        val twentieth = cells.getValue(LocalDate.of(2026, 11, 20))
        assertEquals(1, twentieth.count)
        assertFalse(twentieth.hasOverdue)

        val ninth = cells.getValue(LocalDate.of(2026, 11, 9))
        assertEquals(0, ninth.count)
        assertFalse(ninth.hasOverdue)
    }
}
