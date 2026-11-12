package com.todo.android.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todo.android.core.data.db.OpSyncState
import com.todo.android.core.data.db.TodoDatabase
import com.todo.android.core.data.repository.OpRepositoryImpl
import com.todo.android.core.data.repository.PulledOp
import com.todo.android.core.data.repository.SyncTrigger
import com.todo.android.core.data.repository.WallClock
import com.todo.android.core.domain.EntityType
import com.todo.android.core.domain.Fields
import com.todo.android.core.domain.Hlc
import com.todo.android.core.domain.Op
import com.todo.android.core.domain.OpOperation
import com.todo.android.core.domain.OpSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpRepositoryTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: OpRepositoryImpl
    private var now = 1_000_000L
    private var syncRequests = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OpRepositoryImpl(
            context = context,
            db = db,
            opLogDao = db.opLogDao(),
            metaDao = db.metaDao(),
            preferenceDao = db.preferenceDao(),
            localPrefDao = db.localPrefDao(),
            localHlcDao = db.localHlcDao(),
            wallClock = WallClock { now },
            syncTrigger = SyncTrigger { syncRequests++ },
            dataChangeListener = com.todo.android.core.data.repository.DataChangeListener { },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `写入 op 后进入 pending 并推进本地 HLC`() = runBlocking {
        repository.appendOps("ws-1") { clock ->
            listOf(
                op("op-1", clock.next(), Fields.TITLE, "A"),
                op("op-2", clock.next(), Fields.TITLE, "B"),
            )
        }

        val pending = repository.pendingOps()
        assertEquals(2, pending.size)
        assertTrue("HLC 应随每次发号递增", pending[1].op.hlc.counter > pending[0].op.hlc.counter)
        assertEquals(1, syncRequests)
    }

    @Test
    fun `重复 op_id 写入保持幂等`() = runBlocking {
        val first = repository.appendOps("ws-1") { clock -> listOf(op("op-1", clock.next(), Fields.TITLE, "A")) }
        val second = repository.appendOps("ws-1") { clock -> listOf(op("op-1", clock.next(), Fields.TITLE, "A")) }

        assertEquals(first.size, second.size)
        assertEquals(1, db.opLogDao().loadAll().size)
    }

    @Test
    fun `rejected op 不参与投影`() = runBlocking {
        repository.appendOps("ws-1") { clock ->
            listOf(
                op("op-1", clock.next(), Fields.TITLE, "A"),
                op("op-2", clock.next(), Fields.TITLE, "B"),
            )
        }
        repository.markRejected("op-2", "forbidden: 当前角色无权执行此操作")

        val live = db.opLogDao().loadLive()
        assertEquals(listOf("op-1"), live.map { it.opId })
        assertEquals(1, repository.counts().rejected)
        assertNotNull(db.opLogDao().loadAll().firstOrNull { it.syncState == OpSyncState.REJECTED })
    }

    @Test
    fun `pull 落库推进游标并吸收 server_hlc`() = runBlocking {
        val remote = Hlc(physicalMs = 2_000_000, counter = 3, nodeId = "srv")
        val pulled = listOf(PulledOp(op("op-remote", Hlc(2_000_000, 1, "srv"), Fields.TITLE, "远端"), 42))
        repository.applyPulled(pulled, nextSeq = 42, serverHlc = remote)

        assertEquals(42L, repository.syncCursor())
        val local = repository.currentHlc()
        assertTrue("本地 HLC 应前进到远端物理时间", local.physicalMs >= 2_000_000)
    }

    @Test
    fun `导入重复 op 全部跳过`() = runBlocking {
        val ops = listOf(op("op-1", Hlc(10, 0, "node-a"), Fields.TITLE, "A"))
        val first = repository.importOps(ops)
        val second = repository.importOps(ops)
        assertEquals(1, first.inserted)
        assertEquals(1, second.skipped)
    }

    @Test
    fun `投影跟随 op_log 变化`() = runBlocking {
        repository.setCurrentUserId("u-1")
        repository.setActiveWorkspaceId("ws-1")
        repository.appendOps("ws-1") { clock ->
            listOf(
                op("ws-created", clock.next(), Fields.CREATED, "2026-11-01T09:00:00+08:00", EntityType.WORKSPACE, "ws-1"),
                op("ws-name", clock.next(), Fields.NAME, "我的空间", EntityType.WORKSPACE, "ws-1"),
                op("member", clock.next(), Fields.ROLE, "owner", EntityType.MEMBERSHIP, "ws-1:u-1"),
                op("list-ws", clock.next(), Fields.WORKSPACE_ID, "ws-1", EntityType.LIST, "list-1"),
                op("task-created", clock.next(), Fields.CREATED, "2026-11-01T10:00:00+08:00", EntityType.TASK, "task-1"),
                op("task-list", clock.next(), Fields.LIST_ID, "list-1", EntityType.TASK, "task-1"),
                op("task-title", clock.next(), Fields.TITLE, "写文档", EntityType.TASK, "task-1"),
            )
        }

        val projection = repository.observeProjection().first()
        assertEquals("ws-1", projection.activeWorkspaceId)
        assertEquals(listOf("写文档"), projection.tasks.map { it.title })
    }

    private fun op(
        opId: String,
        hlc: Hlc,
        field: String,
        value: String,
        entityType: EntityType = EntityType.TASK,
        entityId: String = "task-1",
    ): Op = Op(
        opId = opId,
        workspaceId = "ws-1",
        entityType = entityType,
        entityId = entityId,
        actorId = "u-1",
        deviceId = "dev-1",
        operation = OpOperation.SET,
        field = field,
        value = JsonPrimitive(value),
        hlc = hlc,
        source = OpSource.HUMAN,
    )
}
