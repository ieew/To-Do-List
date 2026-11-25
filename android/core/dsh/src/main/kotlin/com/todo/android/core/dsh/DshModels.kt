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
    /** 服务端确认吊销的时刻；0 表示未撤销。仅用于界面展示，不参与任何拦截。 */
    val imeRevokedAt: Long = 0,
    /** 本地已停发但服务端还没收到（断网撤销）：被偷的凭证这期间仍然有效。 */
    val imeRevokePending: Boolean = false,
    /** 插件上报的 dex 版本与协议版本（§3.0.1）；空/0 表示插件太旧、根本没上报。 */
    val imeVersion: String = "",
    val imeProtocol: Int = 0,
) {
    /** 撤销的两种状态都算「输入法通道已停」，但只有 pending 为假时才算真正撤干净。 */
    val imeRevoked: Boolean get() = imeRevokedAt > 0 || imeRevokePending

    /** 插件讲的协议比 App 旧：降级使用，提示升级 dex。0 视为「没上报」，也按旧处理。 */
    val imeProtocolOutdated: Boolean
        get() = imeProtocol in 0 until DshProtocol.VERSION

    /** 插件讲的协议比 App 新：App 侧忽略不认识的能力，提示升级 App。 */
    val appProtocolOutdated: Boolean get() = imeProtocol > DshProtocol.VERSION
}

/** §3.0.1：协议只增不改。新能力放新字段/新方法，旧端不认识就跳过。 */
object DshProtocol {
    /** App 期望的插件协议版本。插件 status() 里的 `protocol` 与它比对。 */
    const val VERSION = 1
}

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
