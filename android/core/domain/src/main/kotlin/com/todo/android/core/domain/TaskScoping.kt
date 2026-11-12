package com.todo.android.core.domain

/**
 * 视图范围与分组（与 `docs/task-time-and-scope-model.md` 对齐）：
 * - 今天 = 已开始任务（`startAtMs` 为空或 <= now）；全部 = 不按开始时间过滤；
 * - 分组只看开始时间：已开始→今天，未开始→即将到来，另加已完成；
 * - 截止时间不参与分组，只用于行内文案、逾期高亮与日历排布。
 */
enum class TaskScopeType {
    TODAY,
    ALL,
    LIST,
    TAG,
    SEARCH,
}

data class TaskScope(
    val type: TaskScopeType = TaskScopeType.TODAY,
    val listId: String? = null,
    val tagId: String? = null,
    val query: String = "",
) {
    companion object {
        val Today = TaskScope(TaskScopeType.TODAY)
        val All = TaskScope(TaskScopeType.ALL)
    }
}

data class TaskGroups(
    val today: List<TaskItem>,
    val upcoming: List<TaskItem>,
    val completed: List<TaskItem>,
) {
    val todayCount: Int get() = today.size
    val upcomingCount: Int get() = upcoming.size
    val allCount: Int get() = today.size + upcoming.size
}

object TaskScoping {
    fun hasStarted(startAtMs: Long?, nowMs: Long): Boolean = startAtMs == null || startAtMs <= nowMs

    fun viewTasks(
        tasks: List<TaskItem>,
        scope: TaskScope,
        nowMs: Long,
    ): List<TaskItem> = tasks.filter { task ->
        when (scope.type) {
            TaskScopeType.TODAY -> hasStarted(task.startAtMs, nowMs)
            TaskScopeType.ALL -> true
            TaskScopeType.LIST -> task.listId == scope.listId
            TaskScopeType.TAG -> task.tagIds.contains(scope.tagId)
            TaskScopeType.SEARCH -> {
                val query = scope.query.trim()
                query.isEmpty() || task.title.contains(query, ignoreCase = true) ||
                    task.notes.contains(query, ignoreCase = true)
            }
        }
    }

    fun group(tasks: List<TaskItem>, nowMs: Long): TaskGroups {
        val active = tasks.filterNot { it.done }
        return TaskGroups(
            today = active.filter { hasStarted(it.startAtMs, nowMs) },
            upcoming = active.filterNot { hasStarted(it.startAtMs, nowMs) },
            completed = tasks.filter { it.done },
        )
    }

    /** 今日进度：今天分组内已完成 / 总数（含已完成）。 */
    fun todayProgress(tasks: List<TaskItem>, nowMs: Long): Pair<Int, Int> {
        val group = group(tasks, nowMs)
        val total = group.today.size + group.completed.size
        return group.completed.size to total
    }

    fun calendarTasks(tasks: List<TaskItem>, scope: TaskScope, nowMs: Long): List<TaskItem> =
        viewTasks(tasks, scope, nowMs).filter { it.dueAtMs != null }
}
