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

    suspend fun applyPulled(ops: List<PulledOp>, nextSeq: Long, serverHlc: Hlc?): Int

    suspend fun importOps(ops: List<Op>): ImportResult

    suspend fun currentHlc(): Hlc

    suspend fun observeHlc(remote: Hlc)

    suspend fun syncCursor(): Long

    suspend fun setSyncCursor(cursor: Long)

    suspend fun syncAccount(): String?

    suspend fun setSyncAccount(account: String?)

    suspend fun resetForAccount(): String?

    suspend fun deviceId(): String

    suspend fun currentUserId(): String

    suspend fun setCurrentUserId(userId: String)

    suspend fun activeWorkspaceId(): String?

    suspend fun setActiveWorkspaceId(workspaceId: String)
}
