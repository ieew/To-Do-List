package com.todo.android.core.ai

import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.WorkspaceProjection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 规模摘要：让模型知道总量，不必逐条读全量任务。 */
data class TaskSummary(
    val openTotal: Int,
    val overdue: Int,
    val dueToday: Int,
    val dueThisWeek: Int,
    val doneTotal: Int,
)

data class RetrievedTasks(
    val tasks: List<TaskItem>,
    val summary: TaskSummary,
    /** 没有任何关键词命中时，改用「逾期 / 本周 / 高优先级」兜底。 */
    val usedFallback: Boolean,
)

/**
 * 本地任务召回。
 *
 * 原则：**所有与用户说法相关的任务都带上**，只排除不相关的，而不是取 Top-N。
 * 中文按 bigram 切分、英文/数字按词切分，不引入分词库。
 */
object TaskRetriever {
    /** 召回条数可配置（设置页），越界时夹到这个区间。 */
    const val MIN_LIMIT = 5
    const val MAX_LIMIT = 200
    const val DEFAULT_LIMIT = 20

    /** 兜底模式下补充的最近完成任务条数，支持「改回未完成」。 */
    private const val RECENT_DONE_LIMIT = 10

    fun retrieve(
        projection: WorkspaceProjection,
        query: String,
        nowMs: Long,
        limit: Int = DEFAULT_LIMIT,
    ): RetrievedTasks {
        val effectiveLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val tokens = tokenize(query)
        val ctx = Context(
            today = today,
            nowMs = nowMs,
            listNameById = projection.lists.associate { it.id to it.name },
            tagNameById = projection.tags.associate { it.id to it.name },
        )
        val summary = summarize(projection.tasks, ctx)

        // 先用相关度筛出「相关任务」，再按时间接近度排序取前 N 条（N 由设置决定）
        val relevant = projection.tasks
            .filter { score(it, tokens, ctx) > 0.0 }
            .sortedWith(byDueThenNewest())

        if (relevant.isNotEmpty()) {
            return RetrievedTasks(
                tasks = relevant.take(effectiveLimit),
                summary = summary,
                usedFallback = false,
            )
        }

        // 队列类说法（如「帮我规划今天」）没有关键词命中：给时间/优先级相关的任务
        val urgent = projection.tasks.filter { task ->
            !task.done && (
                isOverdue(task, ctx) ||
                    isDueToday(task, ctx) ||
                    isDueThisWeek(task, ctx) ||
                    task.priority == "high"
                )
        }.sortedWith(byDueThenNewest())
        val recentDone = projection.tasks
            .filter { it.done }
            .sortedByDescending { it.completedAtMs ?: 0L }
            .take(RECENT_DONE_LIMIT)

        return RetrievedTasks(
            tasks = (urgent.take(effectiveLimit) + recentDone).distinctBy { it.id },
            summary = summary,
            usedFallback = true,
        )
    }

    /** 时间接近度：截止早的在前；没有截止时间的按创建时间新的在前。 */
    private fun byDueThenNewest(): Comparator<TaskItem> =
        compareBy<TaskItem> { it.dueAtMs ?: Long.MAX_VALUE }
            .thenByDescending { it.createdAtMs }

    /** 中文 bigram + 英文/数字整词，去重后返回。 */
    fun tokenize(text: String): List<String> {
        val normalized = text.lowercase()
        val tokens = linkedSetOf<String>()
        Regex("[a-z0-9]{2,}").findAll(normalized).forEach { tokens += it.value }
        Regex("[\\u4e00-\\u9fa5]+").findAll(normalized).forEach { match ->
            val run = match.value
            if (run.length == 1) {
                tokens += run
            } else {
                for (index in 0 until run.length - 1) {
                    tokens += run.substring(index, index + 2)
                }
            }
        }
        return tokens.toList()
    }

    private fun score(task: TaskItem, tokens: List<String>, ctx: Context): Double {
        if (tokens.isEmpty()) return 0.0
        val title = task.title.lowercase()
        val notes = task.notes.lowercase()
        val subtasks = task.subtasks.joinToString(" ") { it.text }.lowercase()
        val tagNames = task.tagIds.mapNotNull { ctx.tagNameById[it] }.joinToString(" ").lowercase()
        val listName = ctx.listNameById[task.listId].orEmpty().lowercase()

        var score = 0.0
        tokens.forEach { token ->
            score += when {
                title.contains(token) -> 3.0
                subtasks.contains(token) -> 1.5
                tagNames.contains(token) || listName.contains(token) -> 1.2
                notes.contains(token) -> 1.0
                else -> 0.0
            }
        }
        if (score <= 0.0) return 0.0

        // 以下只影响排序，不决定是否入选
        when {
            !task.done && isOverdue(task, ctx) -> score += 0.6
            isDueToday(task, ctx) -> score += 0.4
        }
        if (task.done && (task.completedAtMs ?: 0L) > ctx.nowMs - 7 * 86_400_000L) score += 0.2
        return score
    }

    private fun summarize(tasks: List<TaskItem>, ctx: Context): TaskSummary {
        val open = tasks.filter { !it.done }
        return TaskSummary(
            openTotal = open.size,
            overdue = open.count { isOverdue(it, ctx) },
            dueToday = open.count { isDueToday(it, ctx) },
            dueThisWeek = open.count { isDueThisWeek(it, ctx) },
            doneTotal = tasks.count { it.done },
        )
    }

    private fun isOverdue(task: TaskItem, ctx: Context): Boolean {
        val due = task.dueAtMs ?: return false
        return !task.done && due < ctx.nowMs
    }

    private fun isDueToday(task: TaskItem, ctx: Context): Boolean =
        dueDate(task, ctx) == ctx.today

    private fun isDueThisWeek(task: TaskItem, ctx: Context): Boolean {
        if (task.done) return false
        val date = dueDate(task, ctx) ?: return false
        val weekStart = ctx.today.minusDays((ctx.today.dayOfWeek.value - 1).toLong())
        val weekEnd = weekStart.plusDays(6)
        return !date.isBefore(weekStart) && !date.isAfter(weekEnd)
    }

    private fun dueDate(task: TaskItem, ctx: Context): LocalDate? =
        task.dueAtMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            ?: task.dueAt?.let { raw ->
                runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            }

    private data class Context(
        val today: LocalDate,
        val nowMs: Long,
        val listNameById: Map<String, String>,
        val tagNameById: Map<String, String>,
    )
}
