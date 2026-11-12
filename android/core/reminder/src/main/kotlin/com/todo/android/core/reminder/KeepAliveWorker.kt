package com.todo.android.core.reminder

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.todo.android.core.data.settings.KeepAliveModes
import com.todo.android.core.data.settings.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 周期兜底：WorkManager 的周期性任务在进程被杀、设备重启后仍会被系统恢复，
 * 因此它既是"复活"入口，也是心跳闹钟失效时的第二道保险。
 */
@HiltWorker
class KeepAliveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: ReminderScheduler,
    private val keepAlive: KeepAliveManager,
    private val settingsStore: SettingsStore,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = settingsStore.current()
        if (!KeepAliveModes.isEnabled(settings.keepAliveMode)) {
            keepAlive.cancelWorker()
            return Result.success()
        }
        runCatching { scheduler.rescheduleAll() }
        keepAlive.recordBeat()
        // 心跳闹钟可能因为重启/清理而丢失，这里顺手补一次。
        keepAlive.scheduleHeartbeat()
        return Result.success()
    }

    companion object {
        const val NAME = KeepAliveManager.WORK_NAME
    }
}
