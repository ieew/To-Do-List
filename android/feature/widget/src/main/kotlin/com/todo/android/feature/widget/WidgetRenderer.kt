package com.todo.android.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.TaskScope
import com.todo.android.core.domain.TaskScoping
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): OpRepository

    fun taskCommands(): com.todo.android.core.data.repository.TaskCommands
}

/** 桌面卡片渲染：三种尺寸共用同一布局，按可用行数自适应。 */
object WidgetRenderer {
    const val ACTION_TOGGLE = "com.todo.android.widget.TOGGLE"
    const val EXTRA_TASK_ID = "task_id"
    const val MAX_ROWS = 5

    fun build(context: Context, pending: List<TaskItem>, done: Int, total: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_todo)
        val percent = if (total == 0) 0 else done * 100 / total
        views.setTextViewText(R.id.widget_header, "今天 $done/$total")
        views.setTextViewText(R.id.widget_percent, "$percent%")
        views.setProgressBar(R.id.widget_progress, 100, percent, false)

        views.removeAllViews(R.id.widget_rows)
        if (pending.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
            pending.take(MAX_ROWS).forEach { task ->
                views.addView(R.id.widget_rows, rowViews(context, task))
            }
        }

        views.setOnClickPendingIntent(R.id.widget_add, openApp(context, null))
        return views
    }

    private fun rowViews(context: Context, task: TaskItem): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_task_row)
        row.setTextViewText(R.id.row_title, task.title)
        row.setTextViewText(R.id.row_due, dueLabel(task))
        row.setOnClickPendingIntent(R.id.row_check, toggle(context, task.id))
        row.setOnClickPendingIntent(R.id.row_title, openApp(context, task.id))
        return row
    }

    private fun toggle(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TodoWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
            data = Uri.parse("todo://widget/toggle/$taskId")
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(context: Context, taskId: String?): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (taskId != null) putExtra("task_id", taskId)
        }
        return PendingIntent.getActivity(
            context,
            (taskId ?: "app").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dueLabel(task: TaskItem): String {
        val due = task.dueAtMs ?: return "无截止"
        return Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm")) + " 截止"
    }

    suspend fun snapshot(context: Context): Triple<List<TaskItem>, Int, Int> {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val projection = entryPoint.repository().observeProjection().first()
        val now = System.currentTimeMillis()
        val groups = TaskScoping.group(
            TaskScoping.viewTasks(projection.tasks, TaskScope.Today, now),
            now,
        )
        return Triple(groups.today, groups.completed.size, groups.today.size + groups.completed.size)
    }

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val (pending, done, total) = snapshot(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, build(context, pending, done, total))
        }
    }
}
