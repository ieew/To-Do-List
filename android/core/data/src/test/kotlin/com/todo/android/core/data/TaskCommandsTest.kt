package com.todo.android.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todo.android.core.data.db.TodoDatabase
import com.todo.android.core.data.repository.DataChangeListener
import com.todo.android.core.data.repository.OpRepositoryImpl
import com.todo.android.core.data.repository.SyncTrigger
import com.todo.android.core.data.repository.TaskCommands
import com.todo.android.core.data.repository.WallClock
import com.todo.android.core.domain.Fields
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 服务端 `_is_number` 要求 `|order| <= 1e9`；早期实现拿 epoch 毫秒（约 1.7e12）当 order，
 * 会让整条 task 的 op 全部被拒，并在响应解析阶段把 App 打闪退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCommandsTest {
    private lateinit var db: TodoDatabase
    private lateinit var repository: OpRepositoryImpl
    private lateinit var commands: TaskCommands
    private var now = 1_762_000_000_000L

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
            syncTrigger = SyncTrigger { },
            dataChangeListener = DataChangeListener { },
        )
        commands = TaskCommands(repository, WallClock { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `新建任务的 order 在服务端允许范围内`() = runBlocking {
        val taskId = create("刷牙")
        val raw = orderOf(taskId)
        assertTrue("order 必须在 ±1e9 之内，实际 $raw", kotlin.math.abs(raw) <= 1_000_000_000.0)
    }

    @Test
    fun `连续创建的任务 order 递增`() = runBlocking {
        val first = orderOf(create("A"))
        val second = orderOf(create("B"))
        assertEquals(1.0, second - first, 0.0001)
    }

    private suspend fun create(title: String): String = commands.createTask(
        workspaceId = "ws-1",
        listId = "ws-1-today",
        title = title,
        startAtIso = "2026-01-01T09:00:00+08:00",
        dueAtIso = "2026-01-08T09:00:00+08:00",
    )

    private suspend fun orderOf(taskId: String): Double {
        val op = repository.loadAllOps().first { it.entityId == taskId && it.field == Fields.ORDER }
        return (op.value as JsonPrimitive).content.toDouble()
    }
}
