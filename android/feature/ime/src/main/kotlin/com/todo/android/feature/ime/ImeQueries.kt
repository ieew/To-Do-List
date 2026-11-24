package com.todo.android.feature.ime

import com.todo.android.core.domain.TaskItem

/**
 * 插件要的两类任务的纯函数筛选与排序。
 *
 * - 等待完成：未完成、且已开始（`start_at` 为空或已过）。逾期任务排最前，之后按截止时间、
 *   开始时间、order 排序；没有截止时间的排在有截止时间的后面。
 * - 即将开始：未完成、`start_at` 在未来 36 小时内，按开始时间升序。
 *
 * 与 `TaskScoping` 的「今天 / 即将到来」同义，但这里需要「逾期优先」的排序，
 * 所以单独实现并单测，避免改动全局分组语义。
 */
object ImeQueries {

    fun isOverdue(task: TaskItem, nowMs: Long): Boolean {
        val dueAtMs = task.dueAtMs
        return !task.done && dueAtMs != null && dueAtMs < nowMs
    }

    fun waiting(tasks: List<TaskItem>, nowMs: Long, limit: Int): List<TaskItem> =
        tasks.asSequence()
            .filter {
                val startAtMs = it.startAtMs
                !it.done && (startAtMs == null || startAtMs <= nowMs)
            }
            .sortedWith(
                compareBy<TaskItem> { if (isOverdue(it, nowMs)) 0 else 1 }
                    .thenBy { it.dueAtMs ?: Long.MAX_VALUE }
                    .thenBy { it.startAtMs ?: Long.MIN_VALUE }
                    .thenBy { it.order }
                    .thenBy { it.id },
            )
            .take(clampLimit(limit))
            .toList()

    fun upcoming(tasks: List<TaskItem>, nowMs: Long, limit: Int): List<TaskItem> =
        tasks.asSequence()
            .filter {
                val startAtMs = it.startAtMs
                !it.done && startAtMs != null && startAtMs > nowMs && startAtMs <= nowMs + ImeContract.UPCOMING_WINDOW_MS
            }
            .sortedWith(compareBy<TaskItem> { it.startAtMs }.thenBy { it.order }.thenBy { it.id })
            .take(clampLimit(limit))
            .toList()

    fun clampLimit(limit: Int): Int = when {
        limit <= 0 -> ImeContract.DEFAULT_LIMIT
        limit > ImeContract.MAX_LIMIT -> ImeContract.MAX_LIMIT
        else -> limit
    }
}
