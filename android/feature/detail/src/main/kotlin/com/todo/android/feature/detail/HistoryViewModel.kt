package com.todo.android.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.domain.HistoryEntry
import com.todo.android.core.domain.OpOperation
import com.todo.android.core.domain.Timeline
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val taskId: String = "",
    val taskTitle: String = "",
    val entries: List<HistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val toast: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: OpRepository,
    private val projectionHolder: ProjectionHolder,
    private val commands: TaskCommands,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun load(taskId: String) {
        viewModelScope.launch {
            val workspaceId = repository.activeWorkspaceId().orEmpty()
            val projection = projectionHolder.projection.value
            val task = projection.tasks.firstOrNull { it.id == taskId }
            val entries = Timeline.build(
                ops = repository.loadAllOps(),
                workspaceId = workspaceId,
                taskId = taskId,
                currentUserId = repository.currentUserId(),
                memberNames = projection.members.associate { it.userId to it.name },
                taskTitles = projection.tasks.associate { it.id to it.title },
            )
            _state.value = HistoryUiState(
                taskId = taskId,
                taskTitle = task?.title ?: "任务历史",
                entries = entries,
                loading = false,
            )
        }
    }

    fun undo(entry: HistoryEntry) {
        viewModelScope.launch {
            val workspaceId = repository.activeWorkspaceId().orEmpty()
            val element = entry.newValue.let { raw ->
                runCatching { (raw as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull()
            }
            commands.undo(
                taskId = entry.taskId,
                workspaceId = workspaceId,
                targetOpId = entry.opId,
                field = entry.field,
                operation = entry.operation,
                oldValue = entry.oldValue,
                collectionElement = if (entry.operation == OpOperation.SET) null else element,
            )
            _state.value = _state.value.copy(toast = "已生成撤销 op")
            load(entry.taskId)
        }
    }

    fun showToast(message: String?) {
        _state.value = _state.value.copy(toast = message)
    }
}
