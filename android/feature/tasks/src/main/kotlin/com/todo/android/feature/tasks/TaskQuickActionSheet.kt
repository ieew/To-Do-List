package com.todo.android.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todo.android.core.designsystem.PriorityDot
import com.todo.android.core.designsystem.TagChip
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.domain.TagItem
import com.todo.android.core.domain.TaskItem

/**
 * 左滑唤出的快捷操作面板：删除 / 优先级 / 标签。
 * 只做单任务内的三个高频动作，其余仍走详情页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskQuickActionSheet(
    task: TaskItem,
    tags: List<TagItem>,
    onDelete: () -> Unit,
    onPriority: (String) -> Unit,
    onToggleTag: (tagId: String, assigned: Boolean) -> Unit,
) {
    val colors = TodoTheme.colors

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = task.title,
            style = Tokens.Type.pageTitle,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))

        Text("优先级", style = Tokens.Type.badge, color = colors.text3)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low" to "低", "mid" to "中", "high" to "高").forEach { (value, label) ->
                val active = task.priority == value
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Radius.full))
                        .background(if (active) colors.primary50 else colors.bg)
                        .clickable { onPriority(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PriorityDot(value)
                    Text(
                        text = label,
                        style = Tokens.Type.chip,
                        color = if (active) colors.primary else colors.text2,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("标签", style = Tokens.Type.badge, color = colors.text3)
        Spacer(Modifier.height(6.dp))
        if (tags.isEmpty()) {
            Text("当前工作区还没有标签", style = Tokens.Type.meta, color = colors.text3)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    val assigned = tag.id in task.tagIds
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Radius.full))
                            .background(if (assigned) colors.primary50 else colors.bg)
                            .clickable { onToggleTag(tag.id, assigned) }
                            .padding(2.dp),
                    ) {
                        TagChip(text = tag.name, bg = tag.bg, fg = tag.fg)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius.md))
                .background(colors.danger50)
                .clickable(onClick = onDelete)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "删除任务",
                style = Tokens.Type.chip.copy(fontWeight = FontWeight.SemiBold),
                color = colors.danger,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
