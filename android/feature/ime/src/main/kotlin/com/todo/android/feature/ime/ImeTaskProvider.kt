package com.todo.android.feature.ime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.WorkspaceProjection
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 给输入法插件（dsh-kit）的桥。
 *
 * 只做三件事：
 * 1. `query`：两类任务（等待完成 / 36 小时内即将开始），只读；
 * 2. `insert`：快速添加，走 `TaskCommands.createTask`（与 App 内快速添加同一条 op 管道）；
 * 3. `call(set_done)`：勾选/取消完成。
 *
 * 不暴露删除、编辑、成员、历史；数据与同步仍由 App 负责，插件只消费结果。
 */
class ImeTaskProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImeEntryPoint {
        fun repository(): OpRepository

        fun taskCommands(): TaskCommands
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        ensureCallerAllowed()
        val segment = uri.pathSegments.firstOrNull()
        require(segment == SEGMENT_TASKS) { "unknown uri: $uri" }

        val scope = uri.getQueryParameter(ImeContract.PARAM_SCOPE) ?: ImeContract.SCOPE_WAITING
        val limit = ImeQueries.clampLimit(
            uri.getQueryParameter(ImeContract.PARAM_LIMIT)?.toIntOrNull() ?: ImeContract.DEFAULT_LIMIT,
        )
        val now = System.currentTimeMillis()
        val state = projection()
        val tasks = when (scope) {
            ImeContract.SCOPE_WAITING -> ImeQueries.waiting(state.tasks, now, limit)
            ImeContract.SCOPE_UPCOMING -> ImeQueries.upcoming(state.tasks, now, limit)
            else -> throw IllegalArgumentException("unknown scope: $scope")
        }

        val listNames = state.lists.associate { it.id to it.name }
        val columns = projection ?: ImeContract.READ_COLUMNS
        val cursor = MatrixCursor(columns)
        for (task in tasks) {
            val row = arrayOfNulls<Any>(columns.size)
            columns.forEachIndexed { index, column ->
                row[index] = valueOf(column, task, state, listNames, now)
            }
            cursor.addRow(row)
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        ensureCallerAllowed()
        require(uri.pathSegments.firstOrNull() == SEGMENT_TASKS) { "unknown uri: $uri" }

        val title = values?.getAsString(ImeContract.COL_TITLE)?.trim().orEmpty()
        require(title.isNotEmpty()) { "title required" }
        require(title.length <= 500) { "title too long" }

        val state = projection()
        val workspaceId = values?.getAsString(ImeContract.COL_WORKSPACE_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.activeWorkspaceId
        check(workspaceId.isNotEmpty()) { "not_logged_in" }

        val listId = values?.getAsString(ImeContract.COL_LIST_ID_INPUT)?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.lists.firstOrNull { it.smart }?.id
            ?: state.lists.firstOrNull()?.id
            ?: error("no_list")

        val now = System.currentTimeMillis()
        val taskId = runBlocking {
            entryPoint().taskCommands().createTask(
                workspaceId = workspaceId,
                listId = listId,
                title = title,
                startAtIso = IsoTime.nowIso(now),
                dueAtIso = IsoTime.afterDaysIso(7, now),
            )
        }
        return Uri.withAppendedPath(ImeContract.TASKS_URI, taskId)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        ensureCallerAllowed()
        require(method == ImeContract.METHOD_SET_DONE) { "unsupported method: $method" }

        val taskId = arg?.trim()?.takeIf { it.isNotEmpty() }
            ?: extras?.getString(ImeContract.ARG_TASK_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("task_id required")
        val done = extras?.getBoolean(ImeContract.EXTRA_DONE, true) ?: true

        val state = projection()
        check(state.activeWorkspaceId.isNotEmpty()) { "not_logged_in" }
        runBlocking {
            entryPoint().taskCommands().setDone(taskId, state.activeWorkspaceId, done)
        }
        return Bundle().apply { putBoolean(ImeContract.EXTRA_OK, true) }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("ime bridge is read-only here")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ime bridge cannot delete")

    override fun getType(uri: Uri): String? =
        if (uri.pathSegments.firstOrNull() == SEGMENT_TASKS) "vnd.android.cursor.dir/vnd.todo.ime.task" else null

    // ---------------------------------------------------------------- 内部

    private fun entryPoint(): ImeEntryPoint =
        EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, ImeEntryPoint::class.java)

    /** 首帧投影（Room 查询在 IO 线程完成；provider 跑在 binder 线程，不阻塞主线程）。 */
    private fun projection(): WorkspaceProjection = runBlocking {
        entryPoint().repository().observeProjection().first()
    }

    private fun valueOf(
        column: String,
        task: TaskItem,
        state: WorkspaceProjection,
        listNames: Map<String, String>,
        now: Long,
    ): Any = when (column) {
        ImeContract.COL_TASK_ID -> task.id
        ImeContract.COL_TITLE -> task.title
        ImeContract.COL_START_AT -> task.startAtMs ?: 0L
        ImeContract.COL_DUE_AT -> task.dueAtMs ?: 0L
        ImeContract.COL_PRIORITY -> task.priority
        ImeContract.COL_LIST_ID -> task.listId
        ImeContract.COL_LIST_NAME -> listNames[task.listId] ?: ""
        ImeContract.COL_WORKSPACE_ID -> state.activeWorkspaceId
        ImeContract.COL_OVERDUE -> if (ImeQueries.isOverdue(task, now)) 1 else 0
        else -> throw IllegalArgumentException("unknown column: $column")
    }

    /**
     * 插件跑在 `com.nirenr.trime` 进程里，它没有自己的 manifest 可声明权限，
     * 所以只能校验调用方的 uid 对应的包名。App 自己（同 uid）也放行。
     */
    private fun ensureCallerAllowed() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = requireNotNull(context).packageManager.getPackagesForUid(uid) ?: emptyArray<String>()
        if (packages.any { it == ImeContract.CALLER_PACKAGE }) return
        throw SecurityException("ime bridge: caller not allowed (uid=$uid)")
    }

    private fun require(condition: Boolean, message: () -> String) {
        if (!condition) throw IllegalArgumentException(message())
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw IllegalStateException(message())
    }

    private companion object {
        const val SEGMENT_TASKS = "tasks"
    }
}
