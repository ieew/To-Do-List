package com.todo.android.core.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.db.TaskLocalDao
import com.todo.android.core.data.db.TaskLocalEntity
import com.todo.android.core.data.settings.SettingsStore
import com.todo.android.core.domain.TaskItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 提醒调度：API 31+ 检查精确闹钟权限，不可用时降级 `setWindow`。 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectionHolder: ProjectionHolder,
    private val settingsStore: SettingsStore,
    private val taskLocalDao: TaskLocalDao,
    private val notifier: ReminderNotifier,
) {
    /**
     * 重建全部提醒。已过点但从未发出过的提醒会在窗口期内补发一条，
     * 超过窗口则只静默记账，避免恢复时一次性炸屏。
     */
    suspend fun rescheduleAll(nowMs: Long = System.currentTimeMillis()): Int {
        val settings = settingsStore.current()
        val projection = projectionHolder.projection.value
        if (!settings.notifications) {
            cancelAll(projection.tasks)
            return 0
        }
        val local = taskLocalDao.all().associateBy { it.taskId }
        val canNotify = canPostNotifications()
        val inDnd = settings.dndEnabled && isInDnd(nowMs, settings.dndStart, settings.dndEnd)
        var scheduled = 0
        projection.tasks.forEach { task ->
            if (task.done) return@forEach
            val remindAt = task.remindAtMs ?: return@forEach
            val fired = local[task.id]?.lastFiredAt
            if (fired != null && fired >= remindAt) return@forEach
            if (remindAt > nowMs) {
                schedule(task.id, task.title, remindAt, settings.exactAlarm)
                scheduled++
                return@forEach
            }
            val lateBy = nowMs - remindAt
            if (lateBy <= CATCH_UP_WINDOW_MS && canNotify && !inDnd) {
                // 部分 ROM 会直接抛 SecurityException，不影响记账。
                runCatching {
                    notifier.notifyReminder(
                        taskId = task.id,
                        title = task.title,
                        catchUp = true,
                        overdueLabel = notifier.overdueLabel(lateBy),
                    )
                }
            }
            markFired(task.id, remindAt)
        }
        return scheduled
    }

    /** 幂等键：同一 remindAt 只处理一次。 */
    private suspend fun markFired(taskId: String, remindAt: Long) {
        val current = taskLocalDao.get(taskId) ?: TaskLocalEntity(taskId)
        taskLocalDao.put(current.copy(lastFiredAt = remindAt))
    }

    fun schedule(taskId: String, title: String, remindAtMs: Long, preferExact: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(taskId, remindAtMs)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            when {
                preferExact && canExact -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
                !canExact -> manager.setWindow(AlarmManager.RTC_WAKEUP, remindAtMs, 15 * 60_000L, pending)
                else -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
            }
        } catch (_: SecurityException) {
            manager.setWindow(AlarmManager.RTC_WAKEUP, remindAtMs, 15 * 60_000L, pending)
        }
    }

    fun cancel(taskId: String, remindAtMs: Long) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(taskId, remindAtMs))
    }

    /** PendingIntent 的 data URI 带 remindAt，取消失败往往是因为没带上真实时间。 */
    fun cancelAll(tasks: List<TaskItem>) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        tasks.forEach { task ->
            val remindAt = task.remindAtMs ?: return@forEach
            manager.cancel(pendingIntent(task.id, remindAt))
        }
    }

    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** PendingIntent 必须带唯一 data URI，否则同 requestCode 的提醒会互相覆盖。 */
    private fun pendingIntent(taskId: String, remindAtMs: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("todo://reminder/$taskId/$remindAtMs")
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderReceiver.EXTRA_REMIND_AT, remindAtMs)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** 过期超过这个窗口就不再补发，只记账。 */
        const val CATCH_UP_WINDOW_MS = 60L * 60_000L

        /** 免打扰时段支持跨天，例如 22:00–08:00。 */
        fun isInDnd(nowMs: Long, start: String, end: String, zone: ZoneId = ZoneId.systemDefault()): Boolean {
            val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
            val startTime = parseHhMm(start) ?: return false
            val endTime = parseHhMm(end) ?: return false
            return if (startTime <= endTime) {
                !now.isBefore(startTime) && now.isBefore(endTime)
            } else {
                !now.isBefore(startTime) || now.isBefore(endTime)
            }
        }

        private fun parseHhMm(raw: String): java.time.LocalTime? = runCatching {
            val parts = raw.trim().split(":")
            java.time.LocalTime.of(parts[0].toInt(), parts[1].toInt())
        }.getOrNull()
    }
}
