package com.todo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todo.android.core.domain.OpSource
import com.todo.android.core.domain.TagItem
import com.todo.android.core.domain.TaskItem

/** 任务卡：S5 列表、日历、详情共用同一视觉。 */
@Composable
fun TaskRow(
    task: TaskItem,
    tagsById: Map<String, TagItem>,
    nowMs: Long,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = Tokens.Type.taskTitle,
) {
    val colors = TodoTheme.colors
    CardContainer(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TodoCheckbox(
                checked = task.done,
                onCheckedChange = onToggle,
                size = 22.dp,
                contentDescription = if (task.done) "标记为未完成" else "标记为已完成",
            )
            Spacer(Modifier.width(2.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = task.title,
                        style = titleStyle,
                        color = if (task.done) colors.text3 else colors.text,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (task.source == OpSource.AI) AiBadge()
                }
                TaskMetaLine(task = task, tagsById = tagsById, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun TaskMetaLine(
    task: TaskItem,
    tagsById: Map<String, TagItem>,
    nowMs: Long,
) {
    val colors = TodoTheme.colors
    val due = TimeText.dueLabel(task.dueAt, nowMs)
    val overdue = !task.done && TimeText.isOverdue(task.dueAt, nowMs)
    val doneLabel = TimeText.completedLabel(task.completedAtMs)
    val doneCount = task.subtasks.count { it.done }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (task.done) {
            Text(
                text = if (doneLabel != null) "已完成 · $doneLabel" else "已完成",
                style = Tokens.Type.meta,
                color = colors.text3,
            )
        } else {
            PriorityDot(task.priority)
            Text(
                text = priorityLabel(task.priority),
                style = Tokens.Type.meta,
                color = colors.text3,
            )
            if (due != null) {
                Text(
                    text = if (overdue) "$due 截止" else due,
                    style = Tokens.Type.meta,
                    color = if (overdue) colors.danger else colors.text3,
                )
            }
        }
        if (task.subtasks.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.full))
                    .background(if (colors.isDark) colors.border2 else Color(0xFFF1F5F9))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "${if (task.done) doneCount else doneCount} / ${task.subtasks.size}",
                    style = Tokens.Type.badge,
                    color = colors.text3,
                )
            }
        }
        task.tagIds.take(2).mapNotNull { tagsById[it] }.forEach { tag ->
            TagChip(text = tag.name, bg = tag.bg, fg = tag.fg)
        }
    }
}

fun priorityLabel(priority: String): String = when (priority) {
    "high" -> "高"
    "low" -> "低"
    else -> "中"
}
