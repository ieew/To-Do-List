package com.todo.android.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 划掉最近任务后 2 秒的重拉入口。
 * 这里不直接 startForegroundService，而是经过一次广播，
 * 便于吞掉 Android 12+ 的后台启动限制异常。
 */
@AndroidEntryPoint
class KeepAliveRestartReceiver : BroadcastReceiver() {
    @Inject
    lateinit var keepAlive: KeepAliveManager

    override fun onReceive(context: Context, intent: Intent) {
        keepAlive.startServiceSafely()
    }
}
