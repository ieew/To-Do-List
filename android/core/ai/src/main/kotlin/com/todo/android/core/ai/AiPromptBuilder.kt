package com.todo.android.core.ai

import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.TaskItem
import com.todo.android.core.domain.WorkspaceProjection
import kotlinx.serialization.json.Json

/**
 * 提示词组装：**稳定规则在前、动态快照在后**。
 *
 * - 规则段每次完全一致，可命中服务商的前缀缓存（DeepSeek / OpenAI 都支持）；
 * - 任务快照走 [TaskRetriever] 本地召回，只带相关任务 + 规模摘要，
 *   避免任务越积越多时把全量塞给模型。
 */
object AiPromptBuilder {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun build(
        projection: WorkspaceProjection,
        nowMs: Long,
        query: String,
        maxOperations: Int,
        recallLimit: Int = TaskRetriever.DEFAULT_LIMIT,
    ): AiPromptResult {
        val retrieved = TaskRetriever.retrieve(projection, query, nowMs, recallLimit)
        val tagNameById = projection.tags.associate { it.id to it.name }
        val listNameById = projection.lists.associate { it.id to it.name }

        val lists = projection.lists.joinToString(",") { list ->
            """{"id":"${list.id}","name":"${list.name.escape()}"}"""
        }
        val tags = projection.tags.joinToString(",") { tag ->
            """{"id":"${tag.id}","name":"${tag.name.escape()}"}"""
        }
        val tasks = retrieved.tasks.joinToString(",") { task -> task.toJson(tagNameById, listNameById) }
        val summary = retrieved.summary

        val context = buildString {
            append("{\"now\":\"").append(IsoTime.nowIso(nowMs)).append("\",")
            append("\"summary\":{")
            append("\"open_total\":").append(summary.openTotal).append(',')
            append("\"overdue\":").append(summary.overdue).append(',')
            append("\"due_today\":").append(summary.dueToday).append(',')
            append("\"due_this_week\":").append(summary.dueThisWeek).append(',')
            append("\"done_total\":").append(summary.doneTotal)
            append("},")
            append("\"lists\":[").append(lists).append("],")
            append("\"tags\":[").append(tags).append("],")
            append("\"tasks\":[").append(tasks).append("]}")
        }

        val prompt = INTENT_RULES.replace("\$MAX_OPERATIONS", maxOperations.toString()) +
            "\n\n" + CONTEXT_HINT + "\n" + context

        return AiPromptResult(
            prompt = prompt,
            recalledCount = retrieved.tasks.size,
            totalCount = summary.openTotal + summary.doneTotal,
            usedFallback = retrieved.usedFallback,
        )
    }

    private fun TaskItem.toJson(
        tagNameById: Map<String, String>,
        listNameById: Map<String, String>,
    ): String {
        val subtasks = subtasks.joinToString(",") { sub ->
            """{"id":"${sub.id}","text":"${sub.text.escape()}","done":${sub.done}}"""
        }
        val tagNames = tagIds.mapNotNull { tagNameById[it] }.joinToString(",") { "\"${it.escape()}\"" }
        return """{"id":"$id","title":"${title.escape()}","status":"${if (done) "done" else "todo"}",""" +
            """"due_at":"${dueAt ?: ""}","list":"${listNameById[listId].orEmpty().escape()}",""" +
            """"tags":[$tagNames],"subtasks":[$subtasks]}"""
    }

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private const val CONTEXT_HINT =
        "下面是本地召回的工作区上下文：summary 是全量规模统计；tasks 是按时间接近度排序、" +
            "与本次说法相关的任务（已截断到设置里的条数上限）。若 summary 显示还有更多任务但未列出，" +
            "请提示用户缩小范围，不要编造未出现的 task_id。"

    private val INTENT_RULES = """
你会收到一份工作区快照（JSON）和用户的说法。请输出**操作列表**，不要输出最终任务文本。
只输出 JSON：{"message":"一句话说明","operations":[ ... ]}

可用操作（op 字段）：
- create_task: temp_id, title(必填), notes, priority(high|mid|low), list(清单名), due_at(RFC3339), tags([标签名]), subtasks([文本])
- update_fields: task_id, patch{字段:值}, clear_fields[字段]
- add_subtasks: task_id, subtasks([文本])
- set_subtask_done: task_id, subtask_id, done(true|false)
- add_tags / remove_tags: task_id, tags([标签名])
- set_status: task_id, status(todo|done)
- delete_task: task_id

规则：
1. task_id / subtask_id 只能取快照里出现过的值，不要编造。
2. update_fields 的 patch 只允许 title / notes / start_at / due_at / remind_at / priority / list_id。
3. 清单与标签用名称，必须是快照里已存在的名称。
4. 时间用带时区的 RFC3339，基于快照里的 now 推算；用户没说时间就不要猜。
5. 一次最多 ${'$'}MAX_OPERATIONS 条操作，不要输出解释或 Markdown。
6. 只要用户的说法对应快照里的任务或能新建任务，就必须给出至少一条 operation；
   确实无法处理时也返回 `{"message":"原因","operations":[]}`，不要只返回文本。
7. 快照里 status=done 的任务是最近完成的，可以用 set_status(todo) 改回未完成。
""".trim()
}

data class AiPromptResult(
    val prompt: String,
    /** 本次实际放进上下文的任务数。 */
    val recalledCount: Int,
    /** 工作区任务总数（含已完成），用于提示是否做了裁剪。 */
    val totalCount: Int,
    val usedFallback: Boolean,
)
