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

data class ListDistribution(
    val name: String,
    val count: Int,
    val color: String,
)

data class StatsUiState(
    val allSelected: Boolean = false,
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
    private val all = MutableStateFlow(false)

    val state: StateFlow<StatsUiState> = combine(projectionHolder.projection, all) { projection, allSelected ->
        val now = TimeText.now()
        val scope = if (allSelected) TaskScope.All else TaskScope.Today
        val tasks = TaskScoping.viewTasks(projection.tasks, scope, now)
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

        StatsUiState(
            allSelected = allSelected,
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
            lastSevenDays = lastSevenDays(tasks),
            todayIndex = 6,
            averagePerDay = average(tasks),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun selectScope(allSelected: Boolean) {
        all.value = allSelected
    }

    private fun lastSevenDays(tasks: List<TaskItem>): List<Float> {
        val today = Instant.ofEpochMilli(TimeText.now()).atZone(zone).toLocalDate()
        return (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            tasks.count { task -> task.done && completedDate(task) == date }.toFloat()
        }
    }

    private fun average(tasks: List<TaskItem>): String {
        val done = tasks.count { it.done }
        return if (done == 0) "0" else String.format("%.1f", done / 7.0)
    }

    private fun completedDate(task: TaskItem): LocalDate? =
        task.completedAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
