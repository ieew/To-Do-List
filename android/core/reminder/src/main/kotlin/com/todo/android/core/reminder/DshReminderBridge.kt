package com.todo.android.core.reminder

import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.db.TaskLocalDao
import com.todo.android.core.data.db.TaskLocalEntity
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.dsh.DshManager
import com.todo.android.core.network.wire.DshMessage
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 dsh 通道上的 `notify`（服务端提醒）接进 Android 的提醒管道。
 *
 * 「单一展示者」：展示前先在 `dsh_claim` 里认领 `workspace|task|remind_at`；
 * 抢不到说明输入法插件已经弹过，App 只 ack，不再重复提醒。
 */
@Singleton
class DshReminderBridge @Inject constructor(
    private val dsh: DshManager,
    private val notifier: ReminderNotifier,
    private val settingsStore: SettingsStore,
    private val projectionHolder: ProjectionHolder,
    private val taskLocalDao: TaskLocalDao,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            dsh.messages.collect { message ->
                runCatching { handle(message) }
            }
        }
    }

    private suspend fun handle(message: DshMessage) {
        if (message.kind != "notify") return
        val payload = message.payload as? JsonObject ?: return
        if (payload["type"]?.jsonPrimitive?.content != "reminder") return

        val taskId = payload["task_id"]?.jsonPrimitive?.content.orEmpty()
        val workspaceId = payload["workspace_id"]?.jsonPrimitive?.content.orEmpty()
        val rawRemindAt = payload["remind_at"]?.jsonPrimitive?.content.orEmpty()
        val remindAt = parseRfc3339(rawRemindAt) ?: 0L
        if (taskId.isEmpty()) return

        val settings = settingsStore.current()
        val now = System.currentTimeMillis()
        val inDnd = settings.dndEnabled &&
            ReminderScheduler.isInDnd(now, settings.dndStart, settings.dndEnd)
        val projection = projectionHolder.projection.value
        val task = projection.tasks.firstOrNull { it.id == taskId }
        val title = payload["title"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: task?.title
            ?: return
        if (task?.done == true) return

        val claimed = if (remindAt > 0) {
            dsh.claimReminder(workspaceId, taskId, remindAt, "app")
        } else {
            true
        }
        if (!claimed) return

        // 免打扰 / 关闭通知时只记账，保证下次不重复补发。
        if (settings.notifications && !inDnd) {
            notifier.notifyReminder(taskId = taskId, title = title, catchUp = true)
        }
        if (remindAt > 0) {
            val current = taskLocalDao.get(taskId) ?: TaskLocalEntity(taskId)
            taskLocalDao.put(
                current.copy(
                    lastFiredAt = remindAt,
                    snoozeUntil = null,
                    firedSource = "server",
                    firedAt = now,
                ),
            )
        }
    }

    private fun parseRfc3339(raw: String): Long? = runCatching {
        OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }.getOrNull()
}
