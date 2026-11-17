package com.todo.android.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TaskScope
import com.todo.android.core.domain.TaskScoping
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 统计时间范围：按 `due_at` 归属，与 Windows 端 `statsRange` 一致。
 * 没有截止时间的任务只在「全部」范围里出现。
 */
enum class StatsRange(val label: String) {
    TODAY("今天"),
    WEEK("本周"),
    MONTH("本月"),
    LAST30("近 30 天"),
    ALL("全部"),
}

data class ListDistribution(
    val name: String,
    val count: Int,
    val color: String,
)

data class StatsUiState(
    val range: StatsRange = StatsRange.WEEK,
    val scopedTotal: Int = 0,
    val completed: Int = 0,
    val active: Int = 0,
    val overdue: Int = 0,
    val aiCreated: Int = 0,
    val percent: Int = 0,
    val high: Int = 0,
    val mid: Int = 0,
    val low: Int = 0,
    val listDistribution: List<ListDistribution> = emptyList(),
    val lastSevenDays: List<Float> = List(7) { 0f },
    val todayIndex: Int = 6,
    val averagePerDay: String = "0",
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val projectionHolder: ProjectionHolder,
) : ViewModel() {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val range = MutableStateFlow(StatsRange.WEEK)

    val state: StateFlow<StatsUiState> = combine(projectionHolder.projection, range) { projection, selectedRange ->
        val now = TimeText.now()
        val tasks = tasksInRange(
            TaskScoping.viewTasks(projection.tasks, TaskScope.All, now),
            selectedRange,
            now,
        )
        val completed = tasks.count { it.done }
        val active = tasks.count { !it.done }
        val overdue = tasks.count { task ->
            val due = task.dueAtMs
            !task.done && due != null && due < now
        }
        val aiCreated = tasks.count { it.source == OpSource.AI }
        val total = tasks.size
        val percent = if (total == 0) 0 else completed * 100 / total

        val lists = projection.lists.map { list ->
            ListDistribution(
                name = list.name,
                count = tasks.count { it.listId == list.id },
                color = list.color,
            )
        }.filter { it.count > 0 }

        val trend = lastSevenDays(tasks)

        StatsUiState(
            range = selectedRange,
            scopedTotal = total,
            completed = completed,
            active = active,
            overdue = overdue,
            aiCreated = aiCreated,
            percent = percent,
            high = tasks.count { it.priority == "high" },
            mid = tasks.count { it.priority == "mid" },
            low = tasks.count { it.priority == "low" },
            listDistribution = lists,
            lastSevenDays = trend,
            todayIndex = 6,
            averagePerDay = String.format("%.1f", trend.sum() / 7f),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun selectRange(value: StatsRange) {
        range.value = value
    }

    /** 按当前范围裁剪任务集合：`ALL` 不过滤，其余按 `due_at` 落在区间内。 */
    private fun tasksInRange(tasks: List<TaskItem>, range: StatsRange, now: Long): List<TaskItem> {
        if (range == StatsRange.ALL) return tasks
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = when (range) {
            StatsRange.TODAY -> today
            // 周一为一周起点（中文习惯）
            StatsRange.WEEK -> today.minusDays((today.dayOfWeek.value - 1).toLong())
            StatsRange.MONTH -> today.withDayOfMonth(1)
            StatsRange.LAST30 -> today.minusDays(29)
            StatsRange.ALL -> return tasks
        }
        val end = when (range) {
            StatsRange.TODAY -> today
            StatsRange.WEEK -> start.plusDays(6)
            StatsRange.MONTH -> today.withDayOfMonth(today.lengthOfMonth())
            StatsRange.LAST30 -> today
            StatsRange.ALL -> today
        }
        return tasks.filter { task ->
            val due = task.dueAtMs ?: return@filter false
            val date = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            !date.isBefore(start) && !date.isAfter(end)
        }
    }

    private fun lastSevenDays(tasks: List<TaskItem>): List<Float> {
        val today = Instant.ofEpochMilli(TimeText.now()).atZone(zone).toLocalDate()
        return (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            tasks.count { task -> task.done && completedDate(task) == date }.toFloat()
        }
    }

    private fun completedDate(task: TaskItem): LocalDate? =
        task.completedAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
