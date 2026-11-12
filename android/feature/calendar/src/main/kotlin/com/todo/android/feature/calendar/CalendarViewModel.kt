package com.todo.android.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TaskScope
import com.todo.android.core.domain.TaskScoping
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CalendarUiState(
    val weekStart: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val days: List<CalendarDay> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val overdueCount: Int = 0,
    val allSelected: Boolean = false,
    /** 月度视图是否展开（下拉手势或点月份标题切换）。 */
    val expanded: Boolean = false,
    val monthLabel: String = "",
    val monthWeeks: List<List<MonthCell>> = emptyList(),
)

data class CalendarDay(
    val date: LocalDate,
    val count: Int,
    val hasOverdue: Boolean,
    val isToday: Boolean,
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val projectionHolder: ProjectionHolder,
) : ViewModel() {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val today: LocalDate = Instant.ofEpochMilli(TimeText.now()).atZone(zone).toLocalDate()
    private val selected = MutableStateFlow(today)
    private val all = MutableStateFlow(false)
    private val expanded = MutableStateFlow(false)

    val state: StateFlow<CalendarUiState> = combine(
        projectionHolder.projection,
        selected,
        all,
        expanded,
    ) { projection, selectedDate, allSelected, isExpanded ->
        val now = TimeText.now()
        val scope = if (allSelected) TaskScope.All else TaskScope.Today
        val scoped = TaskScoping.viewTasks(projection.tasks, scope, now)
        val weekStart = selectedDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val days = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            val dayTasks = scoped.filter { taskDueDate(it) == date }
            CalendarDay(
                date = date,
                count = dayTasks.count { !it.done },
                hasOverdue = dayTasks.any { task ->
                    val due = taskDueTime(task)
                    !task.done && due < now
                },
                isToday = date == today,
            )
        }
        val dayTasks = scoped
            .filter { taskDueDate(it) == selectedDate }
            .sortedBy { taskDueTime(it) }
        val overdue = scoped.count { task ->
            val due = task.dueAtMs
            !task.done && due != null && due < now
        }
        val month = YearMonth.from(selectedDate)
        CalendarUiState(
            weekStart = weekStart,
            selectedDate = selectedDate,
            days = days,
            tasks = dayTasks,
            overdueCount = overdue,
            allSelected = allSelected,
            expanded = isExpanded,
            monthLabel = "${month.year} 年 ${month.monthValue} 月",
            monthWeeks = MonthGrid.build(
                month = month,
                tasks = scoped,
                nowMs = now,
                today = today,
                zone = zone,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState(days = emptyWeek()))

    fun select(date: LocalDate) {
        selected.value = date
    }

    fun shiftWeek(days: Long) {
        selected.value = selected.value.plusDays(days)
    }

    /** 展开时箭头按月移动，收起时按周移动。 */
    fun shiftMonth(months: Long) {
        selected.value = selected.value.plusMonths(months)
    }

    fun selectScope(allSelected: Boolean) {
        all.value = allSelected
    }

    fun toggleExpanded() {
        expanded.value = !expanded.value
    }

    fun collapse() {
        expanded.value = false
    }

    /** 月视图里点某天：选中并收回周视图。 */
    fun selectFromMonth(date: LocalDate) {
        selected.value = date
        expanded.value = false
    }

    private fun taskDueDate(task: TaskItem): LocalDate? =
        task.dueAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    private fun taskDueTime(task: TaskItem): Long = task.dueAtMs ?: Long.MAX_VALUE

    private fun emptyWeek(): List<CalendarDay> {
        val start = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return (0..6).map { offset ->
            val date = start.plusDays(offset.toLong())
            CalendarDay(date = date, count = 0, hasOverdue = false, isToday = date == today)
        }
    }
}
