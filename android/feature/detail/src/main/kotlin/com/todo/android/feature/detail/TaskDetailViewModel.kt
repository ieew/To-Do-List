package com.todo.android.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.domain.SubtaskItem
import com.todo.android.core.domain.TagItem
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TodoList
import com.todo.android.core.domain.WorkspaceProjection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class TaskDetailUiState(
    val isNew: Boolean = true,
    val taskId: String? = null,
    val title: String = "",
    val notes: String = "",
    val priority: String = "mid",
    val startAt: String? = null,
    val dueAt: String? = null,
    val remindAt: String? = null,
    val listId: String? = null,
    val tagIds: List<String> = emptyList(),
    val subtasks: List<SubtaskItem> = emptyList(),
    val lists: List<TodoList> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val projection: WorkspaceProjection = WorkspaceProjection.EMPTY,
    val toast: String? = null,
    val closed: Boolean = false,
) {
    val listName: String? get() = lists.firstOrNull { it.id == listId }?.name
    val selectedTags: List<TagItem> get() = tags.filter { tagIds.contains(it.id) }
    val subtaskDone: Int get() = subtasks.count { it.done }
}

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val projectionHolder: ProjectionHolder,
    private val repository: OpRepository,
    private val commands: TaskCommands,
) : ViewModel() {
    private data class Local(
        val isNew: Boolean = true,
        val taskId: String? = null,
        val title: String = "",
        val notes: String = "",
        val priority: String = "mid",
        val startAt: String? = null,
        val dueAt: String? = null,
        val remindAt: String? = null,
        val listId: String? = null,
        val tagIds: List<String> = emptyList(),
        val toast: String? = null,
        val closed: Boolean = false,
        val bound: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var original: TaskItem? = null

    val state: StateFlow<TaskDetailUiState> = combine(
        projectionHolder.projection,
        local,
    ) { projection, localState ->
        val task = localState.taskId?.let { id -> projection.tasks.firstOrNull { it.id == id } }
        TaskDetailUiState(
            isNew = localState.isNew,
            taskId = localState.taskId,
            title = localState.title,
            notes = localState.notes,
            priority = localState.priority,
            startAt = localState.startAt,
            dueAt = localState.dueAt,
            remindAt = localState.remindAt,
            listId = localState.listId,
            tagIds = localState.tagIds,
            subtasks = task?.subtasks.orEmpty(),
            lists = projection.lists,
            tags = projection.tags,
            projection = projection,
            toast = localState.toast,
            closed = localState.closed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    /** 进入页面时绑定：taskId 为 null 表示新建模式。 */
    fun bind(taskId: String?) {
        if (local.value.bound) return
        val projection = projectionHolder.projection.value
        val now = TimeText.now()
        if (taskId == null) {
            val listId = projection.lists.firstOrNull { it.smart }?.id ?: projection.lists.firstOrNull()?.id
            local.value = Local(
                isNew = true,
                taskId = null,
                title = "",
                notes = "",
                priority = "mid",
                startAt = TimeText.isoNow(now),
                dueAt = TimeText.isoAfterDays(7, now),
                remindAt = null,
                listId = listId,
                tagIds = emptyList(),
                bound = true,
            )
        } else {
            val task = projection.tasks.firstOrNull { it.id == taskId }
            if (task == null) {
                local.value = local.value.copy(bound = true, toast = "任务不存在", closed = true)
                return
            }
            original = task
            local.value = Local(
                isNew = false,
                taskId = task.id,
                title = task.title,
                notes = task.notes,
                priority = task.priority,
                startAt = task.startAt,
                dueAt = task.dueAt,
                remindAt = task.remindAt,
                listId = task.listId,
                tagIds = task.tagIds,
                bound = true,
            )
        }
    }

    fun onTitleChange(value: String) {
        local.value = local.value.copy(title = value)
    }

    fun onNotesChange(value: String) {
        local.value = local.value.copy(notes = value)
    }

    fun setPriority(priority: String) {
        local.value = local.value.copy(priority = priority)
    }

    fun setStart(iso: String?) {
        local.value = local.value.copy(startAt = iso)
    }

    fun setDue(iso: String?) {
        local.value = local.value.copy(dueAt = iso)
    }

    fun setRemind(iso: String?) {
        local.value = local.value.copy(remindAt = iso)
    }

    fun setList(listId: String) {
        local.value = local.value.copy(listId = listId)
    }

    fun toggleTag(tagId: String) {
        val tags = local.value.tagIds.toMutableList()
        if (!tags.remove(tagId)) tags.add(tagId)
        local.value = local.value.copy(tagIds = tags)
    }

    fun addSubtask(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val state = local.value
        val workspaceId = projectionHolder.projection.value.activeWorkspaceId
        if (state.isNew || state.taskId == null) {
            showToast("请先创建任务再添加子任务")
            return
        }
        viewModelScope.launch {
            commands.addSubtask(state.taskId, workspaceId, trimmed, state.subtasksOrder())
        }
    }

    fun toggleSubtask(subtask: SubtaskItem, done: Boolean) {
        val state = local.value
        val taskId = state.taskId ?: return
        viewModelScope.launch {
            commands.setSubtaskDone(taskId, projectionHolder.projection.value.activeWorkspaceId, subtask.id, done)
        }
    }

    fun deleteSubtask(subtask: SubtaskItem) {
        val state = local.value
        val taskId = state.taskId ?: return
        viewModelScope.launch {
            commands.deleteSubtask(taskId, projectionHolder.projection.value.activeWorkspaceId, subtask.id)
        }
    }

    fun save() {
        val state = local.value
        val title = state.title.trim()
        if (title.isEmpty()) {
            showToast("请输入任务标题")
            return
        }
        val workspaceId = projectionHolder.projection.value.activeWorkspaceId
        if (workspaceId.isEmpty()) {
            showToast("请先创建工作区")
            return
        }
        viewModelScope.launch {
            if (state.isNew) {
                val listId = state.listId
                    ?: projectionHolder.projection.value.lists.firstOrNull()?.id
                if (listId == null) {
                    showToast("请先创建清单")
                    return@launch
                }
                commands.createTask(
                    workspaceId = workspaceId,
                    listId = listId,
                    title = title,
                    startAtIso = state.startAt ?: TimeText.isoNow(),
                    dueAtIso = state.dueAt ?: TimeText.isoAfterDays(7),
                    priority = state.priority,
                )
                close()
            } else {
                val taskId = state.taskId ?: return@launch
                val before = original
                if (before == null || before.title != title) {
                    commands.updateField(taskId, workspaceId, "title", JsonPrimitive(title))
                }
                if (before == null || before.notes != state.notes) {
                    commands.updateField(taskId, workspaceId, "notes", JsonPrimitive(state.notes))
                }
                if (before == null || before.priority != state.priority) {
                    commands.updateField(taskId, workspaceId, "priority", JsonPrimitive(state.priority))
                }
                if (before?.startAt != state.startAt) {
                    commands.updateField(taskId, workspaceId, "start_at", state.startAt?.let(::JsonPrimitive) ?: JsonNull)
                }
                if (before?.dueAt != state.dueAt) {
                    commands.updateField(taskId, workspaceId, "due_at", state.dueAt?.let(::JsonPrimitive) ?: JsonNull)
                }
                if (before?.remindAt != state.remindAt) {
                    commands.updateField(taskId, workspaceId, "remind_at", state.remindAt?.let(::JsonPrimitive) ?: JsonNull)
                }
                if (before?.listId != state.listId && state.listId != null) {
                    commands.updateField(taskId, workspaceId, "list_id", JsonPrimitive(state.listId))
                }
                val beforeTags = before?.tagIds.orEmpty().toSet()
                (state.tagIds.toSet() - beforeTags).forEach { commands.addTag(taskId, workspaceId, it) }
                (beforeTags - state.tagIds.toSet()).forEach { commands.removeTag(taskId, workspaceId, it) }
                close()
            }
        }
    }

    fun delete() {
        val state = local.value
        val taskId = state.taskId ?: return
        viewModelScope.launch {
            commands.updateField(taskId, projectionHolder.projection.value.activeWorkspaceId, "__deleted", JsonPrimitive(true))
            close()
        }
    }

    fun cancel() {
        close()
    }

    fun showToast(message: String?) {
        local.value = local.value.copy(toast = message)
    }

    private fun close() {
        local.value = local.value.copy(closed = true)
    }

    private fun Local.subtasksOrder(): Double {
        val task = projectionHolder.projection.value.tasks.firstOrNull { it.id == taskId }
        return (task?.subtasks?.maxOfOrNull { it.order } ?: 0.0) + 1.0
    }
}
