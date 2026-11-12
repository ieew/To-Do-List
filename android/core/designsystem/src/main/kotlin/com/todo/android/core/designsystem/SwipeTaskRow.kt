package com.todo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.todo.android.core.domain.TagItem
import com.todo.android.core.domain.TaskItem

/**
 * 带左右滑快捷操作的任务卡。
 * 右滑（StartToEnd）= 切换完成状态；左滑（EndToStart）= 呼出快捷操作面板。
 * 两个方向都回弹到原位，真正的状态变更由调用方处理。
 */
@Composable
fun SwipeTaskRow(
    task: TaskItem,
    tagsById: Map<String, TagItem>,
    nowMs: Long,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = Tokens.Type.taskTitle,
    swipeEnabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(!task.done)
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMore()
                }

                SwipeToDismissBoxValue.Settled -> Unit
            }
            // 始终回弹：滑块不是删除动画，而是"触发一次动作"。
            false
        },
        positionalThreshold = { distance -> distance * 0.32f },
    )

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = swipeEnabled,
        enableDismissFromEndToStart = swipeEnabled,
        backgroundContent = { SwipeActionBackground(state.dismissDirection, done = task.done) },
    ) {
        TaskRow(
            task = task,
            tagsById = tagsById,
            nowMs = nowMs,
            onToggle = onToggle,
            onClick = onClick,
            titleStyle = titleStyle,
        )
    }
}

@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue, done: Boolean) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val colors = TodoTheme.colors
    val revealDone = direction == SwipeToDismissBoxValue.StartToEnd
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(if (revealDone) colors.success else colors.border2)
            .padding(horizontal = 16.dp),
        contentAlignment = if (revealDone) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (revealDone) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (done) "取消完成" else "完成",
                    style = Tokens.Type.chip,
                    color = Color.White,
                )
            } else {
                Text(text = "删除 · 优先级 · 标签", style = Tokens.Type.chip, color = colors.text2)
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = null,
                    tint = colors.text2,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
