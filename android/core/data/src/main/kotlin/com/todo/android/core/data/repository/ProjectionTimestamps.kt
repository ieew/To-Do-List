package com.todo.android.core.data.repository

import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.WorkspaceProjection

/** 给投影任务补上毫秒时间戳：范围过滤与日历排布只需要毫秒，领域层不解析时间字符串。 */
internal object ProjectionTimestamps {
    fun enrich(projection: WorkspaceProjection): WorkspaceProjection {
        if (projection.tasks.isEmpty()) return projection
        return projection.copy(
            tasks = projection.tasks.map { task ->
                task.copy(
                    startAtMs = IsoTime.parseMs(task.startAt),
                    dueAtMs = IsoTime.parseMs(task.dueAt),
                    remindAtMs = IsoTime.parseMs(task.remindAt),
                )
            },
        )
    }
}
