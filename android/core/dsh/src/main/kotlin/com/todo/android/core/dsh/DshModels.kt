package com.todo.android.core.dsh

/** 数据通道形态：关闭 / 依赖输入法插件 / App 自建 MQTT / 仅轮询。 */
object DshMode {
    const val OFF = "off"
    const val IME = "ime"
    const val SELF = "self"
    const val POLL = "poll"

    fun label(mode: String): String = when (mode) {
        OFF -> "已关闭"
        IME -> "经输入法通道"
        SELF -> "App 自建通道"
        POLL -> "仅轮询"
        else -> mode
    }
}

/** 设置页与 provider 共用的通道状态快照。 */
data class DshStatus(
    val enabled: Boolean = false,
    val mode: String = DshMode.OFF,
    val appDeviceId: String = "",
    val imeDeviceId: String = "",
    val connected: Boolean = false,
    val cursor: Long = 0,
    val lastPullAt: Long = 0,
    val lastNudgeAt: Long = 0,
    val lastError: String = "",
)

/**
 * 服务端 `{"t":"sync","seq":N,"ws":[...]}`：
 * `seq` 是 push 后的全局 server_seq（可比本地游标决定是否跳过），
 * `ws` 是受影响工作区（客户端可只拉这些；当前仍走全局 pull，保留给后续优化）。
 */
data class DshSyncSignal(
    val seq: Long = 0,
    val workspaces: List<String> = emptyList(),
)

/** 与输入法插件共用的常量（provider authority 在 feature:ime 也有同名定义）。 */
object DshContract {
    const val AUTHORITY = "com.todo.android.ime"
    const val IME_HOST_PACKAGE = "com.nirenr.trime"

    const val METHOD_AUTHORIZE = "dsh_authorize"
    const val METHOD_CREDENTIAL_UPDATE = "dsh_credential_update"
    const val METHOD_STATUS = "dsh_status"
    const val METHOD_NUDGE = "dsh_nudge"
    const val METHOD_CLAIM = "dsh_claim_reminder"

    const val EXTRA_SERVER = "server"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_DEVICE_ID = "device_id"
    const val EXTRA_CREDENTIAL = "credential"
    const val EXTRA_CREDENTIAL_EXP = "credential_expires_at"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_ERROR = "error"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_WORKSPACE_ID = "workspace_id"
    const val EXTRA_REMIND_AT = "remind_at"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_CLAIMED = "claimed"
    const val EXTRA_DND_ENABLED = "dnd_enabled"
    const val EXTRA_DND_START = "dnd_start"
    const val EXTRA_DND_END = "dnd_end"
    const val EXTRA_REMINDER_LEAD = "reminder_lead"
    const val EXTRA_NOTIFICATIONS = "notifications"
    const val EXTRA_AVAILABLE = "available"
}
