package com.todo.android.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todo.android.core.data.settings.KeepAliveModes
import com.todo.android.core.data.settings.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 15 分钟心跳：进程被杀后由系统重新拉起，顺带重建提醒并续订下一次心跳。
 * 设置里关掉保活后，本次心跳起效即停止，不再续订。
 */
@AndroidEntryPoint
class HeartbeatReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: ReminderScheduler

    @Inject
    lateinit var keepAlive: KeepAliveManager

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = settingsStore.current()
                if (!KeepAliveModes.isEnabled(settings.keepAliveMode)) {
                    keepAlive.cancelHeartbeat()
                } else {
                    runCatching { scheduler.rescheduleAll() }
                    keepAlive.recordBeat()
                    keepAlive.scheduleHeartbeat()
                    if (settings.keepAliveMode == KeepAliveModes.SERVICE) {
                        keepAlive.startServiceSafely()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
