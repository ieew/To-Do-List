package com.todo.android.core.sync

/** 同步状态机。UI 只读 [SyncEngine.state]。 */
enum class SyncStatus {
    SIGNED_OUT,
    SYNCING,
    SYNCED,
    OFFLINE,
    RETRYING,
    AUTH_REQUIRED,
}

data class AccountSnapshot(
    val userId: String,
    val username: String,
    val displayName: String?,
    val serverUrl: String,
)

data class SyncState(
    val status: SyncStatus = SyncStatus.SIGNED_OUT,
    val account: AccountSnapshot? = null,
    val pendingCount: Int = 0,
    val rejectedCount: Int = 0,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
    val cursor: Long = 0,
)

/** 提示条事件：红条为权限/登录问题，黄条为时钟漂移。不参与投影。 */
data class SyncNotice(
    val kind: NoticeKind,
    val code: String,
    val message: String,
)

enum class NoticeKind {
    WARNING,
    ERROR,
}
