package com.todo.android.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.db.TaskLocalEntity
import com.todo.android.core.data.db.TaskLocalDao
import com.todo.android.core.data.settings.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: ReminderScheduler

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var taskLocalDao: TaskLocalDao

    @Inject
    lateinit var projectionHolder: ProjectionHolder

    @Inject
    lateinit var notifier: ReminderNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val remindAt = intent.getLongExtra(EXTRA_REMIND_AT, 0L)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = settingsStore.current()
                val task = projectionHolder.projection.value.tasks.firstOrNull { it.id == taskId }
                val title = intent.getStringExtra(EXTRA_TITLE) ?: task?.title
                val inDnd = settings.dndEnabled &&
                    ReminderScheduler.isInDnd(System.currentTimeMillis(), settings.dndStart, settings.dndEnd)

                // 冷启动时投影可能还没加载（task==null）：用 PendingIntent 里带的标题兜底，
                // 否则会出现「闹钟触发了、进程也起来了，但通知没弹、还记了已触发」的丢提醒。
                val shouldNotify = (task == null || !task.done) &&
                    title != null && !inDnd && scheduler.canPostNotifications()
                if (shouldNotify) {
                    notifier.notifyReminder(taskId, title!!)
                }
                // 免打扰时段内静默，但仍记录 last_fired_at，避免重复补发。
                taskLocalDao.put(
                    (taskLocalDao.get(taskId) ?: TaskLocalEntity(taskId))
                        .copy(lastFiredAt = remindAt, snoozeUntil = null),
                )
                scheduler.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_REMIND_AT = "remind_at"
        const val EXTRA_TITLE = "title"
        const val CHANNEL_REMINDER = "reminder"
    }
}
