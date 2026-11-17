package com.todo.android.core.ai

import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.WorkspaceProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Android 端只有 todo / done 两种状态（与 `TaskCommands.setDone` 一致）。 */
object AiTaskStatus {
    const val TODO = "todo"
    const val DONE = "done"
    val all = setOf(TODO, DONE)
}

/**
 * AI 意图：把模型输出归一化为「操作列表」，而不是直接给出最终任务。
 *
 * 模型只能引用上下文里出现过的 `task_id` / `subtask_id`；所有操作由客户端
 * 转成普通 op 写入，服务端不感知 AI。字段与 Windows 端 `ai_intent` 契约一致。
 */
sealed interface AiOperation {
    /** 预览行文案。 */
    val summary: String

    /** 会影响已有数据（状态改成完成 / 删除），需要二次确认。 */
    val dangerous: Boolean get() = false

    data class CreateTask(
        val title: String,
        val notes: String,
        val priority: String,
        val listId: String,
        val listName: String,
        val dueAtIso: String,
        val tagIds: List<String>,
        val tagNames: List<String>,
        val subtasks: List<String>,
    ) : AiOperation {
        override val summary: String
            get() = buildString {
                append("新建「").append(title).append("」")
                if (listName.isNotBlank()) append(" · ").append(listName)
                if (dueAtIso.isNotBlank()) append(" · ").append(AiIntentParser.shortTime(dueAtIso))
                if (tagNames.isNotEmpty()) append(" · ").append(tagNames.joinToString("/"))
            }
    }

    data class UpdateFields(
        val taskId: String,
        val taskTitle: String,
        val patch: Map<String, String>,
        val clearFields: List<String>,
    ) : AiOperation {
        override val summary: String
            get() = buildString {
                append("修改「").append(taskTitle).append("」")
                val changes = patch.entries.joinToString("、") { (key, value) ->
                    aiFieldLabel(key) + "=" + value
                }
                val cleared = clearFields.joinToString("、") { aiFieldLabel(it) }
                when {
                    changes.isNotEmpty() && cleared.isNotEmpty() -> append(" · ").append(changes).append("；清空 ").append(cleared)
                    changes.isNotEmpty() -> append(" · ").append(changes)
                    cleared.isNotEmpty() -> append(" · 清空 ").append(cleared)
                }
            }
    }

    data class AddSubtasks(
        val taskId: String,
        val taskTitle: String,
        val texts: List<String>,
    ) : AiOperation {
        override val summary: String get() = "为「$taskTitle」新增 ${texts.size} 个子任务"
    }

    data class SetSubtaskDone(
        val taskId: String,
        val taskTitle: String,
        val subtaskId: String,
        val subtaskText: String,
        val done: Boolean,
    ) : AiOperation {
        override val summary: String get() = "${if (done) "勾选" else "取消勾选"}「$taskTitle」的子任务「$subtaskText」"
    }

    data class AddTags(
        val taskId: String,
        val taskTitle: String,
        val tagIds: List<String>,
        val tagNames: List<String>,
    ) : AiOperation {
        override val summary: String get() = "为「$taskTitle」加标签 " + tagNames.joinToString("/")
    }

    data class RemoveTags(
        val taskId: String,
        val taskTitle: String,
        val tagIds: List<String>,
        val tagNames: List<String>,
    ) : AiOperation {
        override val summary: String get() = "移除「$taskTitle」的标签 " + tagNames.joinToString("/")
    }

    data class SetStatus(
        val taskId: String,
        val taskTitle: String,
        val status: String,
    ) : AiOperation {
        override val dangerous: Boolean get() = status == AiTaskStatus.DONE

        override val summary: String
            get() = "把「$taskTitle」标记为" + when (status) {
                AiTaskStatus.DONE -> "已完成"
                else -> "待办"
            }
    }

    data class DeleteTask(
        val taskId: String,
        val taskTitle: String,
    ) : AiOperation {
        override val dangerous: Boolean get() = true

        override val summary: String get() = "删除「$taskTitle」"
    }
}

private fun aiFieldLabel(field: String): String = when (field) {
    "title" -> "标题"
    "notes" -> "备注"
    "start_at" -> "开始时间"
    "due_at" -> "截止时间"
    "remind_at" -> "提醒时间"
    "priority" -> "优先级"
    "list_id" -> "清单"
    else -> field
}

data class AiIntentPlan(
    val operations: List<AiOperation>,
    val message: String?,
    val warnings: List<String>,
) {
    val hasDangerous: Boolean get() = operations.any { it.dangerous }
}

/** 解析模型输出；未知操作/非法目标只记 warning，不整体失败。 */
object AiIntentParser {
    const val MAX_OPERATIONS = 50
    private const val MAX_CONTEXT_TASKS = 120

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val EDITABLE_FIELDS = linkedSetOf("title", "notes", "start_at", "due_at", "remind_at", "priority", "list_id")
    private val PRIORITIES = setOf("high", "mid", "low")

    fun buildPrompt(projection: WorkspaceProjection, nowMs: Long): String {
        val lists = projection.lists.joinToString(",") { list ->
            """{"id":"${list.id}","name":"${list.name.escape()}"}"""
        }
        val tags = projection.tags.joinToString(",") { tag ->
            """{"id":"${tag.id}","name":"${tag.name.escape()}"}"""
        }
        val tagNameById = projection.tags.associate { it.id to it.name }
        val listNameById = projection.lists.associate { it.id to it.name }
        val tasks = projection.tasks
            .filter { !it.done }
            .sortedBy { it.dueAtMs ?: Long.MAX_VALUE }
            .take(MAX_CONTEXT_TASKS)
            .joinToString(",") { task ->
                val subtasks = task.subtasks.joinToString(",") { sub ->
                    """{"id":"${sub.id}","text":"${sub.text.escape()}","done":${sub.done}}"""
                }
                val tagNames = task.tagIds.mapNotNull { tagNameById[it] }.joinToString(",") { "\"${it.escape()}\"" }
                """{"id":"${task.id}","title":"${task.title.escape()}","status":"${if (task.done) "done" else "todo"}",""" +
                    """"due_at":"${task.dueAt ?: ""}","list":"${listNameById[task.listId].orEmpty().escape()}",""" +
                    """"tags":[$tagNames],"subtasks":[$subtasks]}"""
            }

        return buildString {
            append("{\"now\":\"").append(IsoTime.nowIso(nowMs)).append("\",")
            append("\"lists\":[").append(lists).append("],")
            append("\"tags\":[").append(tags).append("],")
            append("\"tasks\":[").append(tasks).append("]}")
        } + "\n\n" + INTENT_RULES
    }

    fun parse(text: String, projection: WorkspaceProjection, nowMs: Long): AiIntentPlan {
        val raw = extractJson(text)
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return AiIntentPlan(emptyList(), null, listOf("AI 返回格式不正确，已忽略"))
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull

        val warnings = mutableListOf<String>()
        val operations = mutableListOf<AiOperation>()

        val opArray = root["operations"] as? JsonArray
        if (opArray != null) {
            opArray.take(MAX_OPERATIONS).forEachIndexed { index, element ->
                val op = runCatching { element.jsonObject }.getOrNull()
                if (op == null) {
                    warnings += "第 ${index + 1} 条操作格式不正确，已忽略"
                    return@forEachIndexed
                }
                parseOperation(op, projection, nowMs, warnings)?.let(operations::add)
            }
            if (opArray.size > MAX_OPERATIONS) {
                warnings += "一次最多执行 $MAX_OPERATIONS 条操作，超出的部分已忽略"
            }
        } else {
            // 兼容旧格式：{"tasks":[...]} 全部按新建处理
            (root["tasks"] as? JsonArray)?.forEach { element ->
                val task = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                parseCreate(task, projection, nowMs, warnings)?.let(operations::add)
            }
        }

        if (operations.isEmpty()) {
            val hint = message?.takeIf { it.isNotBlank() }?.let { "（AI 说明：$it）" }.orEmpty()
            val preview = raw.replace("\n", " ").take(160)
            warnings += "AI 没有返回可执行的操作$hint；原始返回：$preview"
        }
        return AiIntentPlan(operations = operations, message = message, warnings = warnings.distinct())
    }

    private fun parseOperation(
        op: JsonObject,
        projection: WorkspaceProjection,
        nowMs: Long,
        warnings: MutableList<String>,
    ): AiOperation? {
        val kind = (op["op"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (kind) {
            "create_task" -> parseCreate(op, projection, nowMs, warnings)
            "update_fields" -> {
                val target = requireTask(op, projection, warnings) ?: return null
                val patch = linkedMapOf<String, String>()
                val patchObj = op["patch"] as? JsonObject
                patchObj?.forEach { (key, value) ->
                    if (key !in EDITABLE_FIELDS) {
                        warnings += "字段「$key」不支持由 AI 修改，已忽略"
                        return@forEach
                    }
                    val text = (value as? JsonPrimitive)?.contentOrNull
                    if (text.isNullOrBlank()) {
                        if (key in setOf("start_at", "due_at", "remind_at")) patch[key] = ""
                        return@forEach
                    }
                    if (key.endsWith("_at") && IsoTime.parseMs(text) == null) {
                        warnings += "「${key}」不是合法 ISO 8601 时间，已忽略"
                        return@forEach
                    }
                    if (key == "priority" && text.lowercase() !in PRIORITIES) {
                        warnings += "优先级「$text」不合法，已忽略"
                        return@forEach
                    }
                    patch[key] = if (key == "priority") text.lowercase() else text
                }
                val clearFields = (op["clear_fields"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it in EDITABLE_FIELDS }
                    .orEmpty()
                if (patch.isEmpty() && clearFields.isEmpty()) {
                    warnings += "「${target.title}」的修改内容为空，已忽略"
                    return null
                }
                AiOperation.UpdateFields(target.id, target.title, patch, clearFields)
            }

            "add_subtasks" -> {
                val target = requireTask(op, projection, warnings) ?: return null
                val texts = (op["subtasks"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (texts.isEmpty()) return null
                AiOperation.AddSubtasks(target.id, target.title, texts)
            }

            "set_subtask_done" -> {
                val target = requireTask(op, projection, warnings) ?: return null
                val subtaskId = (op["subtask_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val subtask = target.subtasks.firstOrNull { it.id == subtaskId }
                if (subtask == null) {
                    warnings += "「${target.title}」没有子任务 $subtaskId，已忽略"
                    return null
                }
                val done = (op["done"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
                AiOperation.SetSubtaskDone(target.id, target.title, subtask.id, subtask.text, done)
            }

            "add_tags", "remove_tags" -> {
                val target = requireTask(op, projection, warnings) ?: return null
                val names = (op["tags"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                val byName = projection.tags.associateBy { it.name.trim() }
                val matched = names.mapNotNull { name -> byName[name] }
                names.filter { byName[it] == null }.forEach { warnings += "标签「$it」不存在，已忽略" }
                if (matched.isEmpty()) return null
                if (kind == "add_tags") {
                    AiOperation.AddTags(target.id, target.title, matched.map { it.id }, matched.map { it.name })
                } else {
                    AiOperation.RemoveTags(target.id, target.title, matched.map { it.id }, matched.map { it.name })
                }
            }

            "set_status" -> {
                val target = requireTask(op, projection, warnings) ?: return null
                val status = (op["status"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
                if (status !in AiTaskStatus.all) {
                    warnings += "状态「$status」不合法，已忽略"
                    return null
                }
                AiOperation.SetStatus(target.id, target.title, status)
            }

            "delete_task" -> {
                val target = requireTask(op, projection, warnings) ?: return null
                AiOperation.DeleteTask(target.id, target.title)
            }

            else -> {
                warnings += "不支持的操作「$kind」，已忽略"
                null
            }
        }
    }

    private fun parseCreate(
        op: JsonObject,
        projection: WorkspaceProjection,
        nowMs: Long,
        warnings: MutableList<String>,
    ): AiOperation.CreateTask? {
        val title = (op["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (title.isEmpty()) {
            warnings += "忽略了一条没有标题的新建操作"
            return null
        }
        val defaultList = projection.lists.firstOrNull { it.smart } ?: projection.lists.firstOrNull()
        val listName = (op["list"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val list = if (listName.isBlank()) {
            defaultList
        } else {
            projection.lists.firstOrNull { it.name.trim() == listName } ?: run {
                warnings += "清单「$listName」不存在，已回落到默认清单"
                defaultList
            }
        }
        if (list == null) {
            warnings += "当前工作区没有可用清单，无法新建「$title」"
            return null
        }

        val tagsByName = projection.tags.associateBy { it.name.trim() }
        val tagNames = (op["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val matchedTags = tagNames.mapNotNull { tagsByName[it] }
        tagNames.filter { tagsByName[it] == null }.forEach { warnings += "标签「$it」不存在，已忽略" }

        val dueRaw = (op["due_at"] as? JsonPrimitive)?.contentOrNull
        val dueIso = dueRaw?.takeIf { IsoTime.parseMs(it) != null }
            ?: run {
                warnings += "「$title」的截止时间缺失或格式不正确，已按 7 天后补全"
                IsoTime.afterDaysIso(7, nowMs)
            }
        val notes = (op["notes"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val priority = (op["priority"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            ?.takeIf { it in PRIORITIES } ?: "mid"
        val subtasks = (op["subtasks"] as? JsonArray)
            ?.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> (element["text"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            }
            .orEmpty()

        return AiOperation.CreateTask(
            title = title,
            notes = notes,
            priority = priority,
            listId = list.id,
            listName = list.name,
            dueAtIso = dueIso,
            tagIds = matchedTags.map { it.id },
            tagNames = matchedTags.map { it.name },
            subtasks = subtasks,
        )
    }

    private fun requireTask(
        op: JsonObject,
        projection: WorkspaceProjection,
        warnings: MutableList<String>,
    ): com.todo.android.core.domain.TaskItem? {
        val taskId = (op["task_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val task = projection.tasks.firstOrNull { it.id == taskId }
        if (task == null) {
            warnings += "任务 $taskId 不在当前上下文中，已忽略"
        }
        return task
    }

    fun shortTime(iso: String): String = runCatching {
        java.time.OffsetDateTime.parse(iso)
            .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    }.getOrDefault(iso)

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

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
5. 一次最多 $MAX_OPERATIONS 条操作，不要输出解释或 Markdown。
6. 只要用户的说法对应快照里的任务或能新建任务，就必须给出至少一条 operation；
   确实无法处理时也返回 `{"message":"原因","operations":[]}`，不要只返回文本。
""".trim()
}
