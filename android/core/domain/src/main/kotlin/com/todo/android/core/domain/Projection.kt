package com.todo.android.core.domain

/**
 * 投影 DTO。字段与服务端 `/v1/history/snapshot` 的 `projection` 对齐，
 * 时间以毫秒时间戳暴露（领域层不读时钟、不做时区格式化，格式化交给 feature 层）。
 */
data class SubtaskItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val order: Double,
)

data class TaskItem(
    val id: String,
    val title: String,
    val notes: String,
    val startAt: String?,
    val dueAt: String?,
    val remindAt: String?,
    val startAtMs: Long? = null,
    val dueAtMs: Long? = null,
    val remindAtMs: Long? = null,
    val priority: String,
    val status: String,
    val listId: String,
    val tagIds: List<String>,
    val subtasks: List<SubtaskItem>,
    val source: OpSource,
    val createdAt: String?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val order: Double,
    val model: String?,
) {
    val done: Boolean get() = status == "done"
}

data class TodoList(
    val id: String,
    val name: String,
    val color: String,
    val order: Double,
    val smart: Boolean,
)

data class TagItem(
    val id: String,
    val name: String,
    val bg: String,
    val fg: String,
    val showInSidebar: Boolean?,
)

data class WorkspaceItem(
    val id: String,
    val name: String,
)

data class MemberItem(
    val userId: String,
    val name: String,
    val initial: String,
    val color: String,
    val role: String,
    val email: String,
)

data class WorkspaceProjection(
    val activeWorkspaceId: String,
    val currentUserId: String,
    val workspaces: List<WorkspaceItem>,
    val members: List<MemberItem>,
    val lists: List<TodoList>,
    val tags: List<TagItem>,
    val tasks: List<TaskItem>,
) {
    val activeWorkspace: WorkspaceItem? get() = workspaces.firstOrNull { it.id == activeWorkspaceId }

    val activeList: TodoList? get() = lists.firstOrNull { it.id == todayListId }

    /** 默认「今天」清单 id：注册种子为 `today`，新建工作区为 `<workspaceId>-today`。 */
    val todayListId: String get() = TodoListIds.today(activeWorkspaceId)

    fun listById(id: String?): TodoList? = lists.firstOrNull { it.id == id }

    fun tagById(id: String): TagItem? = tags.firstOrNull { it.id == id }

    companion object {
        val EMPTY = WorkspaceProjection(
            activeWorkspaceId = "",
            currentUserId = "",
            workspaces = emptyList(),
            members = emptyList(),
            lists = emptyList(),
            tags = emptyList(),
            tasks = emptyList(),
        )
    }
}

object TodoListIds {
    fun today(workspaceId: String): String = "$workspaceId-today"
}

/** 纯函数投影：任意 op 顺序得到相同结果。 */
object Projection {
    fun build(
        ops: List<Op>,
        requestedWorkspaceId: String?,
        currentUserId: String,
    ): WorkspaceProjection {
        val state = MergeState.applyAll(ops)
        return build(state, requestedWorkspaceId, currentUserId)
    }

    fun build(
        state: MergeState,
        requestedWorkspaceId: String?,
        currentUserId: String,
    ): WorkspaceProjection {
        val workspaces = buildWorkspaces(state, currentUserId)
        val activeWorkspaceId = requestedWorkspaceId
            ?.takeIf { id -> workspaces.any { it.id == id } }
            ?: workspaces.firstOrNull()?.id
            ?: ""

        val lists = buildLists(state, activeWorkspaceId)
        val listWorkspace = buildListWorkspaceIndex(state)
        val tags = buildTags(state, activeWorkspaceId)
        val members = buildMembers(state, activeWorkspaceId, currentUserId)
        val tasks = buildTasks(state, activeWorkspaceId, listWorkspace)

        return WorkspaceProjection(
            activeWorkspaceId = activeWorkspaceId,
            currentUserId = currentUserId,
            workspaces = workspaces,
            members = members,
            lists = lists,
            tags = tags,
            tasks = tasks,
        )
    }

    private fun buildWorkspaces(state: MergeState, currentUserId: String): List<WorkspaceItem> =
        state.entitiesOf(EntityType.WORKSPACE)
            .filterNot { state.deleted(EntityType.WORKSPACE, it) }
            .filter { id ->
                val membershipId = "$id:$currentUserId"
                !state.bool(EntityType.MEMBERSHIP, membershipId, Fields.REMOVED) &&
                    state.text(EntityType.MEMBERSHIP, membershipId, Fields.ROLE) != null
            }
            .map { id ->
                WorkspaceItem(
                    id = id,
                    name = state.text(EntityType.WORKSPACE, id, Fields.NAME) ?: "未命名空间",
                )
            }
            .sortedWith(compareBy({ it.name }, { it.id }))

    private fun buildListWorkspaceIndex(state: MergeState): Map<String, String> {
        val index = HashMap<String, String>()
        state.entitiesOf(EntityType.LIST).forEach { id ->
            if (state.deleted(EntityType.LIST, id)) return@forEach
            val workspaceId = state.text(EntityType.LIST, id, Fields.WORKSPACE_ID) ?: return@forEach
            index[id] = workspaceId
        }
        return index
    }

    private fun buildLists(state: MergeState, activeWorkspaceId: String): List<TodoList> =
        state.entitiesOf(EntityType.LIST)
            .filterNot { state.deleted(EntityType.LIST, it) }
            .filter { state.text(EntityType.LIST, it, Fields.WORKSPACE_ID).orEmpty() == activeWorkspaceId }
            .map { id ->
                TodoList(
                    id = id,
                    name = state.text(EntityType.LIST, id, Fields.NAME) ?: "未命名清单",
                    color = state.text(EntityType.LIST, id, Fields.COLOR) ?: "#3B82F6",
                    order = state.number(EntityType.LIST, id, Fields.ORDER, 0.0),
                    smart = id == "today" || id.endsWith("-today"),
                )
            }
            .sortedWith(compareBy({ it.order }, { it.id }))

    private fun buildTags(state: MergeState, activeWorkspaceId: String): List<TagItem> =
        state.entitiesOf(EntityType.TAG)
            .filterNot { state.deleted(EntityType.TAG, it) }
            .filter { state.text(EntityType.TAG, it, Fields.WORKSPACE_ID).orEmpty() == activeWorkspaceId }
            .map { id ->
                TagItem(
                    id = id,
                    name = state.text(EntityType.TAG, id, Fields.NAME) ?: "标签",
                    bg = state.text(EntityType.TAG, id, Fields.BG) ?: "#EFF6FF",
                    fg = state.text(EntityType.TAG, id, Fields.FG) ?: "#2563EB",
                    showInSidebar = state.raw(EntityType.TAG, id, Fields.SHOW_IN_SIDEBAR)
                        ?.let(OpValues::boolOrNull),
                )
            }
            .sortedWith(compareBy({ it.name }, { it.id }))

    private fun buildMembers(
        state: MergeState,
        activeWorkspaceId: String,
        currentUserId: String,
    ): List<MemberItem> {
        val roleRank = mapOf("owner" to 0, "editor" to 1, "viewer" to 2)
        return state.entitiesOf(EntityType.MEMBERSHIP)
            .filter { it.startsWith("$activeWorkspaceId:") }
            .filterNot { state.bool(EntityType.MEMBERSHIP, it, Fields.REMOVED) }
            .mapNotNull { membershipId ->
                val userId = membershipId.substringAfter(':')
                val role = state.text(EntityType.MEMBERSHIP, membershipId, Fields.ROLE)
                    ?: return@mapNotNull null
                MemberItem(
                    userId = userId,
                    name = state.text(EntityType.USER, userId, Fields.NAME) ?: userId,
                    initial = state.text(EntityType.USER, userId, Fields.INITIAL) ?: "用",
                    color = state.text(EntityType.USER, userId, Fields.COLOR) ?: "#3B82F6",
                    role = role,
                    email = state.text(EntityType.USER, userId, Fields.EMAIL)
                        ?: "$userId@todo.local",
                )
            }
            .sortedWith(
                compareBy(
                    { if (it.userId == currentUserId) 0 else 1 },
                    { roleRank[it.role] ?: 3 },
                    { it.name },
                ),
            )
    }

    private fun buildTasks(
        state: MergeState,
        activeWorkspaceId: String,
        listWorkspace: Map<String, String>,
    ): List<TaskItem> =
        state.entitiesOf(EntityType.TASK)
            .filterNot { state.deleted(EntityType.TASK, it) }
            .mapNotNull { id ->
                val listId = state.text(EntityType.TASK, id, Fields.LIST_ID).orEmpty()
                if (listWorkspace[listId] != activeWorkspaceId) return@mapNotNull null

                val meta = state.metaOf(EntityType.TASK, id)
                val status = state.text(EntityType.TASK, id, Fields.STATUS) ?: "todo"
                val statusMs = state.fieldHlc(EntityType.TASK, id, Fields.STATUS)?.physicalMs

                val subtasks = state.elements(EntityType.TASK, id, Fields.SUBTASK)
                    .map { subtaskId ->
                        SubtaskItem(
                            id = subtaskId,
                            text = state.text(EntityType.TASK, id, Fields.subtaskText(subtaskId))
                                ?: "子任务",
                            done = state.bool(EntityType.TASK, id, Fields.subtaskDone(subtaskId)),
                            order = state.number(EntityType.TASK, id, Fields.subtaskOrder(subtaskId), 0.0),
                        )
                    }
                    .sortedWith(compareBy({ it.order }, { it.id }))

                TaskItem(
                    id = id,
                    title = state.text(EntityType.TASK, id, Fields.TITLE) ?: "未命名任务",
                    notes = state.text(EntityType.TASK, id, Fields.NOTES).orEmpty(),
                    startAt = state.text(EntityType.TASK, id, Fields.START_AT),
                    dueAt = state.text(EntityType.TASK, id, Fields.DUE_AT),
                    remindAt = state.text(EntityType.TASK, id, Fields.REMIND_AT),
                    priority = state.text(EntityType.TASK, id, Fields.PRIORITY) ?: "mid",
                    status = status,
                    listId = listId,
                    tagIds = state.elements(EntityType.TASK, id, Fields.TAG),
                    subtasks = subtasks,
                    source = meta?.source ?: OpSource.HUMAN,
                    createdAt = state.text(EntityType.TASK, id, Fields.CREATED),
                    createdAtMs = meta?.firstMs ?: 0L,
                    completedAtMs = if (status == "done") statusMs else null,
                    order = state.number(EntityType.TASK, id, Fields.ORDER, 0.0),
                    model = meta?.model,
                )
            }
            .sortedWith(
                compareBy(
                    { if (it.done) 1 else 0 },
                    { it.order },
                    { it.id },
                ),
            )
}
