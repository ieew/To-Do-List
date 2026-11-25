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
 * - 只暴露「两类任务的只读视图 + 快速添加 + 勾选完成 + 删除（墓碑）」，不暴露编辑/成员/历史；
 * - 删除必须由插件先弹二次确认再调用；
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

    /** `call` 方法：删除任务（写 `__deleted` 墓碑；插件侧已做二次确认）。 */
    const val METHOD_DELETE = "delete_task"

    // ---- dsh 数据通道（App ↔ 输入法插件）----
    /** 插件拉取授权：device_id + credential + 服务器 + 免打扰等设置快照。 */
    const val METHOD_DSH_AUTHORIZE = "dsh_authorize"
    /** 插件续期后回写轮换后的设备凭证。 */
    const val METHOD_DSH_CREDENTIAL_UPDATE = "dsh_credential_update"
    /** 插件回写 MQTT 连接状态（设置页展示）。 */
    const val METHOD_DSH_STATUS = "dsh_status"
    /** 插件收到 nudge，唤醒 App 立即 pull。 */
    const val METHOD_DSH_NUDGE = "dsh_nudge"
    /** 插件收到数据变更信号：唤醒 App 立即跑 op 同步（带 seq / ws）。 */
    const val METHOD_DSH_SYNC = "dsh_sync"
    /** 插件展示提醒前先认领，抢不到就不弹（防止与 App 重复提醒）。 */
    const val METHOD_DSH_CLAIM = "dsh_claim_reminder"

    // dsh 调用用到的 extras
    const val EXTRA_AVAILABLE = "available"
    const val EXTRA_SERVER = "server"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_DEVICE_ID = "device_id"
    const val EXTRA_CREDENTIAL = "credential"
    const val EXTRA_CREDENTIAL_EXP = "credential_expires_at"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_ERROR = "error"
    const val EXTRA_WORKSPACE_ID = "workspace_id"
    const val EXTRA_REMIND_AT = "remind_at"
    const val EXTRA_CLAIMED = "claimed"
    const val EXTRA_DND_ENABLED = "dnd_enabled"
    const val EXTRA_DND_START = "dnd_start"
    const val EXTRA_DND_END = "dnd_end"
    const val EXTRA_REMINDER_LEAD = "reminder_lead"
    const val EXTRA_NOTIFICATIONS = "notifications"
    const val EXTRA_SYNC_SEQ = "sync_seq"
    const val EXTRA_SYNC_WS = "sync_ws"

    const val ARG_TASK_ID = "task_id"
    const val EXTRA_DONE = "done"
    const val EXTRA_OK = "ok"
    const val EXTRA_TASK_ID = "task_id"

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
