package com.todo.android.core.data.repository

import com.todo.android.core.domain.Hlc
import com.todo.android.core.domain.HlcClock
import com.todo.android.core.domain.Op
import com.todo.android.core.domain.WorkspaceProjection
import kotlinx.coroutines.flow.Flow

/** 待推送的本地 op。 */
data class PendingOp(
    val op: Op,
    val createdAt: Long,
    val rowId: Long,
)

/** pull 下来的 op 与其服务端交付序号。 */
data class PulledOp(
    val op: Op,
    val seq: Long,
)

data class ImportResult(
    val inserted: Int,
    val skipped: Int,
)

data class OpCounts(
    val pending: Int,
    val rejected: Int,
)

/** 被服务端拒绝、但仍保留在本地库里的 op（A19：标记保留，可重试/可导出）。 */
data class RejectedOp(
    val opId: String,
    val error: String?,
    val createdAt: Long,
)

/** 换账号清库的结果：备份路径必须能被用户看到，否则「清库」是静默的数据丢失。 */
data class ResetResult(
    val backupPath: String?,
    val removedOps: Int,
)

/** 数据层只声明"需要触发一次同步"，具体实现由 core:sync 提供，避免反向依赖。 */
fun interface SyncTrigger {
    fun requestSync()
}

/** 便于测试替换系统时间；数据层是唯一允许读物理时钟的位置。 */
fun interface WallClock {
    fun nowMs(): Long
}

/** 本地数据变化后通知外部（桌面卡片等）；桌面卡片模块提供实现。 */
fun interface DataChangeListener {
    suspend fun onChanged()
}

/**
 * op 仓库：唯一写入口。
 *
 * - 所有业务写操作必须经 [appendOps]，在同一 Room 事务内完成；
 * - [observeProjection] 是 UI 读取投影的唯一来源；
 * - rejected 的 op 不参与投影，但保留在库中供排查与导出。
 */
interface OpRepository {
    fun observeProjection(): Flow<WorkspaceProjection>

    suspend fun appendOp(workspaceId: String, build: (HlcClock) -> Op): Op

    suspend fun appendOps(workspaceId: String, build: (HlcClock) -> List<Op>): List<Op>

    suspend fun loadAllOps(): List<Op>

    suspend fun pendingOps(limit: Int = 100): List<PendingOp>

    suspend fun counts(): OpCounts

    suspend fun markSynced(items: List<Pair<String, Long>>)

    suspend fun markRejected(opId: String, error: String)

    /** 整批标记被拒：HTTP 级失败时一次响应覆盖一整批 op。 */
    suspend fun markRejectedAll(opIds: List<String>, error: String)

    suspend fun rejectedOps(limit: Int = 200): List<RejectedOp>

    /** 把被拒 op 放回待推送队列，返回重回队列的条数。 */
    suspend fun retryRejected(): Int

    suspend fun applyPulled(ops: List<PulledOp>, nextSeq: Long, serverHlc: Hlc?): Int

    suspend fun importOps(ops: List<Op>): ImportResult

    suspend fun currentHlc(): Hlc

    suspend fun observeHlc(remote: Hlc)

    suspend fun syncCursor(): Long

    suspend fun setSyncCursor(cursor: Long)

    suspend fun syncAccount(): String?

    suspend fun setSyncAccount(account: String?)

    /**
     * 换账号：整库备份 + 清空 op_log/meta，并在**同一个事务**内写入新 accountKey。
     *
     * 先清后写会留下「库已清空但 sync_account 为空」的中间态，下次登录再清一次（A15）。
     */
    suspend fun resetForAccount(accountKey: String?): ResetResult

    /** 手动导出备份（登录前确认框的「先导出备份」）。 */
    suspend fun backupNow(): String?

    suspend fun deviceId(): String

    suspend fun currentUserId(): String

    suspend fun setCurrentUserId(userId: String)

    suspend fun activeWorkspaceId(): String?

    suspend fun setActiveWorkspaceId(workspaceId: String)
}
