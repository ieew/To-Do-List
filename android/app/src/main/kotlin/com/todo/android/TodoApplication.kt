package com.todo.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.todo.android.core.reminder.KeepAliveManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TodoApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** 尽早安装崩溃记录器：即使 onCreate 本身抛异常也能落到文件里。 */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        runCatching { CrashLogger.install(this) }
    }

    override fun onCreate() {
        super.onCreate()
        // 启动期只做最必要的事：建通知渠道。厂商 ROM 限制一律降级，不得带崩进程。
        // 同步排程与保活放到首帧之后（AppViewModel.bootstrap），减少启动风险。
        runCatching { createNotificationChannels() }
    }

    /** API 26+ 必须先创建渠道，否则通知无效。 */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CHANNEL_REMINDER, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_SYNC, "同步状态", NotificationManager.IMPORTANCE_LOW),
            // 保活常驻通知单独渠道：用户可在系统设置里单独关掉它而不影响提醒。
            NotificationChannel(
                KeepAliveManager.CHANNEL_KEEPALIVE,
                "后台保活",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        ).forEach { channel ->
            runCatching { manager.createNotificationChannel(channel) }
        }
    }

    companion object {
        const val CHANNEL_REMINDER = "reminder"
        const val CHANNEL_SYNC = "sync"
    }
}
