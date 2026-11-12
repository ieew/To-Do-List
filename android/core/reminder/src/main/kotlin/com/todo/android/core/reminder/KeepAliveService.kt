package com.todo.android.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * `service` 模式下的常驻服务：
 * - `START_STICKY` 让系统在进程被回收后尽量重启；
 * - `onTaskRemoved` 后 2 秒用闹钟重拉一次（国产 ROM 划掉任务时的兜底）。
 *
 * 通知使用独立渠道 `keepalive`（IMPORTANCE_MIN + 静音），
 * 用户可以在系统通知设置里单独把这条渠道关掉而保留提醒通知。
 */
@AndroidEntryPoint
class KeepAliveService : Service() {
    @Inject
    lateinit var keepAlive: KeepAliveManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            START_STICKY
        } catch (_: Throwable) {
            // 后台启动前台服务被系统拒绝时不要崩溃，交给心跳兜底。
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        keepAlive.scheduleServiceRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        ensureChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, KeepAliveManager.CHANNEL_KEEPALIVE)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("待办提醒保持运行")
            .setContentText("后台心跳用于准时提醒，可单独关闭本通知渠道")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(KeepAliveManager.CHANNEL_KEEPALIVE) != null) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    KeepAliveManager.CHANNEL_KEEPALIVE,
                    "后台保活",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    companion object {
        const val NOTIFICATION_ID = 7_401
    }
}
