package com.todo.android.core.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.todo.android.core.data.db.LocalPrefDao
import com.todo.android.core.data.db.LocalPrefEntity
import com.todo.android.core.data.settings.KeepAliveModes
import com.todo.android.core.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 心跳统计，设置页用它证明保活在真机上确实生效。 */
data class KeepAliveStats(
    val lastBeatMs: Long? = null,
    val beatCount: Long = 0L,
)

/**
 * 后台存活管理，三层叠加：
 * 1. AlarmManager 链式心跳（15 分钟）——进程被杀也会被系统拉起；
 * 2. WorkManager 周期任务（15 分钟）——重启/更新后由系统恢复调度；
 * 3. 可选前台服务（`service` 模式）——被划掉最近任务后 2 秒重拉。
 *
 * 提醒本身走精确闹钟，所以即使进程不在，闹钟照样触发。
 */
@Singleton
class KeepAliveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val localPrefDao: LocalPrefDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 冷启动调用：按设置启用保活。 */
    fun bootstrap() {
        scope.launch {
            runCatching { applyMode(settingsStore.current().keepAliveMode) }
        }
    }

    suspend fun applyMode(mode: String) {
        when (mode) {
            KeepAliveModes.OFF -> {
                cancelHeartbeat()
                cancelWorker()
                stopService()
            }

            KeepAliveModes.SERVICE -> {
                scheduleHeartbeat()
                scheduleWorker()
                startServiceSafely()
            }

            else -> {
                // heartbeat（默认）：无常驻通知，靠闹钟 + WorkManager 复活。
                stopService()
                scheduleHeartbeat()
                scheduleWorker()
            }
        }
    }

    // ---- 心跳 ----

    fun scheduleHeartbeat(delayMs: Long = HEARTBEAT_INTERVAL_MS) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, heartbeatIntent())
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC_WAKEUP, at, heartbeatIntent())
        }
    }

    fun cancelHeartbeat() {
        context.getSystemService(AlarmManager::class.java)?.cancel(heartbeatIntent())
    }

    private fun heartbeatIntent(): PendingIntent {
        val intent = Intent(context, HeartbeatReceiver::class.java).apply {
            data = Uri.parse("todo://keepalive/heartbeat")
        }
        return PendingIntent.getBroadcast(context, REQUEST_HEARTBEAT, intent, PI_FLAGS)
    }

    // ---- WorkManager 兜底 ----

    fun scheduleWorker() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<KeepAliveWorker>(HEARTBEAT_INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
            )
        }
    }

    fun cancelWorker() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    // ---- 常驻服务 ----

    fun startServiceSafely(): Boolean = try {
        val intent = Intent(context, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        true
    } catch (_: Throwable) {
        // ForegroundServiceStartNotAllowedException（Android 12+ 后台限制）等：
        // 起不来就退回心跳，不打扰用户。
        false
    }

    fun stopService() {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    /** 划掉最近任务后延迟把服务拉回来（默认 2 秒）。 */
    fun scheduleServiceRestart(delayMs: Long = RESTART_DELAY_MS) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, restartIntent())
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC_WAKEUP, at, restartIntent())
        }
    }

    private fun restartIntent(): PendingIntent {
        val intent = Intent(context, KeepAliveRestartReceiver::class.java).apply {
            data = Uri.parse("todo://keepalive/restart")
        }
        return PendingIntent.getBroadcast(context, REQUEST_RESTART, intent, PI_FLAGS)
    }

    // ---- 心跳记账 ----

    suspend fun recordBeat() {
        val count = localPrefDao.get(KEY_BEAT_COUNT)?.toLongOrNull() ?: 0L
        localPrefDao.put(LocalPrefEntity(KEY_LAST_BEAT, System.currentTimeMillis().toString()))
        localPrefDao.put(LocalPrefEntity(KEY_BEAT_COUNT, (count + 1).toString()))
    }

    suspend fun stats(): KeepAliveStats = KeepAliveStats(
        lastBeatMs = localPrefDao.get(KEY_LAST_BEAT)?.toLongOrNull(),
        beatCount = localPrefDao.get(KEY_BEAT_COUNT)?.toLongOrNull() ?: 0L,
    )

    // ---- 系统设置引导 ----

    fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 电池优化白名单：先直接申请，退到列表页，最后退到应用详情。 */
    fun requestIgnoreBatteryOptimizations(): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startActivity(direct)) return true
        if (startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            return true
        }
        return openAppDetails()
    }

    /** 厂商自启管理页；找不到就退到应用详情。 */
    fun openAutoStartSettings(): Boolean {
        AUTO_START_COMPONENTS.forEach { (pkg, cls) ->
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startActivity(intent)) return true
        }
        return openAppDetails()
    }

    fun openNotificationChannelSettings(channelId: String): Boolean {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startActivity(intent) || openAppDetails()
    }

    fun openAppDetails(): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivity(intent)
    }

    private fun startActivity(intent: Intent): Boolean = try {
        if (intent.resolveActivity(context.packageManager) == null) {
            false
        } else {
            context.startActivity(intent)
            true
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MINUTES = 15L
        const val HEARTBEAT_INTERVAL_MS = HEARTBEAT_INTERVAL_MINUTES * 60_000L
        const val RESTART_DELAY_MS = 2_000L

        const val WORK_NAME = "todo-keepalive"
        const val CHANNEL_KEEPALIVE = "keepalive"

        private const val REQUEST_HEARTBEAT = 7_301
        private const val REQUEST_RESTART = 7_302
        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private const val KEY_LAST_BEAT = "keepalive_last_beat"
        private const val KEY_BEAT_COUNT = "keepalive_beat_count"

        /** 常见厂商的自启管理页；命中第一个可用的即跳转。 */
        private val AUTO_START_COMPONENTS = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
        )
    }
}
