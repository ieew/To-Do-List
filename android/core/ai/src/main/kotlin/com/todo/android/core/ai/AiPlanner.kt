package com.todo.android.core.ai

import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.WorkspaceProjection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把草案映射到当前工作区的清单/标签，并收集字段告警。
 * 未知清单/标签不报错，只给出「已忽略/已回落」提示。
 */
@Singleton
class AiPlanner @Inject constructor() {
    fun plan(drafts: List<AiDraftTask>, projection: WorkspaceProjection, nowMs: Long): AiPlan {
        val warnings = mutableListOf<String>()
        val defaultList = projection.lists.firstOrNull { it.smart } ?: projection.lists.firstOrNull()
        val listsByName = projection.lists.associateBy { it.name.trim() }
        val tagsByName = projection.tags.associateBy { it.name.trim() }

        val tasks = drafts.mapNotNull { draft ->
            val title = draft.title.trim()
            if (title.isEmpty()) {
                warnings += "忽略了一条没有标题的草案"
                return@mapNotNull null
            }
            val taskWarnings = mutableListOf<String>()

            val list = draft.list?.trim()?.let { name ->
                listsByName[name] ?: run {
                    taskWarnings += "清单「$name」未知，已回落到当前清单"
                    null
                }
            } ?: defaultList
            if (list == null) {
                warnings += "当前工作区没有可用清单，无法写入「$title」"
                return@mapNotNull null
            }

            val matchedTags = draft.tags.mapNotNull { raw ->
                val name = raw.trim()
                tagsByName[name] ?: run {
                    taskWarnings += "标签「$name」未知，已忽略"
                    null
                }
            }

            val startIso = draft.startAt?.takeIf { IsoTime.parseMs(it) != null }
                ?: run {
                    taskWarnings += "开始时间缺失或格式不正确，已按创建时刻补全"
                    IsoTime.nowIso(nowMs)
                }
            val dueIso = draft.dueAt?.takeIf { IsoTime.parseMs(it) != null }
                ?: run {
                    taskWarnings += "截止时间缺失或格式不正确，已按开始时间 + 7 天补全"
                    IsoTime.afterDaysIso(7, IsoTime.parseMs(startIso) ?: nowMs)
                }

            val priority = draft.priority.lowercase().let {
                if (it in setOf("high", "mid", "low")) it else {
                    taskWarnings += "优先级「${draft.priority}」不合法，已按「中」处理"
                    "mid"
                }
            }

            PlannedTask(
                title = title,
                notes = draft.notes,
                priority = priority,
                startAtIso = startIso,
                dueAtIso = dueIso,
                listId = list.id,
                listName = list.name,
                tagIds = matchedTags.map { it.id },
                tagNames = matchedTags.map { it.name },
                subtasks = draft.subtasks.map { it.text.trim() }.filter { it.isNotEmpty() },
                warnings = taskWarnings,
            )
        }

        val allWarnings = warnings + tasks.flatMap { it.warnings }.distinct()
        return AiPlan(tasks = tasks, warnings = allWarnings)
    }
}
