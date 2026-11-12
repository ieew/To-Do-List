package com.todo.android.core.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒通知的唯一出口：准点提醒与补发提醒共用同一套构建逻辑，
 * 避免两个入口（闹钟广播 / 重排补偿）产生不同样式。
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyReminder(
        taskId: String,
        title: String,
        catchUp: Boolean = false,
        overdueLabel: String? = null,
    ) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context,
                taskId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val text = if (overdueLabel != null) "$title · $overdueLabel" else title
        val notification = NotificationCompat.Builder(context, ReminderReceiver.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (catchUp) "补发提醒" else "任务提醒")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(taskId.hashCode(), notification)
    }

    /** 把毫秒差格式化成「已逾期 X 分钟 / X 小时」。 */
    fun overdueLabel(lateByMs: Long): String? = when {
        lateByMs < 60_000L -> null
        lateByMs < 3_600_000L -> "已逾期 ${lateByMs / 60_000L} 分钟"
        lateByMs < 86_400_000L -> "已逾期 ${lateByMs / 3_600_000L} 小时"
        else -> "已逾期 ${lateByMs / 86_400_000L} 天"
    }
}
