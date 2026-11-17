package com.todo.android.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知上的「稍后 10 分钟 / 1 小时」。
 *
 * 只调整本地响铃时间（`task_local.snooze_until`），不写 `remind_at` 的 op，
 * 因此不会污染历史与同步；与 Windows 端 snooze 语义一致。
 */
@AndroidEntryPoint
class ReminderSnoozeReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 10)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                scheduler.snooze(taskId, minutes)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_MINUTES = "minutes"
    }
}
