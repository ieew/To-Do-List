package com.todo.android.core.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.todo.android.core.data.db.LocalHlcEntity
import com.todo.android.core.data.db.LocalPrefEntity
import com.todo.android.core.data.db.MetaEntity
import com.todo.android.core.data.db.MetaDao
import com.todo.android.core.data.db.OpLogDao
import com.todo.android.core.data.db.OpSyncState
import com.todo.android.core.data.db.PreferenceDao
import com.todo.android.core.data.db.PreferenceEntity
import com.todo.android.core.data.db.LocalPrefDao
import com.todo.android.core.data.db.LocalHlcDao
import com.todo.android.core.data.db.TodoDatabase
import com.todo.android.core.domain.Hlc
import com.todo.android.core.domain.HlcClock
import com.todo.android.core.domain.Op
import com.todo.android.core.domain.Projection
import com.todo.android.core.domain.WorkspaceProjection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class OpRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: TodoDatabase,
    private val opLogDao: OpLogDao,
    private val metaDao: MetaDao,
    private val preferenceDao: PreferenceDao,
    private val localPrefDao: LocalPrefDao,
    private val localHlcDao: LocalHlcDao,
    private val wallClock: WallClock,
    private val syncTrigger: SyncTrigger,
    private val dataChangeListener: DataChangeListener,
) : OpRepository {
    private val deviceMutex = Mutex()

    override fun observeProjection(): Flow<WorkspaceProjection> = combine(
        opLogDao.observeLive(),
        preferenceDao.observe(KEY_ACTIVE_WORKSPACE),
        localPrefDao.observe(KEY_CURRENT_USER),
    ) { rows, activeWorkspace, currentUser ->
        val ops = rows.mapNotNull(OpMapper::toDomain)
        ProjectionTimestamps.enrich(Projection.build(ops, activeWorkspace, currentUser.orEmpty()))
    }.distinctUntilChanged()

    override suspend fun appendOp(workspaceId: String, build: (HlcClock) -> Op): Op =
        appendOps(workspaceId) { clock -> listOf(build(clock)) }.first()

    override suspend fun appendOps(workspaceId: String, build: (HlcClock) -> List<Op>): List<Op> {
        val now = wallClock.nowMs()
        val ops = db.withTransaction {
            val device = deviceId()
            val current = localHlcDao.get()
                ?: LocalHlcEntity(physicalMs = now, counter = 0, nodeId = device).also {
                    localHlcDao.put(it)
                }
            val clock = HlcClock(Hlc(current.physicalMs, current.counter, current.nodeId), now)
            val built = build(clock)
            built.forEach { op ->
                require(op.workspaceId == workspaceId) {
                    "op 的工作区与写入工作区不一致：${op.workspaceId} != $workspaceId"
                }
                opLogDao.insertIgnore(OpMapper.toEntity(op, createdAt = now))
            }
            val last = clock.last()
            localHlcDao.put(
                LocalHlcEntity(physicalMs = last.physicalMs, counter = last.counter, nodeId = last.nodeId),
            )
            built
        }
        // 事务外触发同步：禁止在事务内做网络或 emit。
        syncTrigger.requestSync()
        dataChangeListener.onChanged()
        return ops
    }

    override suspend fun loadAllOps(): List<Op> = opLogDao.loadAll().mapNotNull(OpMapper::toDomain)

    override suspend fun pendingOps(limit: Int): List<PendingOp> = opLogDao.loadPending(limit).mapNotNull { row ->
        OpMapper.toDomain(row)?.let { PendingOp(it, row.createdAt, row.rowId) }
    }

    override suspend fun counts(): OpCounts = OpCounts(
        pending = opLogDao.pendingCount(),
        rejected = opLogDao.rejectedCount(),
    )

    override suspend fun markSynced(items: List<Pair<String, Long>>) {
        db.withTransaction {
            items.forEach { (opId, seq) -> opLogDao.markSynced(opId, seq) }
        }
    }

    override suspend fun markRejected(opId: String, error: String) {
        opLogDao.markRejected(opId, error)
    }

    override suspend fun applyPulled(ops: List<PulledOp>, nextSeq: Long, serverHlc: Hlc?): Int {
        val now = wallClock.nowMs()
        val applied = db.withTransaction {
            var count = 0
            ops.forEach { pulled ->
                val rowId = opLogDao.insertIgnore(
                    OpMapper.toEntity(
                        pulled.op,
                        createdAt = now,
                        syncState = OpSyncState.SYNCED,
                        serverSeq = pulled.seq,
                    ),
                )
                if (rowId >= 0) {
                    count++
                } else {
                    // 本地已有同一 op_id（刚 push 过或重复投递）：把 pending 推进为 synced。
                    opLogDao.markSyncedIfPending(pulled.op.opId, pulled.seq)
                }
            }
            metaDao.put(MetaEntity(KEY_SYNC_CURSOR, nextSeq.toString()))
            serverHlc?.let { remote ->
                val device = deviceId()
                val local = localHlcDao.get()
                    ?: LocalHlcEntity(physicalMs = now, counter = 0, nodeId = device)
                val observed = Hlc.observe(Hlc(local.physicalMs, local.counter, local.nodeId), remote, local.nodeId)
                localHlcDao.put(
                    LocalHlcEntity(
                        physicalMs = observed.physicalMs,
                        counter = observed.counter,
                        nodeId = observed.nodeId,
                    ),
                )
            }
            count
        }
        // pull 落库只更新投影；不应反过来再触发一轮同步。
        if (applied > 0) dataChangeListener.onChanged()
        return applied
    }

    override suspend fun importOps(ops: List<Op>): ImportResult {
        val now = wallClock.nowMs()
        val result = db.withTransaction {
            var inserted = 0
            var skipped = 0
            ops.forEach { op ->
                val rowId = opLogDao.insertIgnore(OpMapper.toEntity(op, createdAt = now))
                if (rowId >= 0) inserted++ else skipped++
            }
            ImportResult(inserted, skipped)
        }
        syncTrigger.requestSync()
        dataChangeListener.onChanged()
        return result
    }

    override suspend fun currentHlc(): Hlc {
        val device = deviceId()
        val row = localHlcDao.get() ?: return Hlc(wallClock.nowMs(), 0, device)
        return Hlc(row.physicalMs, row.counter, row.nodeId)
    }

    override suspend fun observeHlc(remote: Hlc) {
        db.withTransaction {
            val device = deviceId()
            val local = localHlcDao.get()
                ?: LocalHlcEntity(physicalMs = wallClock.nowMs(), counter = 0, nodeId = device)
            val observed = Hlc.observe(Hlc(local.physicalMs, local.counter, local.nodeId), remote, local.nodeId)
            localHlcDao.put(
                LocalHlcEntity(
                    physicalMs = observed.physicalMs,
                    counter = observed.counter,
                    nodeId = observed.nodeId,
                ),
            )
        }
    }

    override suspend fun syncCursor(): Long = metaDao.get(KEY_SYNC_CURSOR)?.toLongOrNull() ?: 0L

    override suspend fun setSyncCursor(cursor: Long) {
        metaDao.put(MetaEntity(KEY_SYNC_CURSOR, cursor.toString()))
    }

    override suspend fun syncAccount(): String? = metaDao.get(KEY_SYNC_ACCOUNT)

    override suspend fun setSyncAccount(account: String?) {
        if (account == null) {
            metaDao.delete(KEY_SYNC_ACCOUNT)
        } else {
            metaDao.put(MetaEntity(KEY_SYNC_ACCOUNT, account))
        }
    }

    override suspend fun resetForAccount(): String? {
        val backup = if (opLogDao.loadAll().isNotEmpty()) backupDatabase() else null
        db.withTransaction {
            opLogDao.deleteAll()
            metaDao.deleteAll()
            val device = deviceId()
            localHlcDao.put(LocalHlcEntity(physicalMs = wallClock.nowMs(), counter = 0, nodeId = device))
        }
        return backup
    }

    override suspend fun deviceId(): String = deviceMutex.withLock {
        metaDao.get(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also {
            metaDao.put(MetaEntity(KEY_DEVICE_ID, it))
        }
    }

    override suspend fun currentUserId(): String = localPrefDao.get(KEY_CURRENT_USER).orEmpty()

    override suspend fun setCurrentUserId(userId: String) {
        localPrefDao.put(LocalPrefEntity(KEY_CURRENT_USER, userId))
    }

    override suspend fun activeWorkspaceId(): String? = preferenceDao.get(KEY_ACTIVE_WORKSPACE)

    override suspend fun setActiveWorkspaceId(workspaceId: String) {
        preferenceDao.put(PreferenceEntity(KEY_ACTIVE_WORKSPACE, workspaceId))
    }

    /** 换账户前整库备份；失败不影响后续清理。 */
    private fun backupDatabase(): String? = runCatching {
        val dbFile = context.getDatabasePath(TodoDatabase.NAME)
        if (!dbFile.exists()) return null
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wallClock.nowMs()))
        val target = File(dir, "todo-$stamp.db")
        dbFile.copyTo(target, overwrite = true)
        target.absolutePath
    }.getOrNull()

    companion object {
        const val KEY_SYNC_CURSOR = "sync_cursor"
        const val KEY_SYNC_ACCOUNT = "sync_account"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACTIVE_WORKSPACE = "active_workspace_id"
        const val KEY_CURRENT_USER = "current_user_id"
    }
}
