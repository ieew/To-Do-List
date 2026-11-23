package com.todo.android.feature.ime

import android.net.Uri

/**
 * 输入法插件（`com.nirenr.trime` 进程内的 dsh-kit）与本 App 之间的唯一契约。
 *
 * 走 ContentProvider 而不是 AIDL：插件是注入输入法的 dex，**没有自己的 manifest**，
 * 加不了 `<uses-permission>`，所以不能用 signature 权限；同时 dex 里手写 Binder stub 太脆。
 * ContentProvider 的好处是插件只需 `ContentResolver.query/insert/call`，
 * 且 App 未运行时 provider 会把进程拉起来。
 *
 * 安全边界：
 * - provider `exported=true`，App 端在每次调用校验 `Binder.getCallingUid()` 的包名；
 * - 只暴露「两类任务的只读视图 + 快速添加 + 勾选完成」，不暴露删除/编辑/成员/历史；
 * - 任何未识别调用抛异常，插件侧按「不可用」降级。
 */
object ImeContract {
    const val AUTHORITY = "com.todo.android.ime"

    /** 只读任务列表：`?scope=waiting|upcoming&limit=20`。 */
    val TASKS_URI: Uri = Uri.parse("content://$AUTHORITY/tasks")

    const val SCOPE_WAITING = "waiting"
    const val SCOPE_UPCOMING = "upcoming"
    const val PARAM_SCOPE = "scope"
    const val PARAM_LIMIT = "limit"

    const val COL_TASK_ID = "task_id"
    const val COL_TITLE = "title"
    const val COL_START_AT = "start_at"
    const val COL_DUE_AT = "due_at"
    const val COL_PRIORITY = "priority"
    const val COL_LIST_ID = "list_id"
    const val COL_LIST_NAME = "list_name"
    const val COL_WORKSPACE_ID = "workspace_id"
    const val COL_OVERDUE = "overdue"

    /** 写入快速添加：`title` 必填，`list_id` / `workspace_id` 可选。 */
    const val COL_LIST_ID_INPUT = "list_id"

    /** `call` 方法：勾选/取消完成。 */
    const val METHOD_SET_DONE = "set_done"
    const val ARG_TASK_ID = "task_id"
    const val EXTRA_DONE = "done"
    const val EXTRA_OK = "ok"

    /** 「即将开始」只统计未来 36 小时内会开始的任务。 */
    const val UPCOMING_WINDOW_MS = 36L * 60L * 60L * 1000L

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    /** 唯一允许跨进程调用本 provider 的包名（注入输入法的宿主）。 */
    const val CALLER_PACKAGE = "com.nirenr.trime"

    val READ_COLUMNS = arrayOf(
        COL_TASK_ID,
        COL_TITLE,
        COL_START_AT,
        COL_DUE_AT,
        COL_PRIORITY,
        COL_LIST_ID,
        COL_LIST_NAME,
        COL_WORKSPACE_ID,
        COL_OVERDUE,
    )
}
