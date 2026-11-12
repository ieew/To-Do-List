package com.todo.android.feature.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 桌面卡片：勾选走 op 管道，点任务深链到详情，点底部添加打开 App。 */
class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val (tasks, done, total) = WidgetRenderer.snapshot(context)
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(id, WidgetRenderer.build(context, tasks, done, total))
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRenderer.ACTION_TOGGLE) {
            val taskId = intent.getStringExtra(WidgetRenderer.EXTRA_TASK_ID) ?: return
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
                    val projection = entryPoint.repository().observeProjection().first()
                    val task = projection.tasks.firstOrNull { it.id == taskId }
                    if (task != null) {
                        entryPoint.taskCommands().setDone(taskId, projection.activeWorkspaceId, !task.done)
                    }
                    WidgetRenderer.updateAll(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
