package com.todo.android.feature.calendar

import com.todo.android.core.domain.TaskItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 月视图里的一个日格；`inMonth=false` 表示跨月补位。 */
data class MonthCell(
    val date: LocalDate,
    val count: Int,
    val hasOverdue: Boolean,
    val isToday: Boolean,
    val inMonth: Boolean,
)

/**
 * 月视图网格：固定 6 周 × 7 天，周一起始。
 * 纯函数（时间与时区由调用方注入），放在 UI 层是因为用到 java.time；
 * core/domain 的架构门禁禁止读时钟。
 */
object MonthGrid {
    const val WEEKS = 6
    const val DAYS = 7

    fun build(
        month: YearMonth,
        tasks: List<TaskItem>,
        nowMs: Long,
        today: LocalDate,
        zone: ZoneId,
    ): List<List<MonthCell>> {
        val firstOfMonth = month.atDay(1)
        val gridStart = firstOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val byDate = HashMap<LocalDate, MutableList<TaskItem>>()
        tasks.forEach { task ->
            val dueMs = task.dueAtMs ?: return@forEach
            val date = Instant.ofEpochMilli(dueMs).atZone(zone).toLocalDate()
            byDate.getOrPut(date) { mutableListOf() }.add(task)
        }

        return (0 until WEEKS).map { week ->
            (0 until DAYS).map { offset ->
                val date = gridStart.plusDays((week * DAYS + offset).toLong())
                val dayTasks = byDate[date].orEmpty()
                MonthCell(
                    date = date,
                    count = dayTasks.count { !it.done },
                    hasOverdue = dayTasks.any { task ->
                        !task.done && (task.dueAtMs ?: Long.MAX_VALUE) < nowMs
                    },
                    isToday = date == today,
                    inMonth = YearMonth.from(date) == month,
                )
            }
        }
    }
}
