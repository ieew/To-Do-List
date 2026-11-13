package com.todo.android.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.data.settings.AppSettings
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.designsystem.priorityLabel
import com.todo.android.core.domain.Fields
import com.todo.android.core.domain.TaskGroups
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TaskScope
import com.todo.android.core.domain.TaskScopeType
import com.todo.android.core.domain.TaskScoping
import com.todo.android.core.domain.WorkspaceProjection
import com.todo.android.core.sync.SyncEngine
import com.todo.android.core.sync.SyncState
import com.todo.android.core.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class TasksUiState(
    val projection: WorkspaceProjection = WorkspaceProjection.EMPTY,
    val scope: TaskScope = TaskScope.Today,
    val groups: TaskGroups = TaskGroups(emptyList(), emptyList(), emptyList()),
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val search: String = "",
    val dayStarted: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val sync: SyncState = SyncState(),
    val toast: String? = null,

    /** 非空时 Toast 显示「撤销」（删除后可恢复）。 */
    val undoTaskId: String? = null,
    val loading: Boolean = true,
    /** 下拉刷新中。 */
    val refreshing: Boolean = false,
) {
    val tagsById get() = projection.tags.associateBy { it.id }
    val activeListName: String
        get() = projection.listById(scope.listId)?.name
            ?: projection.lists.firstOrNull()?.name
            ?: "清单"
    val percent: Int
        get() = if (progressTotal == 0) 0 else (progressDone * 100 / progressTotal)
}

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val projectionHolder: ProjectionHolder,
    private val repository: OpRepository,
    private val commands: TaskCommands,
    private val settingsStore: SettingsStore,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private data class Local(
        val scope: TaskScope = TaskScope.Today,
        val search: String = "",
        val toast: String? = null,
        val undoTaskId: String? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val nowMs = MutableStateFlow(TimeText.now())

    val state: StateFlow<TasksUiState> = combine(
        projectionHolder.projection,
        local,
        settingsStore.observe(),
        syncEngine.state,
    ) { projection, localState, settings, sync ->
        val now = nowMs.value
        val scope = if (localState.search.isNotBlank()) {
            TaskScope(TaskScopeType.SEARCH, query = localState.search)
        } else {
            localState.scope
        }
        val tasks = projection.tasks
        val viewTasks = TaskScoping.viewTasks(tasks, scope, now)
        TasksUiState(
            projection = projection,
            scope = scope,
            groups = TaskScoping.group(viewTasks, now),
            progressDone = TaskScoping.todayProgress(tasks, now).first,
            progressTotal = TaskScoping.todayProgress(tasks, now).second,
            search = localState.search,
            dayStarted = true,
            settings = settings,
            sync = sync,
            toast = localState.toast,
            undoTaskId = localState.undoTaskId,
            loading = localState.loading,
            refreshing = localState.refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    init {
        viewModelScope.launch {
            repository.observeProjection().collect { local.value = local.value.copy(loading = false) }
        }
    }

    fun refreshClock() {
        nowMs.value = TimeText.now()
    }

    /** 下拉刷新：立刻跑一轮 push + pull，结果用 Toast 反馈。 */
    fun refresh() {
        if (local.value.refreshing) return
        viewModelScope.launch {
            local.value = local.value.copy(refreshing = true)
            val result = syncEngine.runOnce()
            local.value = local.value.copy(
                refreshing = false,
                toast = when (result.status) {
                    SyncStatus.SYNCED -> "已同步"
                    SyncStatus.SIGNED_OUT -> "未登录，本地修改会稍后推送"
                    SyncStatus.AUTH_REQUIRED -> "登录已过期，请重新登录"
                    else -> result.lastError ?: "网络不稳定，稍后重试"
                },
            )
            refreshClock()
        }
    }

    fun selectScope(all: Boolean) {
        local.value = local.value.copy(scope = if (all) TaskScope.All else TaskScope.Today, search = "")
    }

    fun selectList(listId: String) {
        local.value = local.value.copy(scope = TaskScope(TaskScopeType.LIST, listId = listId), search = "")
    }

    fun selectTag(tagId: String) {
        local.value = local.value.copy(scope = TaskScope(TaskScopeType.TAG, tagId = tagId), search = "")
    }

    fun onSearchChange(query: String) {
        local.value = local.value.copy(search = query)
    }

    fun clearSearch() {
        local.value = local.value.copy(search = "")
    }

    fun showToast(message: String?) {
        local.value = local.value.copy(toast = message, undoTaskId = null)
    }

    fun quickCreate(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        val projection = projectionHolder.projection.value
        val workspaceId = projection.activeWorkspaceId
        if (workspaceId.isEmpty()) {
            showToast("请先创建或登录工作区")
            return false
        }
        val listId = projection.lists.firstOrNull { it.smart }?.id
            ?: projection.lists.firstOrNull()?.id
            ?: return false
        val now = TimeText.now()
        val dueIso = TimeText.isoAfterDays(7, now)
        viewModelScope.launch {
            commands.createTask(
                workspaceId = workspaceId,
                listId = listId,
                title = trimmed,
                startAtIso = TimeText.isoNow(now),
                dueAtIso = dueIso,
            )
            val dueLabel = TimeText.dueLabel(dueIso, now)
            showToast("已创建「$trimmed」· 截止 $dueLabel（自动 +7 天）")
            refreshClock()
        }
        return true
    }

    fun toggleTask(task: TaskItem, done: Boolean) {
        viewModelScope.launch {
            commands.setDone(task.id, projectionHolder.projection.value.activeWorkspaceId, done)
            refreshClock()
        }
    }

    fun setPriority(task: TaskItem, priority: String) {
        viewModelScope.launch {
            commands.updateField(
                task.id,
                projectionHolder.projection.value.activeWorkspaceId,
                Fields.PRIORITY,
                JsonPrimitive(priority),
            )
            showToast("「${task.title}」优先级改为 ${priorityLabel(priority)}")
        }
    }

    fun toggleTag(taskId: String, tagId: String, assigned: Boolean) {
        viewModelScope.launch {
            val workspaceId = projectionHolder.projection.value.activeWorkspaceId
            if (assigned) {
                commands.removeTag(taskId, workspaceId, tagId)
            } else {
                commands.addTag(taskId, workspaceId, tagId)
            }
        }
    }

    /** 删除 = 写 `__deleted` 墓石；3 秒内可撤销。 */
    fun deleteTask(task: TaskItem) {
        viewModelScope.launch {
            commands.updateField(
                task.id,
                projectionHolder.projection.value.activeWorkspaceId,
                Fields.DELETED,
                JsonPrimitive(true),
            )
            local.value = local.value.copy(toast = "已删除「${task.title}」", undoTaskId = task.id)
        }
    }

    fun restoreTask(taskId: String) {
        viewModelScope.launch {
            commands.updateField(
                taskId,
                projectionHolder.projection.value.activeWorkspaceId,
                Fields.DELETED,
                JsonPrimitive(false),
            )
            local.value = local.value.copy(toast = "已恢复该任务", undoTaskId = null)
        }
    }

    fun switchWorkspace(workspaceId: String) {
        viewModelScope.launch {
            repository.setActiveWorkspaceId(workspaceId)
            resetWorkspaceScopedState()
            showToast("已切换到「${projectionHolder.projection.value.workspaces.firstOrNull { it.id == workspaceId }?.name ?: ""}」")
        }
    }

    fun createWorkspace(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 24) return false
        viewModelScope.launch {
            commands.createWorkspace(trimmed, repository.currentUserId())
            resetWorkspaceScopedState()
            showToast("已创建并切换到「$trimmed」（含 4 清单 5 标签）")
        }
        return true
    }

    fun renameWorkspace(workspaceId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 24) return false
        viewModelScope.launch {
            commands.renameWorkspace(workspaceId, trimmed)
            showToast("已重命名工作区")
        }
        return true
    }

    fun deleteWorkspace(workspaceId: String): Boolean {
        val workspaces = projectionHolder.projection.value.workspaces
        if (workspaces.size <= 1) {
            showToast("至少需要保留一个工作区")
            return false
        }
        viewModelScope.launch {
            commands.deleteWorkspace(workspaceId)
            val next = workspaces.firstOrNull { it.id != workspaceId }
            next?.let { repository.setActiveWorkspaceId(it.id) }
            resetWorkspaceScopedState()
            showToast("已删除工作区")
        }
        return true
    }

    /** 切换/删除工作区后必须重置筛选与页面状态。 */
    private fun resetWorkspaceScopedState() {
        local.value = local.value.copy(scope = TaskScope.Today, search = "")
    }
}
