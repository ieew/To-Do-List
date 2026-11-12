package com.todo.android.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todo.android.core.designsystem.AiBadge
import com.todo.android.core.designsystem.EmptyState
import com.todo.android.core.designsystem.TodoTheme
import com.todo.android.core.designsystem.TodoToast
import com.todo.android.core.designsystem.Tokens
import com.todo.android.core.designsystem.TimeText
import com.todo.android.core.domain.HistoryEntry
import com.todo.android.core.domain.OpSource
import kotlinx.coroutines.delay

@Composable
fun HistoryScreen(
    taskId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = TodoTheme.colors

    LaunchedEffect(taskId) { viewModel.load(taskId) }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2200)
            viewModel.showToast(null)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.ChevronLeft,
                    contentDescription = "返回",
                    tint = colors.text2,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(Tokens.Radius.sm))
                        .clickable(onClick = onBack)
                        .padding(4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("任务历史", style = Tokens.Type.section, color = colors.text2)
                    Text(state.taskTitle, style = Tokens.Type.meta, color = colors.text3)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .background(colors.primary50)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "日志只追加：历史记录不可删除，只能以更高 HLC 的反向 op 撤销。AI 操作与手动操作在数据层等价。",
                    style = Tokens.Type.meta,
                    color = colors.primary,
                )
            }

            if (state.entries.isEmpty() && !state.loading) {
                EmptyState(title = "这条任务还没有变更记录")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(state.entries, key = { it.opId }) { entry ->
                        HistoryItem(entry = entry, onUndo = { viewModel.undo(entry) })
                    }
                }
            }
        }

        TodoToast(
            message = state.toast,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}

@Composable
private fun HistoryItem(entry: HistoryEntry, onUndo: () -> Unit) {
    val colors = TodoTheme.colors
    val dotColor = when (entry.source) {
        OpSource.AI -> colors.purple
        OpSource.SYSTEM -> colors.text3
        else -> colors.primary
    }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(colors.card)
                    .border(2.dp, dotColor, CircleShape),
            )
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(64.dp)
                    .background(colors.border),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(TimeText.historyTime(entry.atMs), style = Tokens.Type.meta, color = colors.text3)
                if (entry.source == OpSource.AI) {
                    AiBadge()
                } else {
                    Text(entry.actorName, style = Tokens.Type.meta, color = colors.text2)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(entry.action, style = Tokens.Type.body, color = colors.text)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.xs))
                    .background(colors.border2)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(entry.diffPrefix, style = Tokens.Type.mono, color = colors.text2)
                val oldText = entry.diffOldText
                if (oldText != null) {
                    Text(
                        oldText,
                        style = Tokens.Type.mono,
                        color = colors.text3,
                        textDecoration = TextDecoration.LineThrough,
                    )
                    Text(" → ", style = Tokens.Type.mono, color = colors.text3)
                }
                Text(entry.diffNewText, style = Tokens.Type.mono, color = colors.success)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(entry.opId)
                    entry.model?.let { append(" · model: ").append(it) }
                    append(" · source=").append(entry.source.wire)
                },
                style = Tokens.Type.mono.copy(fontSize = 9.5.sp),
                color = colors.text3,
            )
            if (entry.undoable) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (entry.undone) colors.border2 else colors.primary50)
                        .clickable(enabled = !entry.undone, onClick = onUndo)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (entry.undone) "已生成撤销 op" else "撤销此操作",
                        style = Tokens.Type.chip,
                        color = if (entry.undone) colors.text3 else colors.primary,
                    )
                }
            }
        }
    }
}
